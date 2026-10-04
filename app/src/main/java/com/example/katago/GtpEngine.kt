package com.example.katago

/**
 * GTP（Go Text Protocol）引擎：进程内协议处理 + stub genmove。
 *
 * 协议形态（与标准 GTP 一致）：
 *   输入：`[id] command_name args...`
 *   输出：`?id error_text`（失败）或 `=id reply_text`（成功），最后补一个空行。
 *
 * 现阶段实现：play / genmove / boardsize / clear_board / komi / showboard /
 *             list_commands / known_command / name / version / protocol_version / quit
 *
 * genmove 走 [MoveGen] 接口；默认 [StubGen] 返回随机合法点。
 * 后续接入真 KataGo 时只需把 MoveGen 换成调用 KataGoEngine.kt 的 native 实现。
 *
 * 坐标：列字母 A B C D E F G H J K ...（跳过 I），行号 1..N 从下往上。
 *      内部 (x, y) 与 GTP vertex 的互转见 [vertexToCoord] / [coordToVertex]。
 */
class GtpEngine {

    /** 落子生成器接口。stub 与 native 各一个实现。 */
    interface MoveGen {
        /** 返回 [x, y]；[-1, -1] = pass。 */
        fun genmove(board: Board, color: Int): IntArray
    }

    /** 随机合法点；无合法点则 pass。 */
    class StubGen : MoveGen {
        override fun genmove(board: Board, color: Int): IntArray {
            val legal = board.legalMoves(color)
            if (legal.isEmpty()) return intArrayOf(-1, -1)
            return legal.random()
        }
    }

    /**
     * JNI 接入位（占位）：后续接 KataGoEngine 时实现此类，
     * 然后在 MainActivity 把 `gtp.gen = JniGen(engine)` 即可，
     * GtpEngine 与 BoardView 都不需要改。
     *
     * class JniGen(private val engine: KataGoEngine) : MoveGen {
     *     override fun genmove(board: Board, color: Int): IntArray {
     *         val flat = boardHistoryFlat(board)  // 把 history 平铺成 [color,x,y,...]
     *         val mv = engine.genmove(board.size, color, flat)
     *         return when {
     *             mv == -2 -> intArrayOf(-1, -1)        // resign → pass 兜底
     *             mv == -1 -> intArrayOf(-1, -1)
     *             else -> intArrayOf(mv / board.size, mv % board.size)
     *         }
     *     }
     * }
     */

    /** 当前使用的生成器；可替换为 native 实现。 */
    var gen: MoveGen = StubGen()

    /** 形势判断器；接真 KataGo 后赋值。null 时 kata-analyze 报不可用。 */
    var estimator: ScoreEstimator? = null

    /** 当前棋盘。boardsize / clear_board 会重建。 */
    var board: Board = Board(9); private set

    private var komi: Float = 6.5f

    /** 处理一条 GTP 命令，返回完整响应（含 `=`/`?` 前缀和末尾空行）。 */
    fun execute(cmd: String): String {
        val line = cmd.trim()
        if (line.isEmpty()) return "\n"
        val tokens = line.split(Regex("\\s+")).toMutableList()
        val id = if (tokens.isNotEmpty() && tokens[0].all { it.isDigit() } && tokens[0].isNotEmpty())
            tokens.removeAt(0) else ""
        if (tokens.isEmpty()) return "?$id empty command\n\n"
        val op = tokens.removeAt(0).lowercase()
        val args = tokens

        return try {
            val (ok, body) = run(op, args)
            val prefix = if (ok) "=$id" else "?$id"
            val reply = if (body.isEmpty()) "$prefix " else if (body.startsWith("\n")) "$prefix$body" else "$prefix $body"
            reply + "\n\n"
        } catch (e: Throwable) {
            "?$id ${e.message ?: "error"}\n\n"
        }
    }

