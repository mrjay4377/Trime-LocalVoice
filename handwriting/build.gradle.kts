/*
 * 手写输入独立模块：只放纯推理层、笔迹采集 View、模型文件管理与独立设置存储。
 * 刻意不依赖 app，也不依赖 ime.voice 里的任何类，避免 library 反向依赖 app 形成循环。
 *
 * ORT 使用官方 1.29.0 完整构建：classes.jar + libort1290.so + libonnxruntime4j_jni.so
 * 三者版本自洽。core 的 SONAME 与 JNI 桥的 DT_NEEDED 都改成了 libort1290.so，
 * 因为 app 侧 sherpa-onnx 的 JNI 需要 OrtGetApiBase@VERS_1.28.2，而这里需要
 * @VERS_1.29.0：两个 core 用不同 SONAME 共存，linker 按符号版本各自绑定。
 * 用 classes.jar 而不是完整 AAR 依赖，是为了避开 ORT AAR 的 minSdk=24 manifest 限制
 * （本 App minSdk=21）。
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osfans.trime.handwriting"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(files("libs/onnxruntime-1.29.0-classes.jar"))
    implementation(libs.timber)
}
