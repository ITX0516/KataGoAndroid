# 本地构建指南（v1：EIGEN CPU 后端）

本文档教你在本地 Android Studio 里把 KataGo 真实接入跑起来。完成后你的手机能下出真实 KataGo 的棋（约 5 秒/手）。

## 前置准备

### 1. 安装 Android Studio + NDK + CMake

- **Android Studio**：Hedgehog (2023.1.1) 或更新
- **NDK**：r25c 或 r26（SDK Manager → SDK Tools → NDK Side by side）
- **CMake**：3.22.1（SDK Manager → SDK Tools → CMake）
- **真机**：arm64-v8a，Android 8.0+，开发者选项 + USB 调试

### 2. 克隆 KataGo 源码

```bash
git clone https://github.com/lightvector/KataGo.git ~/KataGo
cd ~/KataGo
git checkout v1.18.2  # 或最新稳定 tag
```

无需 fork karino2 的 android 分支——本工程的 CMakeLists 已经包含了 karino2 验证过的 3 处改动（注释掉 `find_package(Eigen3)`、加 Eigen 头路径、定义 `BYTE_ORDER` 等宏）。

### 3. 下载 Eigen 头文件

KataGo 的 EIGEN 后端只需要 Eigen 的头文件，不需要编译。

```bash
cd ~/KataGo/cpp/external
# 已经包含 eigen 子目录的话直接用，否则下载：
# wget https://gitlab.com/libeigen/eigen/-/archive/3.4.0/eigen-3.4.0.tar.gz
# tar xzf eigen-3.4.0.tar.gz
```

记下 Eigen 头文件的根路径，例如 `/home/you/KataGo/cpp/external/eigen-3.4.0`（该目录下应能直接看到 `Eigen/` 子目录）。

### 4. 准备模型文件

从 https://katagotraining.org/networks/ 下载一个 10-block 网络（CPU 友好）：

```
b10c384  ← 推荐，41MB，CPU 单手 ~5 秒
b18c384  ← 中等，87MB
b20c256  ← 大网，119MB
```

下载后改名为 `b10c384.bin` 放到：

```
app/src/main/assets/models/b10c384.bin
```

### 5. 准备 GTP 配置文件

复制 KataGo 自带的配置到 assets：

```bash
cp ~/KataGo/cpp/configs/gtp_example.cfg \
   /path/to/KataGoAndroid/app/src/main/assets/gtp.cfg
```

修改 `gtp.cfg`，把以下几项改成：

```ini
# 模型文件名（运行时从 assets 复制到 filesDir 后，路径变成 /data/data/.../files/b10c384.bin）
# 不要在 cfg 里写绝对路径，让 App 通过 ConfigParser 覆盖
nnModelFile = b10c384.bin

# 日志（写沙盒路径，App 启动时可用，不写也行）
logDir = .
logAllGmTime = false
logSearchInfo = false

# 搜索参数（CPU 友好，会被代码里的 maxVisits=10 覆盖，这里只是兜底）
maxVisits = 10
numSearchThreads = 1
```

