package com.example.katago

/**
 * 影响力函数（Influence Function）形势判断。
 *
 * —— 经典非 AI 方法，GNU Go 与 Sabaki @sabaki/influence 同思路 ——
 *
 * 原理：每个棋子向四周辐射影响力，随距离衰减。
 *   1. 棋子点固定 ±MAX（黑 +127 / 白 -127）
 *   2. 空点初始 0
 *   3. 迭代卷积：每次让空点 = 8 邻居均值 * DECAY
 *   4. 收敛后：正值归黑地，负值归白地，零附近单官
 *
 * 输出：[Result.map] 是 [x*size+y] 的扁平数组，[-MAX, +MAX]，
 *       供 UI 染色用（黑方越深 / 白方越深 / 单官中性）。
 *
 * 局限：不识别死子，与 [Board.chineseScore] 一致。
 *       接 KataGo 后用 `kata-analyze` 真胜率替代。
 */
class InfluenceMap(private val size: Int) {

    data class Result(
        /** 黑实空（含死子位忽略简化）。 */
        val blackTerritory: Int,
        /** 白实空。 */
        val whiteTerritory: Int,
        /** 单官数。 */
        val dame: Int,
        /** 黑领先目数（正=黑领先，不含贴目）。 */
        val scoreLead: Float,
        /** 黑胜率估算 [0,1]，由 scoreLead 经 sigmoid 映射。 */
        val winrateBlack: Float,
        /** 影响力地图：[x*size+y]，值域 [-MAX, +MAX]。 */
        val map: FloatArray
    )

    fun compute(board: Board): Result {
        val cur = FloatArray(size * size)
        val alt = FloatArray(size * size)

        // ─── 初始化 ─────────────────────────────────
        for (x in 0 until size) for (y in 0 until size) {
            val c = board.get(x, y)
            cur[x * size + y] = when (c) {
                Board.BLACK ->  MAX
                Board.WHITE -> -MAX
                else        ->  0f
            }
        }

        // ─── 迭代卷积 ──────────────────────────────
        // 每次：空点 = 8 邻居均值 * DECAY；棋子点保持原值
        for (it in 0 until ITERATIONS) {
            for (x in 0 until size) for (y in 0 until size) {
                val idx = x * size + y
                val c = board.get(x, y)
                if (c != Board.EMPTY) {
                    alt[idx] = cur[idx]   // 棋子点不动
                    continue
                }
                var sum = 0f; var cnt = 0
                for (d in DIRS8) {
                    val nx = x + d[0]; val ny = y + d[1]
                    if (nx in 0 until size && ny in 0 until size) {
                        sum += cur[nx * size + ny]
                        cnt++
                    }
                }
                // 边角邻居少 → cnt 可能 < 8，仍按实际邻居均值
                alt[idx] = if (cnt > 0) (sum / cnt) * DECAY else 0f
            }
            // 双缓冲交换
            val tmp = cur; cur = alt; alt = tmp
        }
        // 注意：上面的 swap 后 cur 是最后一次写入的 alt，即结果

        // ─── 统计地盘 ──────────────────────────────
        var blackTerr = 0; var whiteTerr = 0; var dame = 0
        var blackStones = 0; var whiteStones = 0
        for (x in 0 until size) for (y in 0 until size) {
            val idx = x * size + y
            val c = board.get(x, y)
            when (c) {
                Board.BLACK -> blackStones++
                Board.WHITE -> whiteStones++
                else -> {
                    val v = cur[idx]
                    when {
                        v >  THRESHOLD -> blackTerr++
                        v < -THRESHOLD -> whiteTerr++
                        else           -> dame++
                    }
                }
            }
        }
        val lead = (blackStones + blackTerr) - (whiteStones + whiteTerr)
        // 胜率：sigmoid(lead / 12) —— 12 是经验缩放，lead=12 → ~73%
        val wr = (1.0 / (1.0 + Math.exp(-lead / 12.0))).toFloat()

        return Result(
            blackTerritory = blackTerr,
            whiteTerritory = whiteTerr,
            dame            = dame,
            scoreLead       = lead.toFloat(),
            winrateBlack    = wr,
            map             = cur
        )
    }

    companion object {
        /** 棋子点的固定极值。 */
        private const val MAX = 127f
        /** 卷积衰减系数。0.92 ≈ Sabaki 默认。 */
        private const val DECAY = 0.92f
        /** 迭代次数。12 次能让影响力传遍 19 路。 */
        private const val ITERATIONS = 12
        /** 单官阈值。|v| < 5 视为单官。 */
        private const val THRESHOLD = 5f

        private val DIRS8 = arrayOf(
            intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
            intArrayOf(-1,  0),                  intArrayOf(1,  0),
            intArrayOf(-1,  1), intArrayOf(0,  1), intArrayOf(1,  1)
        )
    }
}
