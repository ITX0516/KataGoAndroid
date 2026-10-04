#include "katago_engine.h"

#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <streambuf>
#include <sstream>
#include <unordered_set>
#include <memory>

#if defined(USE_REAL_KATAGO)
// ─── KataGo 头文件 ─────────────────────────────────────────────
#include "core/global.h"
#include "core/config_parser.h"
#include "core/logger.h"
#include "core/rand.h"
#include "game/board.h"
#include "game/boardhistory.h"
#include "game/rules.h"
#include "search/asyncbot.h"
#include "search/searchparams.h"
#include "search/timecontrols.h"
#include "program/setup.h"
#endif

#define TAG "KataGoEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(USE_REAL_KATAGO)
// ─── LogcatStreamBuf：把 KataGo Logger 输出重定向到 Android logcat ──
// KataGo 的 Logger::addOStream(std::ostream&) 接受任意 ostream，
// 用一个 streambuf 把每次 write() 转成 __android_log_print。
class LogcatStreamBuf : public std::streambuf {
public:
    LogcatStreamBuf() { /* 故意无缓冲，overflow 直接打印 */ }
protected:
    int overflow(int c) override {
        if(c == EOF) return !EOF;
        char ch = static_cast<char>(c);
        if(ch == '\n') {
            // 一行结束，刷到 logcat
            if(!line_.empty()) {
                __android_log_print(ANDROID_LOG_INFO, "KataGo", "%s", line_.c_str());
                line_.clear();
            }
        } else {
            line_ += ch;
        }
        return c;
    }
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        for(std::streamsize i = 0; i < n; ++i) {
            char ch = s[i];
            if(ch == '\n') {
                if(!line_.empty()) {
                    __android_log_print(ANDROID_LOG_INFO, "KataGo", "%s", line_.c_str());
                    line_.clear();
                }
            } else {
                line_ += ch;
            }
        }
        return n;
    }
private:
    std::string line_;
};

// ─── KataGo 上下文：持有 NN evaluator、bot、配置等 ─────────────
namespace KataGo {
struct Context {
    std::unique_ptr<ConfigParser>      cfg;
    std::unique_ptr<Logger>            logger;
    std::unique_ptr<Rand>              seedRand;
    std::unique_ptr<std::ostream>      logcatStream;
    std::unique_ptr<LogcatStreamBuf>   logcatBuf;

    NNEvaluator*  nnEval       = nullptr;  // Setup::initializeNNEvaluator 返回的是裸指针，需手动 delete
    AsyncBot*     bot          = nullptr;  // 每次 genmove 重建（廉价），或棋盘大小变化时重建
    SearchParams  params;
    Rules         rules;
    int           curBoardX   = 0;
    int           curBoardY   = 0;
    int           expectedConcurrentEvals = 1;

    ~Context() {
        if(bot)    { delete bot; bot = nullptr; }
        if(nnEval) { delete nnEval; nnEval = nullptr; }
    }
};
}
#endif  // USE_REAL_KATAGO

// ═══════════════════════════════════════════════════════════════
// 构造 / 析构
// ═══════════════════════════════════════════════════════════════
KataGoEngine::KataGoEngine(const std::string& modelPath, const std::string& configPath)
    : modelPath_(modelPath), configPath_(configPath) {
    std::srand(static_cast<unsigned>(std::time(nullptr)));

#if defined(USE_REAL_KATAGO)
    realMode_ = initReal();
    if(realMode_) {
        LOGI("real mode ready: model=%s, nnEval=0x%p",
             modelPath_.c_str(), (void*)ctx_->nnEval);
    } else {
        LOGE("initReal() failed, fallback to stub mode");
    }
#else
    LOGI("stub mode (no USE_REAL_KATAGO defined). genmove = random legal");
#endif
}

KataGoEngine::~KataGoEngine() {
#if defined(USE_REAL_KATAGO)
    delete ctx_;
    ctx_ = nullptr;
#endif
}

int KataGoEngine::genmove(int boardSize, int toMove, const int* moves, int moveCount) {
    if(realMode_) {
        return realGenmove(boardSize, toMove, moves, moveCount);
    }
    return stubGenmove(boardSize, toMove, moves, moveCount);
}