注：代码里 [katago_engine.cpp](app/src/main/cpp/katago_engine.cpp#L193-L201) 已强制设 `maxVisits=10`、`numThreads=1`，所以 cfg 里这两项可写可不写。

## 启用 EIGEN 真实模式

### 1. 改 app/build.gradle.kts

打开 [app/build.gradle.kts](app/build.gradle.kts)，找到 `externalNativeBuild { cmake { ... } }` 块，解开注释并改路径：

```kotlin
externalNativeBuild {
    cmake {
        cppFlags += "-std=c++17"
        arguments += "-DUSE_REAL_KATAGO=ON"
        arguments += "-DKATAGO_SRC=/home/you/KataGo"            // 改成你的 KataGo 克隆路径
        arguments += "-DEIGEN_SRC=/home/you/KataGo/cpp/external/eigen-3.4.0"
    }
}
```

⚠️ 路径不要含中文、空格、`~`（CMake 不展开波浪号）。用绝对路径。

### 2. 同步 + 编译

1. Android Studio 打开 `KataGoAndroid/`
2. 等 Gradle Sync 完成（首次会下 AGP + Kotlin + AndroidX，约 1-2 分钟）
3. Run → 选真机
4. 首次编译需要 5-10 分钟（KataGo 大约 200 个 .cpp 文件）

### 3. 看 logcat 验证

接好真机后开一个终端：

```bash
adb logcat -s KataGoJNI:I KataGoEngine:I KataGo:I
```

启动 App，正常应看到：

```
KataGoJNI  I  engine constructed, handle=0xXXXX
KataGo     I  Config loaded: /data/data/.../files/gtp.cfg
KataGo     I  Loaded neural net with nnXLen 19 nnYLen 19
KataGoEngine I  initReal done: rules=chinese, maxVisits=10, numThreads=1
KataGoEngine I  real mode ready: model=/data/data/.../files/b10c384.bin, nnEval=0xXXXX
KataGoEngine I  genmove: chosen=(4,4) loc=...
```

如果看到 `initReal exception: ...`，按提示排查（通常是路径错、模型坏、cfg 缺字段）。

### 4. 资源加载顺序

App 启动后 [MainActivity.kt](app/src/main/java/com/example/katago/MainActivity.kt) 会：

1. 把 `assets/models/b10c384.bin` 复制到 `filesDir/b10c384.bin`
2. 把 `assets/gtp.cfg` 复制到 `filesDir/gtp.cfg`
3. 把这两个 `filesDir` 路径传给 `KataGoEngine(modelPath, configPath)`
4. JNI 调 `initReal()` → 加载 NN → 准备好 genmove

⚠️ **MainActivity 当前不会自动做第 1-3 步**——这是 StubGen 模式下不需要的。启用 EIGEN 后你需要在 MainActivity.onCreate 里加：

```kotlin
private fun copyAssetsToFiles(): Pair<String, String> {
    val modelFile = "b10c384.bin"
    val cfgFile   = "gtp.cfg"
    val modelPath = File(filesDir, modelFile).absolutePath
    val cfgPath   = File(filesDir, cfgFile).absolutePath
    listOf(modelFile to modelPath, cfgFile to cfgPath).forEach { (asset, dest) ->
        if (!File(dest).exists()) {
            assets.open(asset).use { input ->
                File(dest).outputStream().use { input.copyTo(it) }
            }
        }
    }
    return modelPath to cfgPath
}
```

然后在 `onCreate` 里：

```kotlin
val (modelPath, cfgPath) = copyAssetsToFiles()
val engine = KataGoEngine(modelPath, cfgPath)
gtp.gen = JniGen(engine)  // 把 stub 换成真 KataGo
```

## 常见问题

### Q1：编译报 `Eigen3 was not found`

CMake 没找到 Eigen。检查 `EIGEN_SRC` 路径是否指向含 `Eigen/` 子目录的根。CMakeLists 里没动上游的 `find_package(Eigen3)`——我们的 CMakeLists 是独立编译 KataGo 源码（file(GLOB)），不调上游的 find_package。如果还报错，可能是上游某个 `cpp/CMakeLists.txt` 被意外 include 了，确保 `KATAGO_SRC` 指向仓库根（含 `cpp/`），而不是 `cpp/` 自身。

### Q2：链接报 `undefined reference to libzip_*`

代码用了 `NO_LIBZIP` 宏，但可能 `dataio/files.cpp` 仍引用 libzip。检查 CMakeLists 里 `target_compile_definitions` 是否有 `NO_LIBZIP`。如有需要把 `dataio/files.cpp` 排除：

```cmake
file(GLOB KATAGO_DATAIO ${KATAGO_SRC}/cpp/dataio/*.cpp)
list(REMOVE_ITEM KATAGO_DATAIO ${KATAGO_SRC}/cpp/dataio/files.cpp)
```

### Q3：编译报 `BYTE_ORDER already defined`

NDK 部分 API 已定义 BYTE_ORDER。把 CMakeLists 里 `BYTE_ORDER=1234` / `LITTLE_ENDIAN=1234` / `BIG_ENDIAN=4321` 改成条件定义：

```cmake
target_compile_definitions(katago PRIVATE NOMINMAX)
# BYTE_ORDER 由 NDK 提供，仅在缺失时定义
```

karino2 的笔记提到 NDK 没有 BYTE_ORDER，但新版本 NDK 可能已加。先删掉这三个定义试试，编译报错再加回。

### Q4：logcat 显示 `OpenCL 不可用，回退 stub`

这是老代码 `OpenCLLoader::instance().init()` 检查，已在 [katago_engine.cpp](app/src/main/cpp/katago_engine.cpp) 里移除。如果还看到，说明编译的是旧版 .cpp，clean 一下：

```bash
cd KataGoAndroid
./gradlew clean
```

### Q5：genmove 卡 30 秒以上

模型太大或 maxVisits 没生效。检查 `katago_engine.cpp:194` 是否硬编码 `maxVisits=10`。换更小的网络（b10c384）。

### Q6：怎么从 EIGEN 升级到 NPU

参考调研报告里的阶段 2/3 路径。EIGEN 跑通后，`Board.kt`/`GtpEngine.kt`/`MainActivity.kt` 都不用改，只需替换 [katago_engine.cpp](app/src/main/cpp/katago_engine.cpp) 的 `initReal()` 实现：从 `Setup::initializeNNEvaluator`（EIGEN）换成 ONNX Runtime + NNAPI EP。

## 文件清单（启用 EIGEN 后必查）

| 文件 | 作用 | 改动 |
|---|---|---|
| [app/build.gradle.kts](app/build.gradle.kts) | 启用 USE_REAL_KATAGO + 路径 | 解开 3 行注释 |
| [app/src/main/cpp/CMakeLists.txt](app/src/main/cpp/CMakeLists.txt) | file(GLOB) KataGo 源码 + EIGEN 路径 + BYTE_ORDER | 已写好 |
| [app/src/main/cpp/katago_engine.h](app/src/main/cpp/katago_engine.h) | KataGo::Context 前置声明 | 已写好 |
| [app/src/main/cpp/katago_engine.cpp](app/src/main/cpp/katago_engine.cpp) | initReal() + realGenmove() | 已写好 |
| [app/src/main/java/com/example/katago/MainActivity.kt](app/src/main/java/com/example/katago/MainActivity.kt) | copyAssetsToFiles() + JniGen | **需手动加** |
| `app/src/main/assets/models/b10c384.bin` | NN 模型 | **需下载** |
| `app/src/main/assets/gtp.cfg` | GTP 配置 | **需从 KataGo 复制** |

## 进度跟踪

- [x] **阶段 1a**：STUB 模式跑通（UI → JNI → C++ → UI 链路）
- [x] **阶段 1b**：Board 规则 + GTP + SGF + 影响力函数
- [x] **阶段 1c**：EIGEN 真实模式接入代码（本文档涵盖）
- [ ] **阶段 1d**：本地编译跑通 → 真机下出 KataGo 第一手棋（**等你本地做完**）
- [ ] **阶段 2**：ONNX Runtime + NNAPI EP（阿Q 围棋级别速度）
