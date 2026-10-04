# 开发日志 (DEVLOG)

> 这是一份给其他开发者的项目进度路线图。每一条都标注了完成状态、技术决策、坑点与下一步。
> 最新进度请看本文件底部。

---

## 项目目标

在 Android 上原生跑通 KataGo，最终实现**离线、NPU 加速、阿Q围棋级别**的围棋 AI 体验。

技术路线分三阶段：

| 阶段 | 后端 | 速度 (Snapdragon 888) | 状态 |
|---|---|---|---|
| v1 | EIGEN CPU | ~2 nps (5秒/手) | ✅ **代码完成，待本地编译验证** |
| v2 | ONNX Runtime + NNAPI EP | ~50-100 nps | 🗓 规划中 |
| v3 | ONNX Runtime + QNN EP (Hexagon NPU) | ~300-700 nps (阿Q级别) | 🗓 规划中 |

每阶段都基于上一阶段的代码，**Kotlin UI 层完全不动**，只替换 `katago_engine.cpp` 的 `initReal()` 实现。

---

## 调研结论（2026-10）

### 1. 社区方案对比

| 项目 | 接入方式 | 是否开源 | 速度参考 |
|---|---|---|---|
| **karino2/PaooGo** | EIGEN CPU + JNI | ✅ 开源 | ~2 nps |
| **阿Q围棋** | 本地 NPU + 远程 GPU（双路径） | ❌ 闭源 | ~300-700 nps |
| **BadukAI (aki65)** | 本地 NPU | ❌ 仅 APK | ~330-720 nps |

### 2. 关键发现

- **阿Q围棋断网能用**：多源证据（9K9K FAQ / 多特 / 游戏宝）确认其本地内置 KataGo，利用手机 NPU 推理，远程 ikatago 只是可选功能。
- **karino2 实际走 EIGEN**：他在博客 `パオ碁 開発メモ` 里写的 NDK 编译命令是 `-DUSE_BACKEND=EIGEN`，不是 OpenCL。`maxVisits=10` → 5秒/手。
- **karino2 的 3 处 CMakeLists 改动**：
  1. 注释 `find_package(Eigen3)`（NDK 找不到）
  2. `include_directories(external/eigen-3.4.0)`
  3. `target_compile_definitions(... BYTE_ORDER=1234 LITTLE_ENDIAN=1234 BIG_ENDIAN=4321)`（NDK 缺这些宏）
- **libzip 不需要**：karino2 笔记原话"selfplay 才用，下棋用不到"。
- **阿Q/BadukAI 都闭源**：NPU 路径无开源参考，必须自己摸。

### 3. 技术栈选型

- v1 用 karino2 已验证的 EIGEN 路径，**不重新发明轮子**
- v2/v3 用 ONNX Runtime Android AAR + NNAPI/QNN EP
- 不走 OpenCL：手机 GPU OpenCL 只有 ~50-100 nps，远不如 NPU；且 `dlopen libOpenCL.so` 路径处理麻烦

---

## v1 进度（EIGEN CPU 后端）

### ✅ 已完成

#### 阶段 1a：STUB 模式（验证链路）

- [x] 项目骨架：Gradle + NDK + CMake 配置
- [x] JNI 桥：`jlong` handle 承载 C++ 指针
- [x] C++ 引擎封装：`katago_engine.{h,cpp}`，STUB 模式 `genmove` 返回随机合法点
- [x] Kotlin UI：棋盘 + 触摸落子 + 三模式（双人/AI执白/AI执黑）
- [x] 验证：UI → JNI → C++ → UI 整条链路通

#### 阶段 1b：规则 + GTP + 导出

- [x] **Board.kt**：围棋规则完整实现
  - 提子（围死对方连通块）
  - 自杀禁着
  - 超级劫（FNV-1a 哈希 + 历史去重）
  - 中国数子（flood-fill 空区归属）
  - 终局判定（双方 pass）
  - 强制终局（不要求 pass）
  - 棋盘大小 2≤N≤19
- [x] **GtpEngine.kt**：GTP 协议层，18 个命令
  - 基础：`protocol_version` `name` `version` `known_command` `list_commands` `quit`
  - 棋盘：`boardsize` `clear_board` `komi` `play` `genmove` `showboard`
  - 终局：`final_score` `force_final_score` `final_status`
  - 分析：`kata-analyze` `lz-analyze` `analyze` `influence_map`
- [x] **SgfWriter.kt**：SGF 棋谱导出（FF[4]，FileProvider + ACTION_SEND 分享）
  - 坐标三处一致：内部 (x,y) ↔ GTP vertex (跳过 I) ↔ SGF (a..s, y 翻转)
  - 自检 19/19 PASS
