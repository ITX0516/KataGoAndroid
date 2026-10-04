# JNI symbols：保留 native 方法和 C++ 入口符号
-keepclasseswithmembernames class * {
    native &lt;methods&gt;;
}
-keep class com.example.katago.** { *; }
