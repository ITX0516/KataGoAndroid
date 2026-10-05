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
    private var kataEngine: KataGoEngine? = null   // null = 用 StubGen 兜底
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
        AppLogger.init(this)
        AppLogger.i("MainActivity", "onCreate boardSize=$boardSize aiMode=$aiMode")
        try {
            setContentView(R.layout.activity_main)
        } catch (e: Throwable) {
            AppLogger.e("MainActivity", "setContentView failed", e)
            throw e
        }

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

        // 初始化 KataGo 引擎（必须在 statusText 之后，因为 flash() 要用）
        initKataGoEngine()

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

    // ─── 初始化 KataGo 引擎 ────────────────────────────────────
    private fun initKataGoEngine() {
        try {
            val filesDir = filesDir
            // 1) 把 assets 里的模型 + 配置拷到 filesDir（assets 是只读的，KataGo 需要可写路径）
            copyAssetsToFiles("gtp.cfg", filesDir)
            copyAssetsToFiles("models/b10c384.bin", filesDir)

            val modelPath  = File(filesDir, "b10c384.bin").absolutePath
            val configPath = File(filesDir, "gtp.cfg").absolutePath

            if (!File(modelPath).exists()) {
                AppLogger.w("MainActivity", "initKataGoEngine: model not found, fallback to StubGen")
                flash("未找到模型，AI 使用随机模式")
                return
            }

            // 2) 创建引擎（nativeInit 加载模型 + 配置）
            kataEngine = KataGoEngine(modelPath, configPath)
            if (kataEngine!!.isReady()) {
                gtp.gen = GtpEngine.JniGen(kataEngine!!)
                AppLogger.i("MainActivity", "KataGo engine READY (real mode)")
                flash("AI 已就绪")
            } else {
                val err = kataEngine!!.lastError()
                AppLogger.e("MainActivity", "KataGo engine NOT ready: $err")
                kataEngine = null
                flash("AI 初始化失败: $err")
            }
        } catch (e: Throwable) {
            AppLogger.e("MainActivity", "initKataGoEngine failed, fallback to StubGen", e)
            kataEngine = null
            flash("AI 初始化失败: ${e.message}")
        }
    }

    private fun copyAssetsToFiles(name: String, dir: File) {
        val dst = File(dir, name.substringAfterLast('/'))
        if (dst.exists()) return   // 已拷过就跳过，省 IO
        assets.open(name).use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
    }

    // ─── 人落子 ────────────────────────────────────────────────
    private fun onTap(x: Int, y: Int) {
        if (aiThinking) { AppLogger.w("MainActivity", "onTap ignored: aiThinking"); return }
        val color = gtp.board.toMove()
        if (color == Board.BLACK && aiMode == AiMode.AI_BLACK) return  // 轮到 AI
        if (color == Board.WHITE && aiMode == AiMode.AI_WHITE) return

        val r = gtp.board.play(color, x, y)
        AppLogger.i("MainActivity", "onTap color=$color ($x,$y) -> $r")
        if (r != Board.Outcome.OK) {
            flash("非法落子: $r"); return
        }
        if (showInfluence) boardView.showInfluence(null)
        boardView.refreshFrom(gtp.board)
        updateStatus()
        maybeAiMove()
    }

    private fun onPass() {
        if (aiThinking) { AppLogger.w("MainActivity", "onPass ignored: aiThinking"); return }
        val color = gtp.board.toMove()
        if (color == Board.BLACK && aiMode == AiMode.AI_BLACK) return
        if (color == Board.WHITE && aiMode == AiMode.AI_WHITE) return
        AppLogger.i("MainActivity", "onPass color=$color")
        val gameOver = gtp.board.pass(color)
        boardView.refreshFrom(gtp.board)
        if (gameOver == Board.GameOver.YES) {
            AppLogger.i("MainActivity", "onPass -> game over (two passes)")
            showFinalScore()
            return
        }
        updateStatus()
        maybeAiMove()
    }

    /** 终局计分弹窗（中国数子 + 贴目）。 */
    private fun showFinalScore() {
        try {
            val b = gtp.board
            val komi = 6.5f
            val diff = b.chineseScore()           // 不贴目差值（正=黑领先）
            val blackTotal = b.lastBlackScore + komi
            val whiteTotal = b.lastWhiteScore
            AppLogger.i("MainActivity", "showFinalScore diff=$diff black=$blackTotal white=$whiteTotal dame=${b.lastDame}")
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
            android.app.AlertDialog.Builder(this)
                .setTitle("终局结算")
                .setMessage(msg)
                .setPositiveButton("重开") { _, _ -> onReset() }
                .setNegativeButton("查看棋盘", null)
                .show()
            flash("$winner +${"%.1f".format(margin)}  (黑 ${b.lastBlackScore.toInt()}+${komi} vs 白 ${b.lastWhiteScore.toInt()})")
        } catch (e: Throwable) {
            AppLogger.e("MainActivity", "showFinalScore crashed", e)
            flash("计分出错: ${e.message}")
        }
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
        AppLogger.i("MainActivity", "onForceEnd moveCount=${gtp.board.moveCount()}")
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
        AppLogger.i("MainActivity", "aiMove start color=$colorChar moveCount=${gtp.board.moveCount()}")
        Thread {
            try {
                val resp = gtp.execute("genmove $colorChar")
                AppLogger.i("MainActivity", "aiMove genmove resp=$resp")
                runOnUiThread {
                    try {
                        aiThinking = false
                        boardView.refreshFrom(gtp.board)
                        if (gtp.board.isTwoPasses()) {
                            AppLogger.i("MainActivity", "aiMove -> two passes, game over")
                            showFinalScore()
                        } else {
                            updateStatus()
                            maybeAiMove()
                        }
                    } catch (e: Throwable) {
                        AppLogger.e("MainActivity", "aiMove ui-thread crashed", e)
                        aiThinking = false
                    }
                }
            } catch (e: Throwable) {
                AppLogger.e("MainActivity", "aiMove genmove crashed", e)
                runOnUiThread {
                    aiThinking = false
                    flash("AI 出错: ${e.message}")
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
        val lastStr = lm?.let { m ->
            when (m[1]) {
                -1 -> "pass"
                else -> GtpEngine.coordToVertex(m[1], m[2], gtp.board.size)
            }
        } ?: "无"
        val aiStr = if (kataEngine != null) "AI已就绪" else "AI随机模式"
        statusText.text = "$modeStr | $aiStr | 回合:$turnStr | 提子 黑=${gtp.board.blackCaptured} 白=${gtp.board.whiteCaptured} | 上一手:$lastStr"
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

    override fun onDestroy() {
        super.onDestroy()
        try { kataEngine?.close() } catch (_: Throwable) {}
    }
}
