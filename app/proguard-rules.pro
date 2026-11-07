# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class com.osfans.trime.core.** { *; }

# 本地离线语音：OfflineSherpaEngine / StreamingSherpaEngine 通过 Class.forName +
# getDeclaredField/getMethod 反射访问 sherpa-onnx 的 Offline* 与 Online* 类，
# R8 静态分析看不到引用，必须整体保留（classes.jar 仅 ~0.23 MiB）。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.osfans.trime.ime.voice.** { *; }

# kotlinx.serialization / kaml 通过注解与生成的 serializer 工作
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# remove kotlin null checks
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
    static void checkReturnedValueIsNotNull(...);
    static void checkFieldIsNotNull(...);
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Foxlet Translate 0.5.0 以 classes.jar 引入，AAR 内的 proguard.txt 不会自动生效。
# native 层通过 JNI 反向查找 NativeBridge / JniRuntime 的方法，类名与成员名必须保留。
-keep class io.github.yinvoker.foxlet.NativeBridge { *; }
-keep class io.github.yinvoker.foxlet.JniRuntime { *; }
-dontwarn io.github.yinvoker.foxlet.**
