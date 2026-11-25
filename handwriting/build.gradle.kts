/*
 * 手写输入独立模块：只放纯推理层、笔迹采集 View、模型文件管理与独立设置存储。
 * 刻意不依赖 app，也不依赖 ime.voice 里的任何类，避免 library 反向依赖 app 形成循环。
 *
 * ORT 复用 app 侧提供的官方 1.28.0 完整版 libonnxruntime.so（含全部算子，
 * C API VERS_1.28.0），不再打第二份 core：手写模块只保留 1.28.0 的 classes.jar 与 JNI 桥。
 * JNI 桥与 core 同为官方 1.28.0，版本需求 VERS_1.28.0 原生匹配，无需 patch。
 * 这样 APK 只需一份 ORT core，省去约 30MB（libort1290.so）。
 * 注意：sherpa-onnx AAR 原带的精简 core 缺算子会导致手写推理返回空候选，
 * 已在 AAR 内替换为官方 1.28.0 完整 core（sherpa JNI 版本字符串同步 patch 为 VERS_1.28.0）。
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
    implementation(files("libs/onnxruntime-1.28.0-classes.jar"))
    implementation(libs.timber)
}
