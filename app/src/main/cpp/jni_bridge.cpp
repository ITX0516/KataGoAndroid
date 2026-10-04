// ───────────────────────────────────────────────────────────────
// jni_bridge.cpp
// JNI 入口：把 com.example.katago.KataGoEngine 的 external 方法
// 映射到 C++ 的 KataGoEngine。
//
// handle 用 jlong 承载 C++ 指针，避免反复查找，也避免把对象指针
// 装进 Java 域引发的 GC/生命周期混乱。
// ───────────────────────────────────────────────────────────────
#include <jni.h>

#include <android/log.h>

#include "katago_engine.h"
#include "opencl_loader.h"

#define TAG "KataGoJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr const char* kEngineClass = "com/example/katago/KataGoEngine";

extern "C" {

// 构造引擎。modelPath/configPath 在 stub 模式下可为空字符串。
JNIEXPORT jlong JNICALL
Java_com_example_katago_KataGoEngine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath, jstring configPath) {
    const char* model  = modelPath  ? env->GetStringUTFChars(modelPath,  nullptr) : "";
    const char* config = configPath ? env->GetStringUTFChars(configPath, nullptr) : "";

    auto* engine = new KataGoEngine(model, config);

    if (modelPath)  env->ReleaseStringUTFChars(modelPath,  model);
    if (configPath) env->ReleaseStringUTFChars(configPath, config);

    LOGI("engine constructed, handle=%p", static_cast<void*>(engine));
    return reinterpret_cast<jlong>(engine);
}

// 生成一手棋。返回 x*boardSize+y；-1 pass；-2 resign。
JNIEXPORT jint JNICALL
Java_com_example_katago_KataGoEngine_nativeGenmove(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint boardSize, jint toMove,
        jintArray moveHistory) {
    auto* engine = reinterpret_cast<KataGoEngine*>(handle);
    if (!engine) {
        LOGE("genmove on null engine");
        return -2;
    }

    jsize n = moveHistory ? env->GetArrayLength(moveHistory) : 0;
    if (n % 3 != 0) {
        LOGE("moveHistory length not multiple of 3: %d", n);
        return -2;
    }

    jint* moves = (n > 0) ? env->GetIntArrayElements(moveHistory, nullptr) : nullptr;
    int move = engine->genmove(boardSize, toMove,
                               reinterpret_cast<const int*>(moves), n / 3);
    if (moves) {
        // JNI_ABORT：不回写 Java 侧，因为我们没改数组
        env->ReleaseIntArrayElements(moveHistory, moves, JNI_ABORT);
    }
    return move;
}

JNIEXPORT void JNICALL
Java_com_example_katago_KataGoEngine_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* engine = reinterpret_cast<KataGoEngine*>(handle);
    delete engine;
    LOGI("engine destroyed, handle=%p", static_cast<void*>(engine));
}

}  // extern "C"
