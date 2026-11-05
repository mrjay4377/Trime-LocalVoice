#!/bin/bash
cd "C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/trime"
export JAVA_HOME="C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/toolchain/jdk17/jdk17"
export ANDROID_HOME="C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/toolchain/android-sdk"
export GRADLE_USER_HOME="C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/gradle-cache"
export http_proxy="http://127.0.0.1:49169"
export https_proxy="http://127.0.0.1:49169"
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=49169 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=49169"
GRADLE_BIN="$HOME/.gradle/wrapper/dists/gradle-9.8.1-bin/a2l6ltp3k1vth6lwipzm5p2jp/gradle-9.8.1/bin/gradle.bat"
exec "$GRADLE_BIN" :app:assembleDebug --no-daemon --console=plain
