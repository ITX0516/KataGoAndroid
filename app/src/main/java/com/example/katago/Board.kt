package com.example.katago

/**
 * 纯围棋规则棋盘（无 UI 依赖）。
 *
 * 内部坐标约定（与 GTP 一致）：
 *   x = 列号 0..size-1（左→右），对应 GTP 列字母 A B C ...（跳过 I）
 *   y = 行号 0..size-1（下→上），y=0 是最底行，对应 GTP 行号 1
 *   即 A1 = (0,0)，在棋盘左下角
 *
 * 颜色：0 空 / 1 黑 / 2 白
 *
 * 实现的规则：
 *   1. 落子点必须为空
 *   2. 落子后先把对方无气的连通块提走
 *   3. 提完之后若己方仍无气 → 自杀禁着
 *   4. 提完之后局面若与历史任何局面重复 → 超级劫禁着
 *
 * 不实现：数目/计分（GTP final_score 留给后续引擎）。
 */
class Board(val size: Int = 9) {

    private val grid = Array(size) { IntArray(size) }    // 0 空 / 1 黑 / 2 白
    private val history = ArrayList<IntArray>()           // [color, x, y]；x==-1=pass
    private val pastPositions = ArrayList<Long>()         // 历史局面哈希（超级劫）

    var blackCaptured = 0; private set                    // 黑方提走的白子数
    var whiteCaptured = 0; private set                    // 白方提走的黑子数

    enum class Outcome { OK, OCCUPIED, OUT_OF_RANGE, SUICIDE, SUPERKO }

    /** 落子；返回 Outcome。OK 之外一律不修改棋盘状态。 */
    fun play(color: Int, x: Int, y: Int): Outcome {
        if (x !in 0 until size || y !in 0 until size) return Outcome.OUT_OF_RANGE
        if (grid[x][y] != EMPTY) return Outcome.OCCUPIED

        // 在副本上模拟一次完整过程，避免污染真棋盘
        val sim = copyGrid()
        sim[x][y] = color
        val opp = 3 - color
        var captured = 0
        for ((dx, dy) in DIRS) {
            val nx = x + dx; val ny = y + dy
            if (inBound(nx, ny) && sim[nx][ny] == opp) {
                val grp = groupOn(sim, nx, ny)
                if (libertiesOn(sim, grp) == 0) {
                    for (p in grp) sim[p[0]][p[1]] = EMPTY
                    captured += grp.size
                }
            }
        }
        val mine = groupOn(sim, x, y)
        if (libertiesOn(sim, mine) == 0) return Outcome.SUICIDE

        val h = hashGrid(sim)
        if (pastPositions.contains(h)) return Outcome.SUPERKO

        // 提交
        for (i in 0 until size) for (j in 0 until size) grid[i][j] = sim[i][j]
        history.add(intArrayOf(color, x, y))
        pastPositions.add(h)
        if (color == BLACK) blackCaptured += captured else whiteCaptured += captured
        return Outcome.OK
    }

    /** pass：不改变棋盘，但记入历史与回合。返回 [GameOver] 是否触发。 */
    fun pass(color: Int): GameOver {
        history.add(intArrayOf(color, -1, -1))
        return if (isTwoPasses()) GameOver.YES else GameOver.NO
    }

    /** 当前应执子方：空棋盘黑先；非空且上一手非 pass 则换色；上一手 pass 则同色继续。 */
    fun toMove(): Int {
        val last = history.lastOrNull() ?: return BLACK
        return if (last[1] == -1) last[0] else 3 - last[0]
    }

    /** 是否双方连续 pass → 终局。 */
    fun isTwoPasses(): Boolean {
        if (history.size < 2) return false
        val last  = history[history.size - 1]
        val prev  = history[history.size - 2]
        return last[1] == -1 && prev[1] == -1
    }

    enum class GameOver { NO, YES }

    /**
     * 中国数子计分（终局判定用，简化版）。
     *
     * 规则：
     *   - 每个空点：若仅与黑子相邻 → 黑实空；仅与白子 → 白实空；既黑又白 → 单官不数
     *   - 黑分 = 黑子数 + 黑实空
     *   - 白分 = 白子数 + 白实空
     *   - 不贴目：返回正数表示黑胜，负数表示白胜
     *
     * 局限：不识别死子（双活等需手数判断），实测够用对 stub。
     * 真 KataGo 接入后用 GTP `final_score` 替代。
     */
    fun chineseScore(): Float {
        var blackStones = 0; var whiteStones = 0
        var blackTerr = 0;  var whiteTerr = 0; var dame = 0
        val visited = Array(size) { BooleanArray(size) }

        for (x in 0 until size) for (y in 0 until size) {
            when (grid[x][y]) {
                BLACK -> blackStones++
                WHITE -> whiteStones++
                EMPTY -> {
                    if (visited[x][y]) continue
                    val region = floodEmpty(x, y, visited)
                    var touchBlack = false; var touchWhite = false
                    for (p in region) for ((dx, dy) in DIRS) {
                        val nx = p[0] + dx; val ny = p[1] + dy
                        if (!inBound(nx, ny)) continue
                        when (grid[nx][ny]) {
                            BLACK -> touchBlack = true
                            WHITE -> touchWhite = true
                        }
                    }
                    when {
                        touchBlack && !touchWhite -> blackTerr += region.size
                        touchWhite && !touchBlack -> whiteTerr += region.size
                        else -> dame += region.size   // 单官/双气
                    }
                }
            }
        }
        _lastBlackScore = (blackStones + blackTerr).toFloat()
        _lastWhiteScore = (whiteStones + whiteTerr).toFloat()
        _lastDame = dame
        return _lastBlackScore - _lastWhiteScore
    }

