# Trime LocalVoice 分支说明

`localvoice` 是 **osfans/trime 的标准 fork 分支**：带完整上游历史（约 3100 个提交），
与上游存在共同祖先，可以直接 `git merge` / `git rebase` 来做内核或主体代码更新。

## 基线

| 项目 | 值 |
| --- | --- |
| 上游 | `https://github.com/osfans/trime.git`（remote 名 `upstream`） |
| 分支 | `develop` |
| 本分支基线 | `57feda7007d2d2b7e71ecc99eaa38d8a4592a293`（2026-10-29） |
| 上游当前 HEAD | `0f7bc782 build: upgrade XXPermissions to 28.3`（拉取时） |
| 本仓 | `https://github.com/mrjay4377/Trime-LocalVoice.git`（remote 名 `origin`） |
| 功能分支 | `localvoice` |

`git merge-base localvoice upstream/develop` = `57feda7`，即三方合并的基准点。
上游 `develop` 已比基线新，随时可以合并（见下文）。

仓库的 `main` 分支是早期的**源码快照 + v21 APK 存档**，没有上游历史，**不要用它来合并上游**；
请始终使用 `localvoice`。建议在 GitHub 仓库设置里把默认分支改成 `localvoice`。

## 本分支改动范围

- `app/src/main/java/com/osfans/trime/ime/voice/`：本地离线 ASR 全套（sherpa-onnx 反射封装、录音、模型目录 / 下载 / 安装 / ZIP 导入、ITN）
- `LocalAsrSettingsFragment.kt`、`VoiceIndicatorUi.kt`：设置页与候选栏语音标注 / 音量波形
- `KeyboardActionListener` / `KeyView` / `CommonKeyboardActionListener` / `InputBarDelegate` 链路：按住录音、松手结束
- `UserDataManager.kt`：**把 `voice/` 排除出 Rime 双向同步**（否则大模型会被同步拖慢或误删）
- `AndroidManifest.xml`（`RECORD_AUDIO` / `INTERNET`）、`proguard-rules.pro`（反射 keep）、三组 `strings.xml`、`ic_voice_mic.xml`
- `app/build.gradle.kts`、`gradle.properties`、`gradle-wrapper.properties`：debug 开启 R8 / 资源收缩、只打 arm64、应用名用正式名、排除多余 sherpa so

语音模型固定放在应用私有目录：`/sdcard/Android/data/com.osfans.trime.debug/files/rime/voice/`，
不随「使用者资料夹」设置变化。

## 在另一台电脑上继续开发

```bash
git clone -b localvoice https://github.com/mrjay4377/Trime-LocalVoice.git trime
cd trime
git remote add upstream https://github.com/osfans/trime.git
git submodule update --init --recursive
```

还需手动补齐**未入库的第三方依赖**（体积过大或属第三方源码，构建时重新获取）：

- `app/libs/sherpa-onnx-2.0.1.aar` —— `app/build.gradle.kts` 直接引用，缺了无法编译
- `app/src/main/jni/boost/`、`app/src/main/jni/librime*/`、`app/src/main/jni/snappy/` —— 由 submodule / 官方构建脚本获取
- `boost-1.92.0.tar.xz`、Android SDK / NDK / JDK、Gradle 9.8.1

编译：

```bash
./gradlew :app:assembleDebug
```

默认 `buildABI=arm64-v8a`；需要多架构用 `-PbuildABI=arm64-v8a,armeabi-v7a,x86,x86_64` 覆盖。

## Trime 主体代码更新（内核/上游合并）

```bash
git fetch upstream
git merge upstream/develop          # 或 git rebase upstream/develop
# 解决冲突：只会出现在我们改过的 Kotlin / XML / gradle 文件上
git push origin localvoice
```

因为存在共同祖先，三方合并可正常工作；冲突面只限我们改过的约 27 个文件。
合并后建议重新编译 `:app:assembleDebug` 验证。

## 只更新 librime native 内核

```bash
cd app/src/main/jni/librime
git fetch --tags && git checkout <new-tag>
cd ../../../../..
git add app/src/main/jni/librime
git commit -m "chore: bump librime"
git push origin localvoice
```

这种情况 Java/Kotlin 层基本不冲突，只需重新编译 native。

## 网络注意（Windows 代理环境）

若 `fetch` / `push` 报 `CONNECT tunnel failed, response 502`，是本地代理与 HTTP/2 协商不兼容，
不是 GitHub 被墙。改用 HTTP/1.1 即可：

```bash
git -c http.version=HTTP/1.1 fetch upstream
# 或一次性写入本机配置（配置不会随 clone 传递）
git config --local http.version HTTP/1.1
```

另外不要把失效的代理端口写死在仓库配置里；让 git 使用当前会话的 `https_proxy` 环境变量。

## 不要提交的本地改动

本机（Windows）工作区里还有一批**构建环境适配改动，刻意没有纳入提交**：

- `app/data/rime/*`、`app/src/main/assets/shared/*`：clone 时 `core.symlinks=false` 导致 symlink 被实体化成普通文件（约 60 万行）
- `app/src/main/jni/librime-lua-deps`：submodule 内部 dirty
- `.gradle-home/`、`compile_log.txt`、`run_compile.sh`、`app/src/main/jni/boost-*.tar.xz`

它们只影响本机编译，提交会污染 fork 并在合并上游时造成大面积冲突。Windows 上建议开启开发者模式后
`git config core.symlinks true` 并重新 checkout，让 symlink 恢复正常。

## 二进制依赖（v29 起随仓库提供，异地 clone 可直接编译）

| 文件 | 用途 | 来源 / 许可 |
| --- | --- | --- |
| `app/libs/sherpa-onnx-2.0.1.aar` | 本地 ASR 推理引擎 | k2-fsa / BiBi-Keyboard，Apache-2.0 |
| `app/libs/foxlet-v0.5.0.aar`、`app/libs/foxlet-0.5.0-classes.jar` | 离线翻译 SDK | yinvoke/foxlet-translate，MIT |
| `app/src/main/jniLibs/arm64-v8a/libfoxlet.so`、`libc++_shared.so` | 翻译引擎 native 库（仅 arm64） | 同上 |

宿主 `minSdk = 21`，而 Foxlet AAR 声明 `minSdk 28`，因此**不使用 AAR 依赖**，只取 `classes.jar` + arm64 `.so`；
运行时由 `OfflineTranslator.isAvailable()` 判断 Android 9+ 且存在 native 库才启用，老设备自动关闭翻译选项。
`app/proguard-rules.pro` 末尾的 `NativeBridge` / `JniRuntime` keep 规则必须保留，否则 R8 会破坏 JNI 反射面。

翻译模型**不随仓库分发**：Mozilla Firefox Translations 模型（MPL-2.0）由 Foxlet 在应用内下载到
`noBackupFilesDir/translation-models`，首次使用需联网，之后翻译全程离线。中→英约 52 MiB；
中文到其它语种走 `zh-Hans → en → 目标语` pivot，需要两套语言包。
