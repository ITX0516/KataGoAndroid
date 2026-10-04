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
#
# 真实模式（EIGEN CPU 后端，karino2 已验证路径）：
#   在 app/build.gradle.kts 的 cmake 块加：
#     arguments += "-DUSE_REAL_KATAGO=ON"
#     arguments += "-DKATAGO_SRC=/abs/path/to/KataGo"      # 上游 lightvector/KataGo 克隆
#     arguments += "-DEIGEN_SRC=/abs/path/to/eigen-3.4.0"  # Eigen 头文件根目录
#   （KATAGO_SRC 指向 lightvector/KataGo 仓库根目录，包含 cpp/ 和 external/）
# ───────────────────────────────────────────────────────────────
option(USE_REAL_KATAGO "Link against real KataGo (EIGEN CPU backend)" OFF)

# 永远编译的本地源：JNI 桥 + 引擎封装
add_library(katago SHARED
    jni_bridge.cpp
    katago_engine.cpp
)

# Android 系统库
find_library(log-lib log)   # android/log.h
find_library(dl-lib  dl)     # dlopen / dlsym（保留给将来 OpenCL/NNAPI 路径用）
target_link_libraries(katago ${log-lib} ${dl-lib})

if(USE_REAL_KATAGO)
    if(NOT KATAGO_SRC)
        message(FATAL_ERROR "USE_REAL_KATAGO=ON 但未提供 KATAGO_SRC（指向 lightvector/KataGo 仓库根）")
    endif()
    if(NOT EIGEN_SRC)
        message(FATAL_ERROR "USE_REAL_KATAGO=ON 但未提供 EIGEN_SRC（指向 eigen-3.4.0 解压目录）")
    endif()

    # ─── KataGo 源文件（按 karino2 已验证的最小集） ───────────────
    # 排除 distributed/（在线训练，APK 不需要）
    # 排除 tests/（单元测试）
    # 排除 command/selfplay.cpp、command/contribute.cpp、command/match.cpp、command/gatekeeper.cpp
    #   （APK 只用 gtp 子命令；这些会拖入 libzip/OpenSSL 依赖）
    # 排除 neuralnet/cudabackend.cpp 等 GPU 后端（USE_EIGEN_BACKEND 时编译开关已屏蔽，保留无害）
    file(GLOB KATAGO_CORE    ${KATAGO_SRC}/cpp/core/*.cpp)
    file(GLOB KATAGO_GAME    ${KATAGO_SRC}/cpp/game/*.cpp)
    file(GLOB KATAGO_NN      ${KATAGO_SRC}/cpp/neuralnet/*.cpp)
    file(GLOB KATAGO_SEARCH  ${KATAGO_SRC}/cpp/search/*.cpp)
    file(GLOB KATAGO_PROGRAM ${KATAGO_SRC}/cpp/program/*.cpp)
    file(GLOB KATAGO_DATAIO  ${KATAGO_SRC}/cpp/dataio/*.cpp)
    file(GLOB KATAGO_BOOK    ${KATAGO_SRC}/cpp/book/*.cpp)
    set(KATAGO_MAIN_CPP ${KATAGO_SRC}/cpp/main.cpp)

    target_sources(katago PRIVATE
        ${KATAGO_MAIN_CPP}
        ${KATAGO_CORE}
        ${KATAGO_GAME}
        ${KATAGO_NN}
        ${KATAGO_SEARCH}
        ${KATAGO_PROGRAM}
        ${KATAGO_DATAIO}
        ${KATAGO_BOOK}
        # 不加 command/*.cpp（GTP 命令处理我们自己在 katago_engine.cpp 里做）
        # 不加 distributed/*.cpp（在线训练，APK 不需要）
        # 不加 tests/*.cpp
    )

    # ─── 头文件搜索路径 ─────────────────────────────────────────
    target_include_directories(katago PRIVATE
        ${KATAGO_SRC}/cpp
        ${KATAGO_SRC}/cpp/external
        ${KATAGO_SRC}/cpp/external/nlohmann_json
        ${KATAGO_SRC}/cpp/external/half-2.2.0/include
        ${KATAGO_SRC}/cpp/external/filesystem-1.5.8/include
        ${KATAGO_SRC}/cpp/external/tclap-1.2.5/include
        ${EIGEN_SRC}
        ${CMAKE_CURRENT_BINARY_DIR}  # program/gitinfo.h 生成位置
    )

    # ─── 编译宏（karino2 已验证的 3 处改动）─────────────────────
    target_compile_definitions(katago PRIVATE
        USE_REAL_KATAGO
        USE_EIGEN_BACKEND          # EIGEN CPU 后端
        NO_LIBZIP                  # 不用 libzip（selfplay 才需要）
        NO_GIT_REVISION            # 不查 git 信息
        COMPILE_MAX_BOARD_LEN=50   # 支持到 19x19 即可
        BYTE_ORDER=1234            # Android NDK 没定义 BYTE_ORDER，必须手动加
        LITTLE_ENDIAN=1234
        BIG_ENDIAN=4321
    )

    # ─── zlib：NDK 自带 ────────────────────────────────────────
    find_library(z-lib z)
    target_link_libraries(katago ${z-lib})

    # ─── Threads ────────────────────────────────────────────────
    # Android NDK 的 libc 已内置 pthread，不需要单独链接。
    # 非 Android 环境（如本地测试）才链接 pthread。
    if(ANDROID)
        set(threads-lib "")
    else()
        find_library(threads-lib pthread)
    endif()
    if(threads-lib)
        target_link_libraries(katago ${threads-lib})
    endif()

    # ─── 生成 program/gitinfo.h（KataGo 编译期需要）─────────────
    # 上游用 add_custom_command 生成，这里手动写一个最小版本
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/program/gitinfo.h
        "#pragma once\n"
        "namespace KataGoIO {\n"
        "  inline const char* getGitRevision() { return \"android-build\"; }\n"
        "  inline const char* getGitVersion()  { return \"1.18.2-android\"; }\n"
        "  inline const char* getGitDate()     { return \"2026-10\"; }\n"
        "}\n"
    )
endif()
