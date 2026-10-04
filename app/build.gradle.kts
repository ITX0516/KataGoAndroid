plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.katago"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.katago"
        minSdk = 24          // arm64 OpenCL/NNAPI 普遍可用
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-stub"

        // 仅 arm64-v8a（按确认的 ABI 范围）
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // ─── 构建模式开关 ───────────────────────────────────────
                // 默认 STUB 模式：不需要 KataGo 源码即可编译跑通。
                //
                // 接入真 KataGo（EIGEN CPU 后端，karino2 已验证路径）时，
                // 解开下面 3 行注释，把路径换成你本地的：
                //   arguments += "-DUSE_REAL_KATAGO=ON"
                //   arguments += "-DKATAGO_SRC=/path/to/KataGo"        // lightvector/KataGo 仓库根
                //   arguments += "-DEIGEN_SRC=/path/to/eigen-3.4.0"    // Eigen 头文件根
                //
                // 详见 BUILDING.md
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
