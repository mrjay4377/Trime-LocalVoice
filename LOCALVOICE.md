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

- `app/src/main/java/com/osfans/trime/ime/voice/`：本地离线 ASR 全套（离线整段 + 原生流式双引擎、sherpa-onnx 反射封装、录音、模型目录 / 下载 / 安装 / ZIP 导入、ITN）
- `LocalAsrSettingsFragment.kt`、`VoiceIndicatorUi.kt`：设置页与候选栏语音标注 / 音量波形 / 流式实时文字
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

## v30：原生流式离线识别（边说边出字）

之前所有模型都走 `OfflineRecognizer`：按住只累积 PCM，松手对整段音频推理一次。
v30 新增第 7 个引擎家族 `streaming_xasr`，走同一份 AAR 里现成的 `OnlineRecognizer`
（sherpa-onnx 2.0.1 的 `classes.jar` 已含 `OnlineRecognizer` / `OnlineStream` /
`OnlineRecognizerConfig` / `OnlineTransducerModelConfig` / `EndpointConfig`，**无需新增依赖或 so**）。

| | 流式引擎 | 其余 6 个引擎 |
| --- | --- | --- |
| 解码 | `acceptWaveform` 逐块增量解码 | 松手后整段一次推理 |
| 反馈 | 候选栏语音栏实时显示中间文字 | 松手后提示「正在本地识别」 |
| 上屏延迟 | 松手即出结果 | 取决于模型大小，可能数秒~数十秒 |
| 模型 | encoder / decoder / joiner / tokens（Transducer） | 各家离线模型，互不通用 |
| 代价 | 模型 590 MB（fp32 encoder），内存占用高 | SenseVoice int8 仅约 240 MB |

实现要点：

- `StreamingSherpaEngine.kt`：`Channel<ByteArray>` 把录音 PCM 投给单个 IO worker，串行调用
  `acceptWaveform` → `isReady/decode` → `getResult`，中间文本经 `AsrListener.onPartial` 送到
  `VoiceIndicatorUi.updatePartial()` 显示在语音栏；松手 `inputFinished()` 收尾并立即 `onFinal`。
- `SherpaOnlineManager`：会话间复用识别器（首次加载 590 MB 很慢），与离线缓存共用
  「跟随键盘」预加载 / 延迟卸载，切换引擎、删除模型、服务销毁时都会释放。
- `LocalVoiceController` 按 `VoiceModels.isStreaming()` 选引擎；简繁转换、离线翻译、
  WebDAV 同步排除 `voice/`、上屏重试等链路完全复用，未改动。
- `app/proguard-rules.pro` 原有 `-keep class com.k2fsa.sherpa.onnx.** { *; }` 已覆盖
  `Online*` 反射面，无需新增规则。

模型与安装：

- 一键下载：`sherpa-onnx-streaming-x-asr-480ms-zh-en.zip`（约 529 MiB，来自 BiBi-Keyboard
  `models` release），解压后为 `encoder-480ms.onnx`（592,968,361 B）、`decoder-480ms.onnx`、
  `joiner-480ms.onnx`、`tokens.txt`。上游未提供 SHA-256，按「同名 + 大小一致」校验。
- 也支持手动放置 / ZIP 导入：任意 sherpa-onnx streaming Transducer 模型，把
  `encoder*.onnx` / `decoder*.onnx` / `joiner*.onnx` / `tokens.txt` 放进
  `voice/streaming_xasr/` 下的任意子目录即可，`VoiceModels` 会按别名扫描。
- 设置 → 本地语音识别 → 识别引擎与模型 → 选「流式识别」；模型按「模型下载与管理」安装。
- 流式模型与离线模型**不通用**，切换引擎后需各自安装一次；`voice/` 仍不参与资料夹同步。

已知限制：X-ASR 480ms 包的 encoder 是 fp32，首次加载与内存占用明显高于 SenseVoice int8；
低端机上建议保留 SenseVoice 作默认引擎，仅在需要「边说边出字」时切到流式。
按住说话模式下不使用自动端点检测断句，松手始终是本次会话的结束点。

## v31：省电、删除稳定性、模型列表、减包与首字延迟

| 问题 | 处理 |
| --- | --- |
| 耗电 | 新增「省电模式」（默认开）：流式推理固定 1 核、说完静音 10 秒 / 全程无语音 20 秒自动结束录音、流式会话不再缓存整段 PCM（减少内存分配与 GC） |
| 删除模型后 APP 退出 | `SherpaOnlineManager` 加入会话计数与延迟释放，native 释放统一放 IO 线程；`releaseEngines()` 会等解码结束再删文件；设置页所有删除 / 清理改为后台进度对话框 + 全异常捕获 |
| 下载没有选择列表 / 看不到本地包 | 「下载并安装模型 / 本地语音包」始终打开完整选择列表：先列 voice 目录里真实存在的本地语音包（含 ZIP 导入、手动放置），再按引擎分组列出全部可下载规格；「识别引擎与模型」弹窗也新增本地包分组，可直接设为当前 |
| APK 体积 | 新增 `scripts/repack_apk.py`：以 deflate level 9 重打包（AAPT2 默认级别偏低）、zipalign 后用同一把 `~/.android/debug.keystore` 重签，签名证书不变，可直接覆盖安装。实测约省 11% |
| 流式首次加载慢 | 预热提前到 `onStartInput`（输入框获焦即开始，比键盘弹出早），并在设置页刷新 / 安装完成 / 切换配置后主动补预热；加载期间语音栏显示「正在加载…」，PCM 在 `Channel` 中缓冲，模型就绪后回放，开头语音不丢 |

### 减包脚本用法

```bash
export JAVA_HOME=<jdk17>
export ANDROID_HOME=<android-sdk>
APK=$(ls app/build/outputs/apk/debug/*arm64-v8a-debug.apk | head -1)
python scripts/repack_apk.py "$APK" ../Trime-LocalVoice-v31-arm64-v8a.apk
```

脚本会打印原始 / 精简体积并自动执行 `apksigner verify`。它只改 ZIP 压缩流，不改文件内容；
`resources.arsc` 等必须 STORED 的条目保持原样并由 zipalign 对齐。重签使用与 Gradle debug
相同的密钥（SHA-256 `629c7023…`），所以覆盖安装不会被系统拒绝。

### 仍待评估

官方 k2-fsa `asr-models` release 有更小的
`sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05.tar.bz2`
（约 128 MiB，int8，体积和内存约为现用 fp32 包的 1/4），可进一步缩短首次加载并降低耗电。
但官方只发 `tar.bz2`，需要让安装器支持 bzip2 + tar（引入 commons-compress）才能一键下载；
当前可先手动解压后放进 `voice/streaming_xasr/` 使用。