// ═══════════════════════════════════════════════════════════════
// stub：随机合法点
// ═══════════════════════════════════════════════════════════════
int KataGoEngine::stubGenmove(int boardSize, int toMove, const int* moves, int moveCount) {
    (void)toMove;
    std::unordered_set<int> occupied;
    occupied.reserve(moveCount);
    for (int i = 0; i < moveCount; ++i) {
        const int c = moves[i * 3 + 0];
        const int x = moves[i * 3 + 1];
        const int y = moves[i * 3 + 2];
        if (c != 0 && x >= 0 && x < boardSize && y >= 0 && y < boardSize) {
            occupied.insert(x * boardSize + y);
        }
    }
    const int total = boardSize * boardSize;
    for (int tries = 0; tries < 200; ++tries) {
        int idx = std::rand() % total;
        if (occupied.find(idx) == occupied.end()) {
            return idx;  // x*boardSize + y
        }
    }
    return -1;  // pass
}

// ═══════════════════════════════════════════════════════════════
// 真实模式：初始化 KataGo（参照 gtp.cpp 中 GTPEngine 构造流程）
// ═══════════════════════════════════════════════════════════════
#if defined(USE_REAL_KATAGO)
bool KataGoEngine::initReal() {
    try {
        ctx_ = new KataGo::Context();

        // 1) Logger（先创建，KataGo 内部很多日志依赖它）
        ctx_->logger = std::make_unique<Logger>(
            /*cfg=*/nullptr,
            /*logToStdoutDefault=*/false,    // Android 不需要 stdout
            /*logToStderrDefault=*/false,    // 也不需要 stderr
            /*logTimeDefault=*/true,
            /*logConfigContents=*/false
        );

        // 2) 把 Logger 输出重定向到 logcat
        ctx_->logcatBuf   = std::make_unique<LogcatStreamBuf>();
        ctx_->logcatStream = std::make_unique<std::ostream>(ctx_->logcatBuf.get());
        ctx_->logger->addOStream(*ctx_->logcatStream);

        // 3) ConfigParser 读 gtp_example.cfg
        ctx_->cfg = std::make_unique<ConfigParser>();
        ctx_->cfg->initialize(configPath_);
        ctx_->logger->write("Config loaded: " + configPath_);

        // 4) Setup::initializeSession（处理 logDir/logFile 等通用配置）
        Setup::initializeSession(*ctx_->cfg);

        // 5) Rand（用时间戳作 seed，简单可靠）
        ctx_->seedRand = std::make_unique<Rand>(
            static_cast<uint64_t>(std::time(nullptr)) ^ 0x12345ULL
        );

        // 6) SearchParams：用 forTestsV2() 作基线（GTP 用得着的合理默认），
        //    覆盖 maxVisits=10 → 单手 ~5 秒（karino2 验证过的可玩速度）
        ctx_->params = SearchParams::forTestsV2();
        ctx_->params.maxVisits  = 10;
        ctx_->params.maxPlayouts = 10;
        ctx_->params.maxTime    = 10.0;
        ctx_->params.numThreads = 1;   // EIGEN CPU 单线程足够，多线程开销大于收益

        // 7) Rules：中国规则（最常用）
        ctx_->rules = Rules::parseRules("chinese");

        // 8) NNEvaluator：用 19x19 作为 NN 输入大小（最大兼容）
        //    gtpForceMaxNNSize=true 时，nnEval 加载成 19x19，下小棋盘时 KataGo 内部 padding
        ctx_->expectedConcurrentEvals = std::max(ctx_->params.numThreads, 1);
        const bool disableFP16 = false;
        const std::string expectedSha256;
        const bool defaultRequireExactNNLen = false;   // gtpForceMaxNNSize=true 的关键
        const int nnXLen = Board::DEFAULT_LEN;  // 19
        const int nnYLen = Board::DEFAULT_LEN;

        ctx_->nnEval = Setup::initializeNNEvaluator(
            /*nnModelNames=*/modelPath_,
            /*nnModelFiles=*/modelPath_,
            expectedSha256,
            *ctx_->cfg,
            *ctx_->logger,
            *ctx_->seedRand,
            ctx_->expectedConcurrentEvals,
            nnXLen, nnYLen,
            Setup::MaxBatchSizeRequest::fromConcurrency(),
            defaultRequireExactNNLen,
            disableFP16,
            Setup::SETUP_FOR_GTP
        );
        if(ctx_->nnEval == nullptr) {
            LOGE("Setup::initializeNNEvaluator returned null");
            return false;
        }
        ctx_->logger->write("NN evaluator loaded, nnXLen=" +
                            std::to_string(ctx_->nnEval->getNNXLen()) +
                            " nnYLen=" + std::to_string(ctx_->nnEval->getNNYLen()));

        // 9) AsyncBot 暂不创建，等 realGenmove 时按 boardSize 重建
        ctx_->curBoardX = 0;
        ctx_->curBoardY = 0;

        LOGI("initReal done: rules=%s, maxVisits=%lld, numThreads=%d",
             "chinese",
             (long long)ctx_->params.maxVisits,
             ctx_->params.numThreads);
        return true;
    } catch(const std::exception& e) {
        LOGE("initReal exception: %s", e.what());
        delete ctx_;
        ctx_ = nullptr;
        return false;
    } catch(...) {
        LOGE("initReal unknown exception");
        delete ctx_;
        ctx_ = nullptr;
        return false;
    }
}

