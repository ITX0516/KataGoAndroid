package com.example.katago

/**
 * KataGo 引擎的 JNI 包装。
 *
 * - 构造时加载 libkatago.so 并调用 nativeInit 得到 C++ 引擎指针（handle）。
 * - genmove 把局面 + 历史落子平铺传给 C++，返回一手棋的坐标。
 * - 资源在 close() 时回收（C++ 析构）。
 *
 * 坐标约定：
 *   color: 0=空, 1=黑, 2=白
 *   moveHistory 平铺: [color,x,y, color,x,y, ...]，按落子顺序
 *   返回值: x*boardSize + y；-1 = pass；-2 = resign
 *
 * 在 stub 模式下 modelPath/configPath 可传空串，genmove 返回随机合法点，
 * 用以先验证整条链路是否通。
 */
class KataGoEngine(
    modelPath: String,
    configPath: String
) : AutoCloseable {

    private val handle: Long = nativeInit(modelPath, configPath)

    fun genmove(boardSize: Int, toMove: Int, moveHistory: IntArray): Int =
        nativeGenmove(handle, boardSize, toMove, moveHistory)

    override fun close() {
        nativeDestroy(handle)
    }

    // ─── JNI 声明（对应 jni_bridge.cpp 的 Java_com_example_katago_KataGoEngine_*）──
    private external fun nativeInit(modelPath: String, configPath: String): Long
    private external fun nativeGenmove(
        handle: Long, boardSize: Int, toMove: Int, moveHistory: IntArray
    ): Int
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("katago")
        }
    }
}
