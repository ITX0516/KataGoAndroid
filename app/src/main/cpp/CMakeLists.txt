cmake_minimum_required(VERSION 3.22.1)
project(katago LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ───────────────────────────────────────────────────────────────
# 构建模式（核心开关）
# ───────────────────────────────────────────────────────────────
# STUB 模式（默认）：不依赖 KataGo 源码即可编译。
#   genmove 返回随机合法点，用于先验证 UI → JNI → C++ → 回显 整条链路。
# 真实模式：在 app/build.gradle.kts 的 cmake 块加：
#   arguments += "-DUSE_REAL_KATAGO=ON"
#   arguments += "-DKATAGO_SRC=/abs/path/to/karino2/KataGo"
#   arguments += "-DUSE_OPENCL=ON"
#   （KATAGO_SRC 指向 karino2 fork 的克隆目录）
# ───────────────────────────────────────────────────────────────
option(USE_REAL_KATAGO "Link against real KataGo (karino2 fork)" OFF)
option(USE_OPENCL      "Enable OpenCL backend (real mode)"        OFF)

# 永远编译的本地源：JNI 桥 + 引擎封装 + OpenCL 动态加载器
add_library(katago SHARED
    jni_bridge.cpp
    katago_engine.cpp
    opencl_loader.cpp
)

# Android 系统库
find_library(log-lib log)   # android/log.h
find_library(dl-lib  dl)     # dlopen / dlsym
target_link_libraries(katago ${log-lib} ${dl-lib})

if(USE_REAL_KATAGO)
    if(NOT KATAGO_SRC)
        message(FATAL_ERROR "USE_REAL_KATAGO=ON 但未提供 KATAGO_SRC")
    endif()

    # karino2 fork 的源码布局（与上游 lightvector/KataGo 一致）：
    #   ${KATAGO_SRC}/cpp/        —— 引擎主源（含 core/、search/、game/、neuralnet/ 等）
    #   ${KATAGO_SRC}/external/   —— Eigen、json.hpp、clBLAST 头等
    target_include_directories(katago PRIVATE
        ${KATAGO_SRC}/cpp
        ${KATAGO_SRC}/external
        ${KATAGO_SRC}/external/eigen
        ${KATAGO_SRC}/external/clblast/include
    )

    target_compile_definitions(katago PRIVATE
        USE_REAL_KATAGO
        USE_OPENCL_BACKEND=$&lt;BOOL:${USE_OPENCL}&gt;
    )

    # 把 karino2 fork 的 CMakeLists 中列出的 .cpp 逐一加入。
    # 完整清单参见 ${KATAGO_SRC}/cpp/CMakeLists.txt，下面给出常用的最小集
    # （足够跑 OpenCL 推理 + GTP），按需补全：
    file(GLOB KATAGO_CPP
        ${KATAGO_SRC}/cpp/*.cpp
        ${KATAGO_SRC}/cpp/core/*.cpp
        ${KATAGO_SRC}/cpp/game/*.cpp
        ${KATAGO_SRC}/cpp/neuralnet/*.cpp
        ${KATAGO_SRC}/cpp/search/*.cpp
        ${KATAGO_SRC}/cpp/program/*.cpp
        ${KATAGO_SRC}/cpp/distributed/*.cpp
    )
    target_sources(katago PRIVATE ${KATAGO_CPP})

    # OpenCL：Android 端运行时 dlopen 加载 libOpenCL.so，不需要静态链接，
    # 只在编译期需要头文件（clblast 提供 OpenCL headers）。
    if(USE_OPENCL)
        target_compile_definitions(katago PRIVATE USE_OPENCL=1)
    endif()

    # KataGo 依赖：zlib、libzip。NDK 自带 zlib；libzip 需交叉编译后
    # 通过 add_library IMPORTED 引入，或用 NDK prebuilt 路径（见 chat 说明）。
    # 此处省略具体 find_package，按你的 libzip 安装位置补：
    # target_include_directories(katago PRIVATE /path/to/libzip/include)
    # target_link_libraries(katago /path/to/libzip/lib/libzip.a z)
endif()