// ═══════════════════════════════════════════════════════════════
// 真实模式：genmove
//   1. 如果 boardSize 变了（或 bot 还没建），重建 AsyncBot
//   2. 从 moves 数组重建 Board + BoardHistory
//   3. bot->setPosition()
//   4. bot->genMoveSynchronous()
//   5. Loc → x*boardSize + y 转换
// ═══════════════════════════════════════════════════════════════
int KataGoEngine::realGenmove(int boardSize, int toMove, const int* moves, int moveCount) {
    if(ctx_ == nullptr || ctx_->nnEval == nullptr) {
        LOGE("realGenmove: ctx_/nnEval null, fallback to stub");
        return stubGenmove(boardSize, toMove, moves, moveCount);
    }

    try {
        // 1) 棋盘大小变化或 bot 还没创建 → 重建 bot
        if(ctx_->bot == nullptr || ctx_->curBoardX != boardSize) {
            if(ctx_->bot) {
                delete ctx_->bot;
                ctx_->bot = nullptr;
            }
            ctx_->bot = new AsyncBot(
                ctx_->params,
                ctx_->nnEval,
                /*humanEval=*/nullptr,
                ctx_->logger.get(),
                /*randSeed=*/"katago-android"
            );
            ctx_->curBoardX = boardSize;
            ctx_->curBoardY = boardSize;
        }

        // 2) 重建 Board + BoardHistory
        Board board(boardSize, boardSize);
        Player curPla = (toMove == 1) ? P_BLACK : P_WHITE;
        BoardHistory hist(board, curPla, ctx_->rules, /*encorePhase=*/0, BoardHistoryModes());

        // 3) 把 moves 数组里的所有着走一遍，更新 board + hist
        //    用 makeBoardMoveTolerant：能处理合法着 + pass，遇到非法着返回 false
        for(int i = 0; i < moveCount; ++i) {
            const int color = moves[i * 3 + 0];
            const int x     = moves[i * 3 + 1];
            const int y     = moves[i * 3 + 2];
            Player pla = (color == 1) ? P_BLACK : P_WHITE;

            Loc loc;
            if(x < 0 || y < 0) {
                // pass（约定 x/y 是负数表示 pass）
                loc = Board::PASS_LOC;
            } else {
                loc = Location::getLoc(x, y, boardSize);
            }
            bool ok = hist.makeBoardMoveTolerant(board, loc, pla, /*preventEncore=*/false);
            if(!ok) {
                // 非法着（理论上不应发生，因为 UI 层已经校验）
                LOGW("realGenmove: illegal move at (%d,%d), skip", x, y);
                continue;
            }
        }

        // 4) bot->setPosition()
        ctx_->bot->setPosition(curPla, board, hist);

        // 5) genMoveSynchronous
        TimeControls tc;   // 默认无时间限制（靠 maxVisits/maxPlayouts 控速）
        Loc chosen = ctx_->bot->genMoveSynchronous(curPla, tc);

        // 6) Loc → x*boardSize + y
        //    genMoveSynchronous 可能返回 PASS_LOC（pass）或 NULL_LOC（无合法着）
        if(chosen == Board::PASS_LOC || chosen == Board::NULL_LOC) {
            LOGI("genmove: pass or no move");
            return -1;
        }
        int x = Location::getX(chosen, boardSize);
        int y = Location::getY(chosen, boardSize);
        LOGI("genmove: chosen=(%d,%d) loc=%d", x, y, (int)chosen);
        return x * boardSize + y;
    } catch(const std::exception& e) {
        LOGE("realGenmove exception: %s, fallback stub", e.what());
        return stubGenmove(boardSize, toMove, moves, moveCount);
    } catch(...) {
        LOGE("realGenmove unknown exception, fallback stub");
        return stubGenmove(boardSize, toMove, moves, moveCount);
    }
}

#else  // !USE_REAL_KATAGO
bool KataGoEngine::initReal() { return false; }
int  KataGoEngine::realGenmove(int, int, const int*, int) { return -1; }
#endif
