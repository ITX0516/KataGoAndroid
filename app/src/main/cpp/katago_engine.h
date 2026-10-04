#pragma once

#include <string>
#include <vector>

// ───────────────────────────────────────────────────────────────
// KataGoEngine
// C++ 侧对 JNI 层暴露的引擎封装。
//
// - stub 模式（默认）：genmove 返回一个随机未占用的点，
//   不依赖 KataGo 源码，先打通 UI→JNI→C++→UI 链路。
// - 真实模式（宏 USE_REAL_KATAGO）：把 genmove 接到 KataGo 的
//   NNEvaluator + AsyncBot。EIGEN CPU 后端，karino2 已验证路径。
//
// 棋盘坐标约定：
//   color: 0=空, 1=黑, 2=白
//   x = 棋盘列号（0..boardSize-1），y = 棋盘行号（0..boardSize-1）
//   moveHistory: 平铺 [color,x,y, color,x,y, ...]，按落子顺序
//   返回值 = x*boardSize + y；-1 = pass；-2 = resign
// ───────────────────────────────────────────────────────────────

// 前置声明，避免头文件拖入 KataGo 全套
namespace KataGo { struct Context; }

class KataGoEngine {
public:
    KataGoEngine(const std::string& modelPath, const std::string& configPath);
    ~KataGoEngine();

    KataGoEngine(const KataGoEngine&) = delete;
    KataGoEngine& operator=(const KataGoEngine&) = delete;

    int genmove(int boardSize, int toMove, const int* moves, int moveCount);

private:
    bool   realMode_   = false;   // 是否启用了真 KataGo
    KataGo::Context* ctx_ = nullptr;  // 真模式下的 KataGo 上下文（nnEval/logger/cfg 等）
    std::string modelPath_;
    std::string configPath_;

    int stubGenmove(int boardSize, int toMove, const int* moves, int moveCount);
    int realGenmove(int boardSize, int toMove, const int* moves, int moveCount);

    bool initReal();   // 真模式下：建 NN evaluator、加载模型
};
