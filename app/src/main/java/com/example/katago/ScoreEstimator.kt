package com.example.katago

/**
 * 形势判断接口：根据当前局面给出胜率 + 主要变化（PV）。
 *
 * 真 KataGo 接入时由 native 实现（`kata-analyze` 返回的 JSON 解析后填入）。
 * 在 stub 模式下 GtpEngine 的 `estimator` 字段为 null，`kata-analyze` 等命令会返回
 * "?... not available in stub mode" 错误，避免给用户假数据。
 *
 * 设计参考 KataGo `kata-analyze` 协议（JSON 模式）：
 *   {
 *     "move": "G14",            ← 引擎首选着
 *     "winrate": 0.523,         ← 黑胜率 [0,1]
 *     "scoreLead": 1.4,        ← 黑领先目数
 *     "visits": 8000,
 *     "pv": ["G14", "Q5", ...], ← 主要变化
 *     "moveInfos": [ {move, winrate, visits, pv, ...}, ... ]
 *   }
 */
interface ScoreEstimator {

    data class Result(
        /** 黑胜率 [0.0, 1.0]。 */
        val winrateBlack: Float,
        /** 黑领先目数（正=黑领先）。 */
        val scoreLead: Float = 0f,
        /** 总访问数（衡量搜索深度）。 */
        val moveCount: Int = 0,
        /** 主要变化，每项 [x, y]。 */
        val pv: List<IntArray> = emptyList()
    )

    /**
     * 分析当前局面。
     * @param color 视角（黑或白）
     * @return 分析结果；返回前应完成一轮搜索
     */
    fun analyze(board: Board, color: Int): Result
}

/**
 * Stub 实现：仅作占位与开发期占位（不返回真实胜率，避免误导）。
 * GtpEngine 默认不挂这个，而是直接 `estimator = null` 让 `kata-analyze` 报错。
 */
class StubEstimator : ScoreEstimator {
    override fun analyze(board: Board, color: Int): ScoreEstimator.Result {
        // 给个中性值用于联调；UI 不应展示给用户
        return ScoreEstimator.Result(
            winrateBlack = 0.5f,
            scoreLead = 0f,
            moveCount = 0,
            pv = emptyList()
        )
    }
}

/**
 * 影响力函数实现的形势判断器（GNU Go / Sabaki 风格）。
 *
 * 优势：纯静态启发式，无需 KataGo 也能给出**合理**的胜率与目数差。
 * 缺点：不识别死子、双活，对中盘复杂局面误差较大。
 *
 * Stub 模式下用这个就有"假但合理"的形势判断，
 * 接 KataGo 后换成 `KataGoEstimator` 拿真胜率。
 */
class InfluenceEstimator : ScoreEstimator {
    override fun analyze(board: Board, color: Int): ScoreEstimator.Result {
        val r = InfluenceMap(board.size).compute(board)
        return ScoreEstimator.Result(
            winrateBlack = r.winrateBlack,
            scoreLead    = r.scoreLead,
            moveCount    = 0,
            pv           = emptyList()
        )
    }
}

/*
 * ─── 接真 KataGo 的实现模板（参考） ───────────────────────────
 *
 * class KataGoEstimator(private val engine: KataGoEngine) : ScoreEstimator {
 *     override fun analyze(board: Board, color: Int): ScoreEstimator.Result {
 *         // 1. 把 history 平铺给 native
 *         val flat = board.historySnapshot().flatMap { it.toList() }.toIntArray()
 *         // 2. 调 native kata-analyze，等回调（建议设 0.5s 超时）
 *         val json = engine.analyzeJson(board.size, color, flat, maxNodes = 8000)
 *         // 3. 解析 JSON 取 winrate/scoreLead/pv
 *         val winrate = parseWinrate(json)
 *         val pv = parsePV(json, board.size)
 *         return ScoreEstimator.Result(winrateBlack = winrate, pv = pv, ...)
 *     }
 * }
 *
 * 在 MainActivity 接入：
 *   val engine = KataGoEngine(modelPath, configPath)
 *   gtp.gen = GtpEngine.JniGen(engine)
 *   gtp.estimator = KataGoEstimator(engine)
 */
