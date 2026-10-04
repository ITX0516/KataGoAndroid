#include "katago_engine.h"

#include &lt;android/log.h&gt;
#include &lt;cstdlib&gt;
#include &lt;cstring&gt;
#include &lt;ctime&gt;
#include &lt;unordered_set&gt;

#include "opencl_loader.h"

#define TAG "KataGoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

KataGoEngine::KataGoEngine(const std::string&amp; modelPath, const std::string&amp; configPath)
    : modelPath_(modelPath), configPath_(configPath) {
    std::srand(static_cast&lt;unsigned&gt;(std::time(nullptr)));

#if defined(USE_REAL_KATAGO)
    realMode_ = initReal();
    LOGI("real mode=%d (OpenCL=%d)", realMode_ ? 1 : 0,
         OpenCLLoader::instance().available() ? 1 : 0);
#else
    LOGI("stub mode (no KataGo source linked). genmove = random legal");
#endif
}

KataGoEngine::~KataGoEngine() {
    if (katagoCtx_) {
        // 真模式下释放 KataGo 资源（NN evaluator / search）。
        // 具体析构代码见接入 KataGo 时补全。
        katagoCtx_ = nullptr;
    }
}

int KataGoEngine::genmove(int boardSize, int toMove, const int* moves, int moveCount) {
    if (realMode_) {
        return realGenmove(boardSize, toMove, moves, moveCount);
    }
    return stubGenmove(boardSize, toMove, moves, moveCount);
}

// ─── stub：随机合法点 ──────────────────────────────────────────
int KataGoEngine::stubGenmove(int boardSize, int toMove, const int* moves, int moveCount) {
    (void)toMove;
    std::unordered_set&lt;int&gt; occupied;
    occupied.reserve(moveCount);
    for (int i = 0; i &lt; moveCount; ++i) {
        const int c = moves[i * 3 + 0];
        const int x = moves[i * 3 + 1];
        const int y = moves[i * 3 + 2];
        if (c != 0 &amp;&amp; x &gt;= 0 &amp;&amp; x &lt; boardSize &amp;&amp; y &gt;= 0 &amp;&amp; y &lt; boardSize) {
            occupied.insert(x * boardSize + y);
        }
    }
    const int total = boardSize * boardSize;
    for (int tries = 0; tries &lt; 200; ++tries) {
        int idx = std::rand() % total;
        if (occupied.find(idx) == occupied.end()) {
            return idx;  // x*boardSize + y
        }
    }
    // 几乎全占满 → pass
    return -1;
}

// ─── 真实模式接入点（karino2 fork / 上游 KataGo）──────────────
bool KataGoEngine::initReal() {
#ifndef USE_REAL_KATAGO
    return false;
#else
    // OpenCL 必须先 dlopen 成功，否则不进真实模式
    if (!OpenCLLoader::instance().init()) {
        LOGE("OpenCL 不可用，回退 stub");
        return false;
    }

    // 1) 读 KataGo 配置（modelPath/configPath 指向 assets 解压后的路径）
    //    ConfigParser cfg(configPath_);
    //
    // 2) 建 OpenCL 后端 NN evaluator
    //    auto nnEval = std::make_shared&lt;NNGo8BackendEvaluator&gt;(
    //        modelPath_, cfg, ...);   // 实际类名按 KataGo 版本
    //
    // 3) 持有 Search 上下文
    //    auto* ctx = new SearchContext(cfg, nnEval, logger);
    //    katagoCtx_ = ctx;

    LOGE("initReal(): KataGo 真实接入待补，请按 karino2 fork 的 cpp/main() 流程填入");
    return false;  // 暂未真正接入，先回退 stub
#endif
}

int KataGoEngine::realGenmove(int boardSize, int toMove, const int* moves, int moveCount) {
    // 流程（伪代码）：
    // 1) 把 moves 数组重建为 KataGo Board：board.playMove(...)
    // 2) search.runSinglePlayout(board, ...) 做 MCTS
    // 3) auto loc = search.getChosenMoveLoc()
    // 4) 转回 x*boardSize+y（注意 KataGo 用 0..N*N-1 一维 loc）
    //    int x = Location::getX(loc, boardSize);
    //    int y = Location::getY(loc, boardSize);
    //    return x*boardSize + y;
    // 兜底：真实模式未接入时走 stub
    return stubGenmove(boardSize, toMove, moves, moveCount);
}
