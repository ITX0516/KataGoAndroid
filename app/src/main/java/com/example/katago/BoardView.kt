package com.example.katago

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 纯显示棋盘 View：木色底、网格线、黑白子、最后一手标记。
 *
 * 坐标约定（与 Board / GTP 一致）：
 *   x = 列号 0..size-1（左→右）
 *   y = 行号 0..size-1（下→上），y=0 是最底行
 * 屏幕坐标 y 是从上往下，所以绘制时要翻转：(size-1) - y。
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var boardSize: Int = 9; private set

    /** 当前显示的棋盘；通过 refreshFrom 同步显示。 */
    var board: Board? = null
        set(value) { field = value; refreshFrom(value); invalidate() }

    /** 改变棋盘大小；会清空当前显示并从 [board] 重新同步。 */
    fun setBoardSize(n: Int) {
        if (n == boardSize) return
        boardSize = n
        cells = Array(n) { IntArray(n) }
        refreshFrom(board)
        invalidate()
    }

    var onCellTapped: ((x: Int, y: Int) -> Unit)? = null

    private var cells: Array<IntArray> = Array(boardSize) { IntArray(boardSize) }
    private var lastX: Int = -1
    private var lastY: Int = -1

    /** 影响力地图：null 时不显示；非 null 时染色 [x*size+y]。 */
    private var influenceMap: FloatArray? = null
    private var influenceMax: Float = 127f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000.toInt(); strokeWidth = 2f
    }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8B964.toInt() }
    private val lastMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5722.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val influencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 从 Board 同步棋子到内部缓存。 */
    fun refreshFrom(b: Board?) {
        if (b == null) {
            cells = Array(boardSize) { IntArray(boardSize) }
            lastX = -1; lastY = -1
            return
        }
        if (b.size != boardSize) {
            boardSize = b.size  // 触发 setter 重建 cells，再继续
        }
        cells = Array(boardSize) { IntArray(boardSize) }
        for (x in 0 until boardSize) for (y in 0 until boardSize) {
            cells[x][y] = b.get(x, y)
        }
        val lm = b.lastMove()
        if (lm != null && lm[1] >= 0) {
            lastX = lm[1]; lastY = lm[2]
        } else {
            lastX = -1; lastY = -1
        }
        invalidate()
    }

    /** 显示影响力地图（覆盖在棋盘上染色）。传 null 关闭。 */
    fun showInfluence(map: FloatArray?, max: Float = 127f) {
        influenceMap = map
        influenceMax = if (max > 0) max else 127f
        invalidate()
    }

    /** 把影响力值映射成 ARGB：正→半透黑，负→半透白，0→透明。 */
    private fun influenceColor(v: Float): Int {
        val norm = (v / influenceMax).coerceIn(-1f, 1f)
        val alpha = (Math.abs(norm) * 130).toInt().coerceIn(0, 255)
        return when {
            norm > 0 -> (alpha shl 24) or 0x000000   // 半透黑
            norm < 0 -> (alpha shl 24) or 0xFFFFFF   // 半透白
            else -> 0x00000000                        // 透明
        }
    }

    // 返回 (pad, step) 几何
    private fun geom(): Pair<Float, Float> {
        val side = minOf(width, height).toFloat()
        val pad = side * 0.05f
        val usable = side - 2 * pad
        val step = if (boardSize > 1) usable / (boardSize - 1) else usable
        return pad to step
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        val pad = side * 0.05f

        canvas.drawRect(pad * 0.4f, pad * 0.4f,
            side - pad * 0.4f, side - pad * 0.4f, boardPaint)

        val (p, step) = geom()
        for (i in 0 until boardSize) {
            val v = p + i * step
            canvas.drawLine(p, v, p + (boardSize - 1) * step, v, linePaint)
            canvas.drawLine(v, p, v, p + (boardSize - 1) * step, linePaint)
        }
        // 影响力地图染色（在棋子下方）
        influenceMap?.let { mp ->
            val half = step / 2
            for (x in 0 until boardSize) for (y in 0 until boardSize) {
                val v = mp[x * boardSize + y]
                val c = influenceColor(v)
                if (c == 0) continue
                val screenY = (boardSize - 1) - y
                val cx = p + x * step; val cy = p + screenY * step
                influencePaint.color = c
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, influencePaint)
            }
        }
        for (x in 0 until boardSize) for (y in 0 until boardSize) {
            val c = cells[x][y]
            if (c == 0) continue
            // y 翻转：屏幕 y 从上往下，棋盘 y 从下往上
            val screenY = (boardSize - 1) - y
            val cx = p + x * step
            val cy = p + screenY * step
            val r = step * 0.45f
            if (c == 1) {
                canvas.drawCircle(cx, cy, r, blackPaint)
            } else {
                canvas.drawCircle(cx, cy, r, whiteFill)
                canvas.drawCircle(cx, cy, r, whiteStroke)
            }
        }
        // 最后一手标记
        if (lastX in 0 until boardSize && lastY in 0 until boardSize) {
            val screenY = (boardSize - 1) - lastY
            val cx = p + lastX * step
            val cy = p + screenY * step
            val r = step * 0.18f
            canvas.drawCircle(cx, cy, r, lastMarkPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        val (p, step) = geom()
        val col = ((event.x - p + step / 2) / step).toInt()
        val screenRow = ((event.y - p + step / 2) / step).toInt()
        if (col !in 0 until boardSize || screenRow !in 0 until boardSize) return false
        val row = (boardSize - 1) - screenRow   // 翻转回棋盘坐标
        onCellTapped?.invoke(col, row)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        if (s == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        setMeasuredDimension(s, s)
    }
}
