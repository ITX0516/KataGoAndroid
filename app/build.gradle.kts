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
                // 默认 STUB 模式：不需要 KataGo 源码即可编译跑通。
                // 接入真 KataGo 时改为：
                //   arguments += "-DUSE_REAL_KATAGO=ON"
                //   arguments += "-DKATAGO_SRC=/path/to/karino2/KataGo"
                //   arguments += "-DUSE_OPENCL=ON"
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
