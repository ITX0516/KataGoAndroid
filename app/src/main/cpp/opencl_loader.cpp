#include "opencl_loader.h"

#include <android/log.h>
#include <dlfcn.h>

#define TAG "OpenCLLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

OpenCLLoader&amp; OpenCLLoader::instance() {
    static OpenCLLoader inst;
    return inst;
}

bool OpenCLLoader::init() {
    if (handle_) return true;

    // Android 厂商放置 OpenCL 的常见路径与命名
    static const char* kPaths[] = {
        "libOpenCL.so",
        "libOpenCL-pixel.so",
        "libGLES_mali.so",       // Mali GPU 暴露的 OpenCL
        "libPVROCL.so",          // PowerVR
        "libadreno-sdk.so",      // Adreno 历史命名
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/lib/libOpenCL.so",
    };

    for (const char* p : kPaths) {
        handle_ = dlopen(p, RTLD_LAZY | RTLD_LOCAL);
        if (handle_) {
            LOGI("loaded OpenCL from %s", p);
            return true;
        }
    }
    LOGE("OpenCL not available on device: %s", dlerror());
    return false;
}