- [x] **InfluenceMap.kt**：影响力函数形势判断（GNU Go / Sabaki 风格）
  - 8 邻居均值 × 0.92 衰减，迭代 12 次
  - 输出：blackTerritory / whiteTerritory / dame / scoreLead / winrateBlack / map[]
- [x] **BoardView.kt**：影响力地图可视化（半透黑/白染色）

#### 阶段 1c：EIGEN 真实接入代码（**本次提交**）

- [x] **CMakeLists.txt 重写**：`file(GLOB)` KataGo 源码 + EIGEN 头路径 + karino2 3 处改动 + 生成 `gitinfo.h`
- [x] **katago_engine.cpp `initReal()`**：
  - Logger 输出重定向到 logcat（`LogcatStreamBuf`）
  - `ConfigParser` 读 `gtp.cfg`
  - `Setup::initializeSession`
  - `SearchParams::forTestsV2()` 基线，覆盖 `maxVisits=10`、`numThreads=1`
  - `Rules::parseRules("chinese")`
  - `Setup::initializeNNEvaluator(nnXLen=19, nnYLen=19, SETUP_FOR_GTP)`
- [x] **katago_engine.cpp `realGenmove()`**：
  - 棋盘大小变化时重建 `AsyncBot`
  - `Board` + `BoardHistory` 重建
  - `hist.makeBoardMoveTolerant()` 回放 moves 数组
  - `bot->setPosition()` + `bot->genMoveSynchronous()`
  - `Location::getX/Y(loc, boardSize)` → `x*N+y`
  - PASS_LOC / NULL_LOC → return -1
  - try/catch 兜底回退 stub
- [x] **gtp.cfg**：Android 优化版配置
- [x] **BUILDING.md**：本地构建指南 + FAQ

### 🚧 待本地验证

- [ ] 本地 Android Studio 编译通过（沙盒无 NDK，需开发者本地跑）
- [ ] 真机下出 KataGo 第一手棋（logcat 看 `genmove: chosen=(x,y)`）
- [ ] 排查 FAQ 列出的常见编译错误

### 关键代码位置

| 文件 | 作用 |
|---|---|
| [app/src/main/cpp/CMakeLists.txt](app/src/main/cpp/CMakeLists.txt) | EIGEN 接入的 CMake 配置 |
| [app/src/main/cpp/katago_engine.cpp](app/src/main/cpp/katago_engine.cpp) | `initReal()` + `realGenmove()` |
| [app/src/main/cpp/katago_engine.h](app/src/main/cpp/katago_engine.h) | `KataGo::Context` 前置声明 |
| [app/src/main/assets/gtp.cfg](app/src/main/assets/gtp.cfg) | Android GTP 配置 |
| [BUILDING.md](BUILDING.md) | 本地构建指南 |

### 开发者要做的事

1. Clone 本仓库
2. 按 [BUILDING.md](BUILDING.md) 准备 KataGo 源码 + Eigen + 模型
3. 解开 `app/build.gradle.kts` 的 3 行注释，路径改成自己的
4. 在 `MainActivity.kt` 加 `copyAssetsToFiles()` + `gtp.gen = JniGen(engine)`
5. Android Studio Run → 真机

---

## v2 规划（ONNX Runtime + NNAPI EP）

### 目标

把 v1 的 EIGEN 推理换成 ONNX Runtime + NNAPI EP，速度从 ~2 nps 提升到 ~50-100 nps。

### 工作分解

- [ ] **m1: 模型转换** — `katago exportmodel -model b10c384.bin -export-onnx b10c384.onnx`
- [ ] **m2: ORT AAR 集成** — `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")`
- [ ] **m3: 写 `OnnxEvaluator`** — 替换 `Setup::initializeNNEvaluator`，调 `OrtSession`
- [ ] **m4: NNAPI EP 注册** — `SessionOptions.appendExecutionProvider("nnapi")`
- [ ] **m5: 推理正确性验证** — 同一局面，EIGEN vs ORT 输出 winrate 误差 < 1%

### 难点

- KataGo 的 `NNEvaluator` 类和 `Search` 紧耦合，不是简单的"输入张量→输出张量"接口
- 内部维护 NN 输入缓冲区、批量压缩、异步提交、多线程同步
- 写功能等价的 `OnnxEvaluator` 需要完整理解 KataGo 的 NN 调用流程

---

## v3 规划（QNN EP / Hexagon NPU）

### 目标

在 v2 基础上启用 QNN EP，速度提升到 ~300-700 nps（阿Q围棋级别）。

### 工作分解

- [ ] 模型 INT8 量化（QAT 或 PTQ）
- [ ] QNN SDK 集成，引入 `libQnnHtp.so`
- [ ] `SessionOptions.appendExecutionProvider("qnn", {backend_path: "QnnHtp.so"})`
- [ ] 设备芯片检测，自动选 NNAPI 还是 QNN
- [ ] 性能调优 + 多机型适配

### 难点

