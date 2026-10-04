package com.example.katago

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自由落子 + GTP 引擎的最小对弈界面。
 *
 * 数据流：
 *   BoardView 点击 → onTap(x, y) → gtp.board.play(...) → refresh
 *   若当前轮到 AI → aiMove() 后台线程 → gtp.execute("genmove w/b")
 *
 * AI 模式可循环切换：双人对弈 → AI 执白 → AI 执黑 → 双人对弈。
 *
 * 当前 gtp.gen 默认是 GtpEngine.StubGen（随机合法点）。
 * 后续接 JNI：把 gtp.gen 替换为调用 KataGoEngine.nativeGenmove 的实现即可，
 * MainActivity 与 BoardView 都不需要改。
 */
class MainActivity : Activity() {

    private enum class AiMode { NONE, AI_WHITE, AI_BLACK }

    private val boardSize = 9
    private var aiMode: AiMode = AiMode.AI_WHITE

    @Volatile private var aiThinking: Boolean = false   // AI 思考时禁止人下

    private lateinit var gtp: GtpEngine
    private lateinit var boardView: BoardView
    private lateinit var statusText: TextView
    private lateinit var passBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var exportBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var influenceBtn: Button
    private lateinit var forceEndBtn: Button
    private lateinit var sizeSpinner: Spinner

    /** 当前是否显示影响力地图。再次点击形势判断按钮可关闭。 */
    private var showInfluence: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gtp = GtpEngine().also { it.execute("boardsize $boardSize") }

        boardView = findViewById(R.id.boardView)
        statusText = findViewById(R.id.statusText)
        passBtn     = findViewById(R.id.passBtn)
        modeBtn     = findViewById(R.id.modeBtn)
        exportBtn   = findViewById(R.id.exportBtn)
        resetBtn    = findViewById(R.id.resetBtn)
        influenceBtn= findViewById(R.id.influenceBtn)
        forceEndBtn = findViewById(R.id.forceEndBtn)
        sizeSpinner = findViewById(R.id.sizeSpinner)

        boardView.board = gtp.board
        boardView.onCellTapped = ::onTap

