#!/bin/bash
cd "C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/trime"
export JAVA_HOME="C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/toolchain/jdk17/jdk17"
export ANDROID_HOME="C:/Users/Administrator/WorkBuddy/2026-11-02-19-43-31/toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=64963 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=64963"
echo "=== start: $(date) ==="
sh ./gradlew :app:compileDebugKotlin --no-daemon --console=plain
echo "=== exit: $? at $(date) ==="