    private fun run(op: String, args: List<String>): Pair<Boolean, String> {
        return when (op) {
            "protocol_version" -> true to "2"
            "name"            -> true to "KataGoDemoStub"
            "version"         -> true to "0.1-stub"
            "known_command"   -> true to if (args.isNotEmpty() && args[0] in KNOWN) "true" else "false"
            "list_commands"   -> true to KNOWN.joinToString("\n")
            "boardsize"       -> {
                val n = args.firstOrNull()?.toIntOrNull()
                    ?: throw IllegalArgumentException("bad boardsize")
                board = Board(n)
                true to ""
            }
            "clear_board"     -> { val s = board.size; board = Board(s); true to "" }
            "komi"            -> { komi = args.firstOrNull()?.toFloatOrNull() ?: 0f; true to "" }
            "play"            -> {
                val color = parseColor(args.getOrElse(0) { "" })
                val v = args.getOrElse(1) { "" }
                if (v == "pass") { board.pass(color); return true to "" }
                val (x, y) = vertexToCoord(v, board.size)
                when (val r = board.play(color, x, y)) {
                    Board.Outcome.OK -> true to ""
                    else -> false to "illegal move: $r"
                }
            }
            "genmove"         -> {
                val color = parseColor(args.getOrElse(0) { "" })
                val m = gen.genmove(board, color)
                if (m[0] < 0) { board.pass(color); true to "pass" }
                else {
                    val r = board.play(color, m[0], m[1])
                    if (r != Board.Outcome.OK) false to "illegal genmove: $r"
                    else true to coordToVertex(m[0], m[1], board.size)
                }
            }
            "showboard"       -> true to "\n" + showBoard()
            // 终局：双方连续 pass 才算
            "final_score"     -> {
                if (!board.isTwoPasses())
                    false to "game not over (need two consecutive passes or use force_final_score)"
                else {
                    val diff = board.chineseScore()   // 正=黑领先，负=白领先
                    val score = if (diff > 0) "B+${"%.1f".format(diff + komi)}" else "W+${"%.1f".format(-diff - komi)}"
                    true to score
                }
            }
            // 强制终局：不要求双方 pass，立即按当前局面数子
            "force_final_score" -> {
                val diff = board.chineseScore()
                val score = if (diff > 0) "B+${"%.1f".format(diff + komi)}" else "W+${"%.1f".format(-diff - komi)}"
                true to score
            }
            "final_status"    -> {
                // 简化：仅返回 alive/dead。stub 模式无死子识别 → 全 alive
                val v = args.getOrElse(0) { "" }
                if (v.isEmpty()) false to "vertex required"
                else {
                    val (x, y) = vertexToCoord(v, board.size)
                    when (board.get(x, y)) {
                        Board.EMPTY -> true to "empty"
                        else -> true to "alive"
                    }
                }
            }
            // 形势判断：流式输出 winrate + 候选手。stub 无法给出真实胜率，
            // 接真 KataGo 后由 ScoreEstimator 实现。
            "kata-analyze", "lz-analyze", "analyze" -> {
                if (estimator == null)
                    false to "${op} not available (set estimator=InfluenceEstimator or KataGoEstimator)"
                else {
                    val color = parseColor(args.getOrElse(0) { "" })
                    val r = estimator!!.analyze(board, color)
                    true to formatAnalyze(r)
                }
            }
            // 影响力地图：返回每点的影响力值，供 UI 染色
            "influence_map"   -> {
                val r = InfluenceMap(board.size).compute(board)
                val sb = StringBuilder()
                sb.append("winrate=${"%.2f".format(r.winrateBlack)} ")
                sb.append("lead=${"%.1f".format(r.scoreLead)} ")
                sb.append("blackTerr=${r.blackTerritory} whiteTerr=${r.whiteTerritory} dame=${r.dame}\n")
                for (y in board.size - 1 downTo 0) {
                    for (x in 0 until board.size) {
                        val v = r.map[x * board.size + y]
                        sb.append(String.format("%+4d", v.toInt()))
                    }
                    sb.append("\n")
                }
                true to sb.toString()
            }
            "quit"            -> true to ""
            else              -> false to "unknown command"
        }
    }

    /** 把 ScoreEstimator.Result 格式化为 lz-analyze 兼容串。 */
    private fun formatAnalyze(r: ScoreEstimator.Result): String {
        // lz-analyze 格式：winrate=NN.N moveCnt=N pv=coord1,coord2,...
        val wr = "%.2f".format(r.winrateBlack)
        val pv = r.pv.joinToString(" ") { coordToVertex(it[0], it[1], board.size) }
        return "winrate=$wr moveCnt=${r.moveCount} pv=$pv"
    }

    /** 文本棋盘，列字母+行号标注。 */
    private fun showBoard(): String {
        val sb = StringBuilder()
        sb.append("  ")
        for (x in 0 until board.size) sb.append(COLS[x])
        sb.append("\n")
        for (y in board.size - 1 downTo 0) {
            sb.append(String.format("%2d", y + 1))
            for (x in 0 until board.size) {
                sb.append(when (board.get(x, y)) {
                    Board.BLACK -> "X"
                    Board.WHITE -> "O"
                    else -> "."
                })
            }
            sb.append(String.format("%-2d", y + 1)).append("\n")
        }
        sb.append("  ")
        for (x in 0 until board.size) sb.append(COLS[x])
        sb.append("\n")
        sb.append("X=黑 O=白 .=空  坐标列跳过 I")
        return sb.toString()
    }

    companion object {
        private val KNOWN = listOf(
            "protocol_version", "name", "version", "known_command", "list_commands",
            "boardsize", "clear_board", "komi", "play", "genmove", "showboard",
            "final_score", "force_final_score", "final_status",
            "kata-analyze", "lz-analyze", "analyze", "influence_map",
            "quit"
        )

        // GTP 列字母：跳过 I（避免与 1 混淆）
        private const val COLS = "ABCDEFGHJKLMNOPQRSTUVWXYZ"

        fun parseColor(s: String): Int = when (s.lowercase()) {
            "b", "black" -> Board.BLACK
            "w", "white" -> Board.WHITE
            else -> throw IllegalArgumentException("bad color: $s")
        }

        /** "D4" -> (3, 3)；boardSize 用于校验范围。 */
        fun vertexToCoord(v: String, boardSize: Int): Pair<Int, Int> {
            if (v.isEmpty()) throw IllegalArgumentException("empty vertex")
            val c = v[0].uppercaseChar()
            val col = COLS.indexOf(c)
            if (col < 0 || col >= boardSize) throw IllegalArgumentException("bad vertex: $v")
            val row = v.substring(1).toIntOrNull()?.minus(1)
                ?: throw IllegalArgumentException("bad vertex: $v")
            if (row !in 0 until boardSize) throw IllegalArgumentException("bad vertex: $v")
            return col to row
        }

        fun coordToVertex(x: Int, y: Int, boardSize: Int): String {
            val c = COLS[x]
            return "$c${y + 1}"
        }
    }
}
