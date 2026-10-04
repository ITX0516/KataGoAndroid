package com.example.katago

/**
 * SGF（Smart Game Format）棋谱导出。
 *
 * SGF 坐标约定（与 GTP / 内部坐标都不同）：
 *   - 用小写字母 a..s 表示 0..18（19 路以内）
 *   - 第 0 行是棋盘最顶行（与内部 y=0 在最底行相反）
 *   - 列字母 = 'a' + x
 *   - 行字母 = 'a' + (size-1 - y)
 *   - pass 用空 [] 表示（FF[4] 标准写法）
 *
 * 例：9 路棋盘，内部 (3, 3) 即 GTP D4，SGF 写作 [dd]。
 *
 * 输出格式：
 *   (;FF[4]GM[1]SZ[9]KM[6.5]PB[黑]PW[白]
 *   ;B[ed]
 *   ;W[de]
 *   ...)
 */
object SgfWriter {

    /**
     * 从 Board 当前局面生成 SGF 字符串。
     * @param blackName 黑方名字
     * @param whiteName 白方名字
     * @param komi 贴目
     * @param result 可选，如 "B+R"（黑胜不数子）、"W+2.5"
     */
    fun from(
        board: Board,
        blackName: String = "黑",
        whiteName: String = "白",
        komi: Float = 6.5f,
        result: String = ""
    ): String {
        val sb = StringBuilder(256)
        sb.append("(;FF[4]GM[1]SZ[${board.size}]KM[$komi]")
        sb.append("PB[").append(escape(blackName)).append("]")
        sb.append("PW[").append(escape(whiteName)).append("]")
        if (result.isNotEmpty()) sb.append("RE[").append(result).append("]")
        sb.append("\n")

        for (m in board.historySnapshot()) {
            val color = m[0]
            val x = m[1]
            val y = m[2]
            val tag = if (color == Board.BLACK) "B" else "W"
            if (x < 0) {
                // pass：FF[4] 标准用空 []
                sb.append(";").append(tag).append("[]\n")
            } else {
                val cx = (CHOOSE_BASE + x).toChar()
                val cy = (CHOOSE_BASE + (board.size - 1 - y)).toChar()
                sb.append(";").append(tag).append("[").append(cx).append(cy).append("]\n")
            }
        }
        sb.append(")")
        return sb.toString()
    }

    /** SGF 文本转义：] 和 \ 需要反斜杠转义。 */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("]", "\\]")

    private const val CHOOSE_BASE = 'a'.code   // SGF 列/行字母基准
}
