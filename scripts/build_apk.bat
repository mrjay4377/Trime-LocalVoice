@echo off
setlocal
set WS=C:\Users\Administrator\WorkBuddy\2026-11-02-19-43-31
set JAVA_HOME=%WS%\toolchain\jdk17\jdk17
set ANDROID_HOME=%WS%\toolchain\android-sdk
set ANDROID_SDK_ROOT=%WS%\toolchain\android-sdk
set PATH=%JAVA_HOME%\bin;%PATH%
rem Java 不读 http_proxy 环境变量；显式传给 JVM，确保 AGP/Gradle 经 127.0.0.1:49169 代理访问 google maven。
set "JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=49169 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=49169"
set "GRADLE_OPTS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=49169 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=49169"
cd /d "%WS%\trime"
gradlew :app:assembleDebug
