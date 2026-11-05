@echo off
set "JAVA_HOME=C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/toolchain/jdk17/jdk17"
set "ANDROID_SDK_ROOT=C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/toolchain/android-sdk"
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "http_proxy=http://127.0.0.1:49169"
set "https_proxy=http://127.0.0.1:49169"
set "JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=49169 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=49169"
for /L %%i in (1,1,30) do (echo y)
"%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%ANDROID_SDK_ROOT%" "ndk;30.0.16248370"