- 阿Q/BadukAI 都闭源，无开源参考
- 不同芯片家族 EP 行为差异大
- 模型量化可能掉精度

---

## 架构图

```
┌─────────────────────────────────────────────────────────┐
│  Kotlin UI 层（已稳定，v1→v3 不动）                       │
│  MainActivity / BoardView / GtpEngine / SgfWriter        │
└─────────────┬─────────────────────────────┬────────────┘
              │ external fun / JNI          │ 资源加载
              ▼                             ▼
┌─────────────────────────────┐  ┌─────────────────────┐
│  C++ 引擎层（v1→v3 渐进替换）│  │  模型 / 配置         │
│  katago_engine.cpp          │  │  b10c384.bin         │
│  ┌────────────────────────┐ │  │  gtp.cfg             │
│  │ v1: EIGEN 真接入        │ │  └─────────────────────┘
│  │   Setup::initialize... │ │
│  │   AsyncBot::genMove... │ │
│  └────────────────────────┘ │
│  ┌────────────────────────┐ │
│  │ v2: ORT + NNAPI EP     │ │  ← 下一步
│  │   OnnxEvaluator        │ │
│  │   OrtSession           │ │
│  └────────────────────────┘ │
│  ┌────────────────────────┐ │
│  │ v3: ORT + QNN EP      │ │  ← 最终目标
│  │   libQnnHtp.so         │ │
│  │   Hexagon HTP          │ │
│  └────────────────────────┘ │
└─────────────────────────────┘
```

---

## 开发节奏建议

| 里程碑 | 工作量 | 价值 |
|---|---|---|
| v1 编译跑通 | 1-2 天（本地） | 能下棋，5秒/手，可复盘 |
| v2 推理验证 | 3-5 天 | 速度 25-50x，接近实时 |
| v2 完整接入 | 1-2 周 | 50-100 nps，体验良好 |
| v3 NPU 启用 | 2-4 周 | 阿Q级别，可商用 |

---

## 调试技巧

### 看 logcat

```bash
adb logcat -s KataGoJNI:I KataGoEngine:I KataGo:I
```

关键日志：
- `KataGoEngine: real mode ready` → initReal() 成功
- `KataGo: Loaded neural net with nnXLen 19 nnYLen 19` → 模型加载成功
- `KataGoEngine: genmove: chosen=(x,y) loc=...` → genmove 返回

### 编译错误排查

见 [BUILDING.md](BUILDING.md) FAQ 节，覆盖：
- `Eigen3 was not found`
- `BYTE_ORDER already defined`
- `undefined reference to libzip_*`
- `OpenCL 不可用，回退 stub`（老代码残留，clean 一下）

### 跑 Web 测试

```bash
cd KataGoAndroid/web-test
python3 -m http.server 8080
# 浏览器访问 http://localhost:8080/
# 点 "运行自检"，19/19 PASS 即规则与坐标转换正确
```

---

## 贡献指南

### 接入 v1（EIGEN）

按 [BUILDING.md](BUILDING.md) 一步步走。跑通后欢迎在 GitHub Issue 反馈：
- 编译报错 + NDK 版本 + ABI
- logcat 关键日志
- 真机芯片 + Android 版本

### 接入 v2/v3（NPU）

v2 是「写 `OnnxEvaluator` 替换 `Setup::initializeNNEvaluator`」，需要：
- 熟悉 KataGo `NNEvaluator` 接口（`nneval.h`）
- 熟悉 ONNX Runtime C++ API
- 熟悉 KataGo 的 NN 输入张量布局（`nninputs.h`）

建议先在沙盒/x86 上跑通 ORT 推理（不依赖 NDK），再移植到 Android。

### 提交规范

- `feat:` 新功能
- `fix:` bug 修复
- `docs:` 文档
- `test:` 测试
- `refactor:` 重构

---

## 时间线

| 日期 | 进度 |
|---|---|
| 2026-09 | 项目启动，STUB 模式跑通 |
| 2026-09 | Board 规则 + GTP + SGF + 影响力函数 |
| 2026-10-04 | v1 EIGEN 接入代码完成，待本地编译验证 |
| 下一步 | v1 本地编译验证 → v2 ONNX Runtime 推理可行性验证 |

---

## 联系

- 仓库：https://github.com/ITX0516/KataGoAndroid
- Issue：欢迎反馈编译错误、运行问题、改进建议
- 参考资源：
  - [karino2 PaooGo 开发笔记](https://karino2.github.io/RandomThoughts/%E3%83%91%E3%82%AA%E7%A2%81.html)
  - [BadukAI 官方网站](https://aki65.github.io/index.html)
  - [ONNX Runtime NNAPI EP](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)
  - [QNN Execution Provider](https://www.mintlify.com/microsoft/onnxruntime-genai/acceleration/qnn.html)
