#!/usr/bin/env bash
set -e
WS="C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31"
JDK="$WS/toolchain/jdk17/jdk-17.0.20+1"
SDK="$WS/toolchain/android-sdk"
export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$JDK/bin:$SDK/cmdline-tools/latest/bin:$PATH"
# accept licenses
yes | sdkmanager --licenses > /dev/null 2>&1 || true
# install platform 37 + build-tools (AGP 9.4.1 needs build-tools 35+)
sdkmanager "platforms;android-37" "build-tools;37.0.0" "platform-tools" 2>&1 | tail -3
cd "$WS/trime"
# point sdk.dir
echo "sdk.dir=$SDK" > local.properties
./gradlew --no-daemon :app:assembleDebug 2>&1 | tail -60
