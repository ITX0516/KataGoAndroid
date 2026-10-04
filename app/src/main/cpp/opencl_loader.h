#pragma once

// ───────────────────────────────────────────────────────────────
// OpenCLLoader
// Android 上 libOpenCL.so 由厂商放到 /vendor/lib[64]、/system/lib[64]
// 或厂商专有命名（如 libOpenCL-pixel.so）。无法静态链接，必须 dlopen。
// 本加载器封装为单例，供 KataGoEngine 在真模式下使用。
// ───────────────────────────────────────────────────────────────
class OpenCLLoader {
public:
    static OpenCLLoader&amp; instance();

    // 尝试从常见路径加载 libOpenCL.so。成功返回 true 且后续 available()=true。
    bool init();

    bool  available() const { return handle_ != nullptr; }
    void* handle()    const { return handle_; }

private:
    OpenCLLoader() = default;
    void* handle_ = nullptr;
};
