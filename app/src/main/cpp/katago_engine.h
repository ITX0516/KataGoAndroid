#pragma once

#include &lt;string&gt;
#include &lt;vector&gt;

// ───────────────────────────────────────────────────────────────
// KataGoEngine
// C++ 侧对 JNI 层暴露的引擎封装。
//
// - stub 模式（默认）：genmove 返回一个随机未占用的点，
//   不依赖 KataGo 源码，先打通 UI→JNI→C++→UI 链路。
// - 真实模式（宏 USE_REAL_KATAGO）：把 genmove 接到 KataGo 的
//   AsyncNNEvaluator + Search。具体接入点见 katago_engine.cpp。
//
// 棋盘坐标约定：
//   color: 0=空, 1=黑, 2=白
//   x = 棋盘列号（0..boardSize-1），y = 棋盘行号（0..boardSize-1）
//   moveHistory: 平铺 [color,x,y, color,x,y, ...]，按落子顺序
//   返回值 = x*boardSize + y；-1 = pass；-2 = resign
// ───────────────────────────────────────────────────────────────
class KataGoEngine {
public:
    KataGoEngine(const std::string&amp; modelPath, const std::string&amp; configPath);
    ~KataGoEngine();

    KataGoEngine(const KataGoEngine&amp;) = delete;
    KataGoEngine&amp; operator=(const KataGoEngine&amp;) = delete;

    int genmove(int boardSize, int toMove, const int* moves, int moveCount);

private:
    bool   realMode_   = false;   // 是否启用了真 KataGo
    void*  katagoCtx_  = nullptr; // 真模式下 KataGo 上下文（NNEvaluator/Search 等）的 opaque 指针
    std::string modelPath_;
    std::string configPath_;

    int stubGenmove(int boardSize, int toMove, const int* moves, int moveCount);
    int realGenmove(int boardSize, int toMove, const int* moves, int moveCount);

    bool initReal();   // 真模式下：建 NN evaluator、加载模型
};