        // 棋盘大小 Spinner：2..19
        val sizes = (2..19).toList()
        sizeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, sizes
        )
        sizeSpinner.setSelection(sizes.indexOf(9))   // 默认 9 路
        sizeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val n = sizes[pos]
                if (n != gtp.board.size) {
                    gtp.execute("boardsize $n")
                    gtp.execute("clear_board")
                    boardView.setBoardSize(n)
                    boardView.refreshFrom(gtp.board)
                    boardView.showInfluence(null)   // 切棋盘关掉染色
                    showInfluence = false
                    updateStatus()
                    if (aiMode == AiMode.AI_BLACK) aiMove()
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        passBtn.setOnClickListener     { onPass() }
        modeBtn.setOnClickListener    { cycleMode() }
        exportBtn.setOnClickListener { exportSgf() }
        resetBtn.setOnClickListener  { onReset() }
        influenceBtn.setOnClickListener { onInfluence() }
        forceEndBtn.setOnClickListener { onForceEnd() }

        // 如果 AI 执黑，开局让 AI 先下一手
        if (aiMode == AiMode.AI_BLACK) aiMove()
        updateStatus()
    }

    // ─── 人落子 ────────────────────────────────────────────────
    private fun onTap(x: Int, y: Int) {
        if (aiThinking) return
        val color = gtp.board.toMove()
        if (color == Board.BLACK && aiMode == AiMode.AI_BLACK) return  // 轮到 AI
        if (color == Board.WHITE && aiMode == AiMode.AI_WHITE) return

        val r = gtp.board.play(color, x, y)
        if (r != Board.Outcome.OK) {
            flash("非法落子: $r"); return
        }
        if (showInfluence) boardView.showInfluence(null)   // 落子后清染色，等再点形势判断
        boardView.refreshFrom(gtp.board)
        updateStatus()
        maybeAiMove()
    }

    private fun onPass() {
        if (aiThinking) return
        val color = gtp.board.toMove()
        if (color == Board.BLACK && aiMode == AiMode.AI_BLACK) return
        if (color == Board.WHITE && aiMode == AiMode.AI_WHITE) return
        val gameOver = gtp.board.pass(color)
        boardView.refreshFrom(gtp.board)
        if (gameOver == Board.GameOver.YES) {
            showFinalScore()
            return
        }
        updateStatus()
        maybeAiMove()
    }

    /** 终局计分弹窗（中国数子 + 贴目）。 */
    private fun showFinalScore() {
        val b = gtp.board
        val komi = 6.5f
        val diff = b.chineseScore()           // 不贴目差值（正=黑领先）
        val blackTotal = b.lastBlackScore + komi
        val whiteTotal = b.lastWhiteScore
        val (winner, margin) = if (blackTotal > whiteTotal)
            "黑" to (blackTotal - whiteTotal)
        else
            "白" to (whiteTotal - blackTotal)
        val msg = """
            |终局（双方 pass）
            |
            |黑：${b.lastBlackScore.toInt()} 子 + 贴目 $komi = ${"%.1f".format(blackTotal)}
            |白：${b.lastWhiteScore.toInt()} 子
            |单官：${b.lastDame} 点未数
            |
            |提子统计：黑提 ${b.blackCaptured} / 白提 ${b.whiteCaptured}
            |
            |>>> $winner +${"%.1f".format(margin)}
        """.trimMargin()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("终局结算")
            .setMessage(msg)
            .setPositiveButton("重开") { _, _ -> onReset() }
            .setNegativeButton("查看棋盘", null)
            .show()
        flash("$winner +${"%.1f".format(margin)}  (黑 ${b.lastBlackScore.toInt()}+${komi} vs 白 ${b.lastWhiteScore.toInt()})")
    }

    /** 形势判断：用影响力函数给出当前局面胜率 + 染色。 */
    private fun onInfluence() {
        if (aiThinking) return
        showInfluence = !showInfluence
        if (showInfluence) {
            val r = InfluenceMap(gtp.board.size).compute(gtp.board)
            boardView.showInfluence(r.map, 127f)
            val blackTotal = gtp.board.let { b ->
                // 用影响力结果算总数差
                var bs = 0; var ws = 0
                for (x in 0 until b.size) for (y in 0 until b.size) {
                    when (b.get(x, y)) {
                        Board.BLACK -> bs++
                        Board.WHITE -> ws++
                    }
                }
                bs to ws
            }
            val lead = (blackTotal.first + r.blackTerritory) - (blackTotal.second + r.whiteTerritory)
            val wr = r.winrateBlack
            flash("形势：黑胜率 ${(wr * 100).toInt()}% | 黑领 ${"%.1f".format(lead.toFloat())} 目 | 黑地 ${r.blackTerritory} 白地 ${r.whiteTerritory}")
        } else {
            boardView.showInfluence(null)
            updateStatus()
        }
    }

    /** 强制终局：立即按当前局面数子。 */
    private fun onForceEnd() {
        if (aiThinking) return
        showFinalScore()
    }

    private fun onReset() {
        if (aiThinking) return
        gtp.execute("clear_board")
        boardView.refreshFrom(gtp.board)
        if (aiMode == AiMode.AI_BLACK) aiMove()
        updateStatus()
    }

    private fun cycleMode() {
        if (aiThinking) return
        aiMode = when (aiMode) {
            AiMode.NONE     -> AiMode.AI_WHITE
            AiMode.AI_WHITE -> AiMode.AI_BLACK
            AiMode.AI_BLACK -> AiMode.NONE
        }
        gtp.execute("clear_board")
        boardView.refreshFrom(gtp.board)
        if (aiMode == AiMode.AI_BLACK) aiMove()
        updateStatus()
    }

    // ─── AI 落子（后台线程，避免阻塞 UI）────────────────────────
    private fun maybeAiMove() {
        if (aiMode == AiMode.NONE) return
        val next = gtp.board.toMove()
        val aiColor = if (aiMode == AiMode.AI_BLACK) Board.BLACK else Board.WHITE
        if (next != aiColor) return
        aiMove()
    }

    private fun aiMove() {
        aiThinking = true
        statusText.text = "AI 思考中…"
        val colorChar = if (gtp.board.toMove() == Board.BLACK) "b" else "w"
        Thread {
            // 这条调用链就是后续替换为 native 的入口：
            //   gtp.gen = JniGen(KataGoEngine(...))
            // 之后 gtp.execute("genmove b") 内部走的是 native，对外接口不变。
            val resp = gtp.execute("genmove $colorChar")
            // 留作 logcat 调试：观察 GTP 响应
            android.util.Log.i("KataGoDemo", "genmove resp=$resp")
            runOnUiThread {
                aiThinking = false
                boardView.refreshFrom(gtp.board)
                if (gtp.board.isTwoPasses()) {
                    showFinalScore()    // AI 也 pass → 终局
                } else {
                    updateStatus()
                    maybeAiMove()       // 双方都是 AI 时继续
                }
            }
        }.start()
    }

    // ─── 状态条 ────────────────────────────────────────────────
    private fun updateStatus() {
        val turn = gtp.board.toMove()
        val turnStr = if (turn == Board.BLACK) "黑" else "白"
        val modeStr = when (aiMode) {
            AiMode.NONE     -> "双人对弈"
            AiMode.AI_WHITE -> "AI 执白"
            AiMode.AI_BLACK -> "AI 执黑"
        }
        val lm = gtp.board.lastMove()
        val lastStr = when {
            lm == null -&gt; "无"
            lm[1] == -1 -&gt; "pass"
            else -&gt; GtpEngine.coordToVertex(lm[1], lm[2], gtp.board.size)
        }
        statusText.text = "$modeStr | 回合:$turnStr | 提子 黑=${gtp.board.blackCaptured} 白=${gtp.board.whiteCaptured} | 上一手:$lastStr"
    }

    private fun flash(msg: String) {
        statusText.text = msg
    }

    // ─── SGF 导出 ───────────────────────────────────────────────
    private fun exportSgf() {
        if (aiThinking) return
        if (gtp.board.moveCount() == 0) {
            flash("棋盘为空，无棋谱可导出"); return
        }
        val modeStr = when (aiMode) {
            AiMode.NONE     -> "human_vs_human"
            AiMode.AI_WHITE -> "human_black_ai_white"
            AiMode.AI_BLACK -> "ai_black_human_white"
        }
        val sgf = SgfWriter.from(
            board      = gtp.board,
            blackName  = if (aiMode == AiMode.AI_BLACK) "KataGo-Stub" else "Player-Black",
            whiteName  = if (aiMode == AiMode.AI_WHITE) "KataGo-Stub" else "Player-White",
            komi       = 6.5f
        )

        val dir = File(filesDir, "games").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "katago_${modeStr}_$ts.sgf")
        file.writeText(sgf, Charsets.UTF_8)

        val authority = "$packageName.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(this, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-go-sgf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 SGF"))
        flash("已生成 ${file.name}")
    }
}
