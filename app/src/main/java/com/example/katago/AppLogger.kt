package com.example.katago

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 文件日志：同时输出到 logcat 和磁盘文件。
 *
 * 日志路径：
 *   ${context.getExternalFilesDir(null)}/katago_logs/log_YYYYMMDD.txt
 *   （外部存储私有目录，无需存储权限，用户可通过"文件"应用查看）
 *
 * 用法：
 *   AppLogger.init(this)            // Application.onCreate 或 MainActivity.onCreate
 *   AppLogger.i("MainActivity", "onTap x=$x y=$y")
 *   AppLogger.e("MainActivity", "genmove failed", throwable)
 *
 * 线程安全：内部用 ReentrantLock 串行化写文件。
 */
object AppLogger {

    private var logDir: File? = null
    private var currentFile: File? = null
    private val lock = ReentrantLock()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    /** 初始化日志目录。必须在首次 log 前调用。 */
    fun init(context: Context) {
        try {
            // 外部存储私有目录：/sdcard/Android/data/<package>/files/katago_logs/
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            logDir = File(base, "katago_logs").apply { mkdirs() }
            rotateIfNeeded()
            i("AppLogger", "log file = ${currentFile?.absolutePath}")
        } catch (e: Throwable) {
            Log.e("AppLogger", "init failed", e)
        }
    }

    /** 当前日志文件路径，供 UI 显示给用户。 */
    fun logFilePath(): String? = currentFile?.absolutePath

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        write("W", tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        val sb = StringBuilder(msg)
        if (t != null) {
            sb.append('\n').append(Log.getStackTraceString(t))
        }
        write("E", tag, sb.toString())
    }

    private fun write(level: String, tag: String, msg: String) {
        try {
            lock.withLock {
                rotateIfNeeded()
                val f = currentFile ?: return
                val ts = timeFmt.format(Date())
                f.appendText("[$ts] $level/$tag: $msg\n")
            }
        } catch (_: Throwable) {
            // 日志写入失败不影响主流程
        }
    }

    /** 按天分文件：每天一个 log_YYYYMMDD.txt。 */
    private fun rotateIfNeeded() {
        val dir = logDir ?: return
        val name = "log_${dateFmt.format(Date())}.txt"
        val target = File(dir, name)
        if (currentFile?.name != target.name) {
            currentFile = target
            if (!target.exists()) {
                target.createNewFile()
                target.appendText("=== KataGoAndroid log started at ${timeFmt.format(Date())} ===\n")
            }
        }
    }
}
