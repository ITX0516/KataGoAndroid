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
                // ─── 构建模式开关（通过 Gradle 属性切换）─────────────────
                // 支持两种调用方式：
                //   1) 本地编辑：解开下面 3 行注释，把路径换成你本地的
                //   2) CI/命令行：gradle assembleDebug -PkatagoMode=eigen \
                //                   -PkatagoSrc=/path/to/KataGo \
                //                   -PeigenSrc=/path/to/eigen-3.4.0
                //
                // 默认 STUB 模式：不需要 KataGo 源码即可编译跑通。
                // 详见 BUILDING.md
                val katagoMode = properties["katagoMode"]?.toString() ?: "stub"
                if (katagoMode == "eigen") {
                    val katagoSrc = properties["katagoSrc"]?.toString()
                        ?: error("katagoMode=eigen requires -PkatagoSrc=/path/to/KataGo")
                    val eigenSrc = properties["eigenSrc"]?.toString()
                        ?: "$katagoSrc/cpp/external/eigen-3.4.0"
                    arguments += "-DUSE_REAL_KATAGO=ON"
                    arguments += "-DKATAGO_SRC=$katagoSrc"
                    arguments += "-DEIGEN_SRC=$eigenSrc"
                    logger.lifecycle("KataGo EIGEN mode: KATAGO_SRC=$katagoSrc")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ─── 签名配置 ───────────────────────────────────────────────
    // CI 统一用 release keystore 签名（debug 和 release 都用同一个，
    // 这样所有 CI 出的 APK 可以直接覆盖安装）。
    // 本地开发时若未设置环境变量，自动回退到 Android 默认 debug 签名。
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            val ksPass = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")
            if (ksPath != null && ksPass != null && keyAlias != null && keyPass != null
                && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                this.keyAlias = keyAlias
                keyPassword = keyPass
                logger.lifecycle("[signing] using release keystore: $ksPath")
            } else {
                logger.warn("[signing] release keystore env vars not set, using default debug signing")
            }
        }
    }

    buildTypes {
        debug {
            // 统一签名：debug 也用 release keystore（若可用）
            signingConfig = signingConfigs.getByName("release").takeIf {
                System.getenv("KEYSTORE_PATH") != null
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.appcompat:appcompat:1.6.1")
}
