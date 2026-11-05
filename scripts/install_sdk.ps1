$WS = "C:\Users\Administrator\WorkBuddy\2026-11-02-19-43-31"
$env:JAVA_HOME = "$WS\toolchain\jdk17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$WS\toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = "$WS\toolchain\android-sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
$sdk = "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"
& $sdk --licenses | Out-Null
& $sdk "platforms;android-37" "build-tools;37.0.0" "platform-tools" 2>&1 | Select-Object -Last 20