    /** 中国数子最后一次的结果（黑分数）。 */
    var lastBlackScore: Float = 0f; private set
    /** 中国数子最后一次的结果（白分数）。 */
    var lastWhiteScore: Float = 0f; private set
    /** 最后一次数子的单官数（debug 用）。 */
    var lastDame: Int = 0; private set
    private var _lastBlackScore: Float = 0f
    private var _lastWhiteScore: Float = 0f
    private var _lastDame: Int = 0

    private fun floodEmpty(sx: Int, sy: Int, visited: Array<BooleanArray>): List<IntArray> {
        val out = ArrayList<IntArray>()
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(sx, sy))
        visited[sx][sy] = true
        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.removeLast()
            out.add(intArrayOf(cx, cy))
            for ((dx, dy) in DIRS) {
                val nx = cx + dx; val ny = cy + dy
                if (inBound(nx, ny) && !visited[nx][ny] && grid[nx][ny] == EMPTY) {
                    visited[nx][ny] = true
                    stack.addLast(intArrayOf(nx, ny))
                }
            }
        }
        return out
    }

    fun get(x: Int, y: Int): Int = grid[x][y]

    fun lastMove(): IntArray? = history.lastOrNull()?.copyOf()

    fun moveCount(): Int = history.size

    /** 历史快照：每项 [color, x, y]，x==-1 表示 pass。供 SGF 导出使用。 */
    fun historySnapshot(): List<IntArray> = history.map { it.copyOf() }

    fun clear() {
        for (x in 0 until size) for (y in 0 until size) grid[x][y] = EMPTY
        history.clear(); pastPositions.clear()
        blackCaptured = 0; whiteCaptured = 0
    }

    /** 该点是否可落（只读判断，不修改状态）。 */
    fun isLegal(color: Int, x: Int, y: Int): Boolean {
        if (x !in 0 until size || y !in 0 until size) return false
        if (grid[x][y] != EMPTY) return false
        val sim = copyGrid()
        sim[x][y] = color
        val opp = 3 - color
        for ((dx, dy) in DIRS) {
            val nx = x + dx; val ny = y + dy
            if (inBound(nx, ny) && sim[nx][ny] == opp) {
                val grp = groupOn(sim, nx, ny)
                if (libertiesOn(sim, grp) == 0) for (p in grp) sim[p[0]][p[1]] = EMPTY
            }
        }
        val mine = groupOn(sim, x, y)
        if (libertiesOn(sim, mine) == 0) return false  // 自杀
        val h = hashGrid(sim)
        return !pastPositions.contains(h)               // 超级劫
    }

    /** 所有合法落子点（不含 pass）。供 stub genmove 与 UI 高亮使用。 */
    fun legalMoves(color: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (x in 0 until size) for (y in 0 until size) {
            if (isLegal(color, x, y)) out.add(intArrayOf(x, y))
        }
        return out
    }

    private fun copyGrid(): Array<IntArray> = Array(size) { grid[it].copyOf() }

    private fun inBound(x: Int, y: Int) = x in 0 until size && y in 0 until size

    private fun groupOn(g: Array<IntArray>, x: Int, y: Int): List<IntArray> {
        val color = g[x][y]
        if (color == EMPTY) return emptyList()
        val visited = Array(size) { BooleanArray(size) }
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(x, y))
        visited[x][y] = true
        val out = ArrayList<IntArray>()
        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.removeLast()
            out.add(intArrayOf(cx, cy))
            for ((dx, dy) in DIRS) {
                val nx = cx + dx; val ny = cy + dy
                if (inBound(nx, ny) && !visited[nx][ny] && g[nx][ny] == color) {
                    visited[nx][ny] = true
                    stack.addLast(intArrayOf(nx, ny))
                }
            }
        }
        return out
    }

    private fun libertiesOn(g: Array<IntArray>, group: List<IntArray>): Int {
        var count = 0
        val counted = HashSet<Long>()
        for (p in group) for ((dx, dy) in DIRS) {
            val nx = p[0] + dx; val ny = p[1] + dy
            if (inBound(nx, ny) && g[nx][ny] == EMPTY) {
                val key = nx.toLong() * 1000 + ny
                if (key !in counted) { counted.add(key); count++ }
            }
        }
        return count
    }

    private fun hashGrid(g: Array<IntArray>): Long {
        var h = 1469598103934665603L  // FNV-1a offset
        for (x in 0 until size) for (y in 0 until size) {
            h = h xor g[x][y].toLong()
            h *= 1099511628211L
        }
        return h
    }

    companion object {
        const val EMPTY = 0
        const val BLACK = 1
        const val WHITE = 2
        private val DIRS = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
    }
}
