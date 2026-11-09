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

本机（Windows）工作区里仍有一批**构建环境适配状态，刻意没有纳入提交**：

- `app/data/rime/*` 与 `app/src/main/jni/librime-lua-deps`：submodule 被本地编译脚本标成 dirty；提交这些指针会让仓库指向上游不存在的 commit。
- `app/src/main/assets/shared/*.yaml` 原本是 symlink，Windows `core.symlinks=false` 下被展开成普通文件。为保证异地 clone 可编译，v33–v41 那次备份已把它们以 `100644` 普通文件提交；以后合并 upstream 时这些路径会与上游 symlink 冲突，需手工处理。
- `.gradle-home/`、`compile_log.txt`、`run_compile.sh`、`app/src/main/jni/boost-*.tar.xz` 等本地构建缓存已写入 `.gitignore`，并于 2026-11-07 清理，不再产生未跟踪文件。

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
- 设置 → 本地语音识别 → 语音识别引擎 → 选「流式识别」；模型按「下载并安装模型 / 本地语音包」安装。
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

## v32：引擎列表精简、流式尾部丢字修复、下载源点选复制

| 问题 | 处理 |
| --- | --- |
| 「识别引擎与模型」改名并只显示已安装 | 标题改为「语音识别引擎」；弹窗只列 voice 目录里真实存在的本地语音包（手动放置 / ZIP 导入）和已下载安装的规格模型，不再混入未安装的可下载项与「自动选择」。没有模型时给出提示，并提供「下载模型」按钮直达选择列表 |
| 流式松手后最后几个字没识别到 | 根因是流式 Zipformer 按 chunk（X-ASR 为 480ms）消费特征，最后不足一个 chunk 的音频一直留在特征提取器里。`OnlineSession.finalizeInput()` 现在按 sherpa-onnx 官方示例补 0.6s 静音 padding → `decodeReady()` → `inputFinished()` → 再 `decodeReady()` 把尾帧解完；同时把 `enableEndpoint` 设为 false，避免端点判定重置已解码结果。另外 `stop()` 改为先关队列再改状态、`feed()` 不再用 `running` 做门闩，松手瞬间最后一块 PCM 不会被丢掉；保留最长中间结果作为空结果兜底 |
| 手动下载地址复制 | `ModelCatalog.sourceUrls()` 返回「源名称 → 完整地址」列表，手动下载的源列表用两行项展示，**点哪个源就复制哪个源的地址**（官方 / 3 个主镜像 / 2 个备用镜像），不再需要「复制当前源 / 复制官方」两个按钮 |

流式收尾顺序（与官方 `decode-file` 示例一致）：

```text
最后一块 PCM → acceptWaveform(0.6s 静音) → while isReady: decode
            → inputFinished()          → while isReady: decode → getResult()
```

## v33：流式出字改到文本框、加载时机统一、新增 Paraformer 流式引擎

| 改动 | 处理 |
| --- | --- |
| 流式中间文字在候选栏 | 改为直接写进目标输入框：`TrimeInputMethodService.updateVoiceComposing()` 用 `InputConnection.setComposingText()` 增量替换，边说边出字；松手后 `commitVoiceText()` → `commitText()` 自动把 composing 区替换成最终结果（简繁转换 / 离线翻译链路完全复用）。取消、识别失败、会话超时由 `cancelVoiceComposing()` 清掉未定稿的半句话。候选栏只保留麦克风 + RMS 波形 |
| 流式加载方式与其它引擎不一致 | 去掉 `VoiceLifecycle` 里流式专属的键盘预加载，以及「正在加载流式模型，语音会先缓冲」的专属提示；统一成按住语音键时按需加载，提示与其它引擎同为「正在加载本地模型，首次加载可能较慢…」。加载期间 PCM 继续排在 `Channel` 里，模型就绪后回放再逐块出字，开头语音不丢 |
| 下载地址没显示全 | 「模型下载地址」弹窗不再用 `setMessage + setAdapter`（长说明会把列表挤出可视区），改为把说明放进列表首行 Header，官方 / 3 个主镜像 / 2 个备用镜像共 6 条**完整地址**全部逐条多行显示，点某条即复制该条地址。「模型下载源」切换弹窗也恢复显示每个源对示例模型的完整地址 |
| 增加流式本地引擎 | 查 BiBi-Keyboard `models` release 找到两套本分支还没用上的流式 ZIP：`sherpa-onnx-streaming-paraformer-bilingual-zh-en.zip`、`...trilingual-zh-cantonese-en.zip`；AAR 里已有 `OnlineParaformerModelConfig`（字段 `encoder` / `decoder`）与 `OnlineModelConfig.paraformer`，**无需新增依赖或 so**。新增第 9 个引擎家族 `streaming_paraformer`，`StreamingSherpaEngine.buildConfig()` 按家族构造 Transducer 或 Paraformer 配置（Paraformer 分支按 AAR 官方示例显式写 `modelType="paraformer"`，否则 native 会按默认架构去找 joiner），两条家族共用同一套录音 / 解码 / 生命周期链路 |
| 文案 | 设置项「下载并安装模型 / 本地语音包」改为「下载并安装模型」，弹窗标题同步 |
| GitHub 备份 | 本轮按要求暂停，不执行 `commit` / `push`；源码改动只留在本地工作区 |

### 新增的两套流式模型（可一键下载安装）

包内同时含 fp32 与 int8，安装器只复制 int8 三件套到 `voice/streaming_paraformer/<规格>/`，
安装后约 230 MB；ZIP 本身约 1 GB，文件名与解压大小取自 release 包中央目录实测：

| 规格 | ZIP | tokens.txt | encoder.int8.onnx | decoder.int8.onnx |
| --- | --- | --- | --- | --- |
| Paraformer 中英 | `...-bilingual-zh-en.zip` | 75,756 B | 165,462,184 B | 71,664,561 B |
| Paraformer 中粤英 | `...-trilingual-zh-cantonese-en.zip` | 81,289 B | 166,362,800 B | 72,062,549 B |

release 里还有一个 `sherpa-onnx-qnn-x-asr-streaming-zipformer-transducer-...-android-aarch64.zip`
（约 542 MiB），需要高通 QNN NPU 与额外 `qnn-libs`，只适用于特定芯片，暂不纳入通用 CPU 分发。
手动放置 / ZIP 导入同样支持：`voice/streaming_paraformer/` 下放 `encoder*.onnx` /
`decoder*.onnx` / `tokens.txt` 即可，`VoiceModels` 会按别名扫描。

## v34：缩短首次部署耗时

首次部署是三段串行工作：

1. `UserDataManager.sync()` 双向镜像使用者资料夹（SAF 逐个文件查属性 / SHA-256 / 复制）；
2. `DataManager.sync()` 把 11MB 预设数据从 assets 复制到共享目录；
3. librime `start_maintenance(true)` 编译 `luna_pinyin` / `stroke` 等词典。

第 3 步在 `build/` 为空时无法跳过，是 librime 固有成本；之后有编译缓存只做增量重建，
所以只有第一次慢。本轮把前两步里重复、无用的 I/O 去掉，并让等待过程有明确反馈。

| 改动 | 处理 |
| --- | --- |
| 编译产物被反复同步 | `UserDataManager` 把 `build/` 与 `voice/` 一起排除出资料夹双向镜像（本地遍历、远端收集、历史 manifest 三处都过滤）。词典 `.table.bin` / `.prism.bin` 有几十 MB、与 librime 版本绑定，镜像只会让每次部署多做一轮 hash + SAF 复制，同步回旧产物还会污染新版本工作区；历史 manifest 里的旧条目一并忽略，避免被误判成「本地删除」 |
| 覆盖安装后全量重写预设数据 | `DataManager.copyFile()` 复制前先比对目标文件的 SHA-256 与 `checksums.json` 记录值，一致就跳过写入并保留原 mtime。`checksums.json` 丢失 / 损坏时 diff 会把全部文件判成 `CreateFile`，重写会刷新所有 `.yaml` 的 mtime，让 librime 的 `DetectModifications` 误以为工作区全变了 |
| checksums 写一半被杀 | `checksums.json` 改用 `AtomicFile` 原子写，中断不会留下损坏文件触发下一次全量复制 |
| 不知道卡在哪一步 | 部署通知增加阶段文字：「正在同步使用者资料夹…」→「首次部署：正在编译词库，可能需要几分钟…」/「正在部署：重新编译有变更的方案…」，简 / 繁 / 英文三套文案 |
| 首次编译超过 5 分钟误报失败 | `Rime.deploy()` 的等待上限从 5 分钟放宽到 10 分钟（`syncUserData()` 保持 5 分钟） |
| GitHub 备份 | 继续暂停，本轮不 `commit` / 不 `push` |

若还要进一步压缩第 3 步，只能减少首次需要编译的数据（例如移除未被方案引用的
`essay.txt` 5.7 MB 和笔画词典 `stroke.dict.yaml` 2.2 MB，代价是失去笔画输入与八股文词库），
或把编译好的 `build/*.bin` 预置进 APK（需要一台已部署过的真机导出）。两者都会改变默认功能
或引入版本兼容风险，本轮未做。

## v35：精简预设词库、WebDAV 目录核查、OpenCC 打包确认

| 项目 | 结论 / 处理 |
| --- | --- |
| 删除 `essay.txt` 与笔画词库 | 按用户确认移除 `shared/essay.txt`(5.7 MB)、`shared/stroke.dict.yaml`(2.2 MB)、`shared/stroke.schema.yaml`。`luna_pinyin.dict.yaml` 的 `use_preset_vocabulary` 改为 false（librime 的 `PresetVocabulary` 默认词库名就是 `essay`，留 true 会去找已删除的文件）；`luna_pinyin.schema.yaml` 移除 stroke 依赖、`reverse_lookup_translator`、`reverse_lookup` 段、识别模式与 `grammar:/hant?` 补丁；`default.yaml` 的 `schema_list` 只保留随包提供的 3 个方案（原来另列了 4 个本包根本没有的方案，用户自定义 `default.custom.yaml` 时会报 missing input schema）。**代价：失去笔画输入与八股文预设词频** |
| 部署安全性 | `ConfigNeedsUpdate` 按源文件 mtime 判断，改过的 yaml 会触发重新生成 build 配置；`checksums.json` 由 gradle 重新生成（54 条），覆盖安装时 `DataManager` 会把设备上旧的三份文件删掉。设备上残留的 `build/stroke.*.bin` 不会被引用，需要清理可在资料夹里手动删。若之前在方案列表里手动勾选过「笔画」，请取消勾选 |
| WebDAV 是否新建文件夹 | **是**：`WebDavPrefs.remoteDir` 默认 `trime`，`WebDavClient.ensureRoot()` 会对 `地址/trime/` 发 MKCOL，在服务器上新建同名目录；`ensureDir()` 上传文件时还会在远端逐级建子目录；本地则由 `download()` 的 `mkdirs()` 按远端目录结构建父目录。另外 `OpenCCDictManager` 以前会在使用者资料夹无条件建一个空的 `rime/opencc/`（与 WebDAV 无关，但同步时会被看到）。修正：`ensureRoot()` 改为「远端目录留空就完全不 MKCOL；填了目录先 PROPFIND Depth:0 探测，只有不存在才创建」，设置项 hint 与失败排查文案写明留空＝不新建；`OpenCCDictManager.userDir` 改为导入词典时才创建 |
| APK 是否打包 OpenCC 文件夹 | **是，且必需，保留**：`assets/shared/opencc/` 37 个文件、原始 1,325,096 B（压缩后约 0.55 MB），由 gradle `installOpenCCData` 从 `librime/deps/opencc/data` 生成，供 librime 的 `simplifier`（`s2t.json` / `t2tw.json` 等）与 `OpenCCDictManager` 使用。另有 `assets/voice/zh_convert.txt`(682,884 B) 是语音输出简繁转换的另一套数据，两者用途不同，不是重复打包 |
| 体积 | `assets/shared` 11 MB → 2.6 MB；APK 25,310,711 B → **22,041,880 B**（−3,268,831 B，约 3.1 MB），首次部署少编译两个大词库 |
| GitHub 备份 | 继续暂停，本轮不 `commit` / 不 `push` |

⚠️ 无真机连接，删词库后的部署未做实机验证；若首次部署报「missing input schema」，
检查使用者资料夹里的 `default.custom.yaml` 是否还手动列着 stroke / bopomofo 等方案。

## v36：修复 WebDAV 递归列目录 404（backgrounds/backgrounds）

用户截图报错「读取远端目录 backgrounds/backgrounds 失败：HTTP 404」。这**不是** v35
「远端目录留空不新建文件夹」改错了，也不是删词库引起，而是 `WebDavClient` 自 v22 起就有的
路径解析缺陷被触发：PROPFIND Depth:1 的响应里，坚果云会把**当前集合自身**也作为一个
`<d:response>` 返回；旧 `childName()` 始终拿全局 `root`（`.../dav/trime/`）去减前缀，递归进
`backgrounds/` 时自身 href `/dav/trime/backgrounds/` 被算成子项 `backgrounds`，于是拼出
`backgrounds/backgrounds` 再去 PROPFIND，必然 404。同一缺陷在「远端目录留空」时表现为
二级目录文件被整批跳过（child 含 `/` 直接丢弃）。

| 改动 | 处理 |
| --- | --- |
| 基于错误的集合解析 | `list(relative)` 把本次请求的集合 URL 传给 `parsePropfind()`；`childName()` 改为以该集合 path 为基准，同时尝试以集合和以 root 作为相对 href 的解析 base |
| 集合自身误判 | 任一解析结果等于当前集合 path 就跳过；只接受直接子项，深层路径不再混入当前层 |
| 文件名含 `+` | 解码前把 `+` 还原成 `%2B`，避免 `URLDecoder` 把真名里的 `+` 变成空格（上传用 `%20`） |
| 单目录失败拖垮整批 | `WebDavSync.listRemote()` 现在把子目录读取失败记入 `Result.failed`（显示「同步完成（部分失败）」）并继续其它目录；只有根目录读不到才整体抛错，这时才该检查地址 / 账号 / 权限 |
| 体积与签名 | 22,041,880 B 不变，证书 `629c7023…` 一致，可直接覆盖 v35；GitHub 备份继续暂停 |

装 v36 后直接点「立即同步」即可，无需改地址或清空已同步的目录。

## v37：WebDAV 网络异常加固（14 项 DNS 失败）

用户第二张截图是「WebDAV 同步完成（部分失败）」，14 项全部为
`Unable to resolve host "dav.jianguoyun.com": No address associated with hostname`。
**这不是又一处路径 bug，反而证明 v36 生效了**：远端 `symbols.yaml`、`wubi86.dict.yaml` 等
14 个文件已被正确列出并进入下载 / 上传阶段（PROPFIND 已通），失败发生在随后 GET / PUT 时
设备解析不到坚果云域名 —— 属于手机侧 DNS / 网络问题。但客户端缺重试与清晰提示，本轮补上。

| 改动 | 处理 |
| --- | --- |
| 一次 DNS 抖动毁掉整批 | `WebDavClient.request()` 对 `UnknownHostException` / `InterruptedIOException` 及连接重置、网络不可达等瞬时 `IOException` 最多重试 2 次（400 / 800 ms 退避）。OkHttp 的 `retryOnConnectionFailure` 在域名解析返回空地址时不会重试，所以原来一次抖动就让十几个文件连挂。只重试 `IOException`，HTTP 4xx/5xx 仍按状态码正常处理；每次重试重建 Request / RequestBody，`fileBody` 每次重新开流，PUT 重发安全 |
| 14 条相同错误淹没根因 | 部分失败弹窗把网络类失败聚合：标题改「WebDAV 同步失败：网络异常」，显示失败项数、原始错误、受影响文件与可操作建议（切 Wi-Fi / 移动数据、开关飞行模式刷新 DNS）；其它类型失败仍逐条列出。整体失败弹窗的排查建议也补上 DNS 条目 |
| 状态栏误报「刚刚同步」 | `WebDavSync` 有失败项时不再写 `lastSyncTime`，`lastSyncResult` 前缀标「未完成 · 」，避免显示成一次成功同步 |

客户端能自愈偶发抖动，但**无法代替可用网络**。若重试后仍是 `Unable to resolve host`，
需在手机上处理：确认该网络能打开网页、切换 Wi-Fi / 移动数据、开关一次飞行模式刷新 DNS，
或换一个 DNS；地址保持 `https://dav.jianguoyun.com/dav`，账号用邮箱 + 网页端生成的应用密码。

体积 22,041,880 B 不变，证书 `629c7023…` 一致可覆盖 v36；GitHub 备份继续暂停。

## v38：解决「跳过 90 个文件」、SYNC 文件夹与重装恢复

用户重新安装后本地 rime 目录为空，同步结果是「上传 16 · 下载 13 · 跳过 90」，配置没恢复完整，
同时用户目录里多出一个 `sync/` 文件夹。

**90 个跳过的成因**：`WebDavSync` 只在两边都有同名文件时才可能跳过；坚果云 PROPFIND 返回的
`getlastmodified` 有部分格式（或干脆不返回该属性）解析不出时间，`remoteTime` 变成 0，于是走进
「时间未知 → 保守跳过」。另外 librime 的 `sync/` 快照目录此前没被排除，快照文件也混进了
这批两边同名的条目，把数字放大。这些文件并非「已同步」，只是没被处理。

**SYNC 文件夹不是 WebDAV 建的**：`deployer.cc` 的 `sync_dir("sync")` +
`user_data_sync_dir() = sync/<installation_id>/` 是 librime 的用户词典备份目录，存放
`*.userdb.txt` 与配置备份。真正触发它出现的是 `startWebDavSync()` 在同步成功后调用的
`syncUserData()` —— 它内部跑 `RimeSyncUserData()`，v37 首次同步成功时就把这个目录建了出来。

| 改动 | 处理 |
| --- | --- |
| 时间解析太窄 | `parseTime()` 依次尝试 RFC 1123、ISO 8601（含毫秒/时区）、`yyyy-MM-dd HH:mm:ss`、epoch 秒/毫秒，并关掉 `SimpleDateFormat` 的宽松解析；仍解析不出的才计为时间未知 |
| 跳过原因看不出来 | `Result` 增加 `skippedNoTime`，状态栏显示「跳过 90（其中 N 项服务器未提供修改时间）」，不再让人误以为同步完成 |
| 重装后恢复不完整 | 设置里新增「从服务器恢复（覆盖本地同名文件）」，带确认弹窗；`WebDavSync.sync(preferRemote = true)` 对所有远端文件强制下载覆盖，且**不上传**本机重装后的预设文件，避免污染服务器备份 |
| `sync/` 参与同步 | `EXCLUDED_DIRS` 加入 `sync`；顺带把真正的文件名 `.sync-manifest.json` 排除（原来写的 `sync_manifest` 前缀匹配不到它） |
| WebDAV 每次都新建 `sync/` | 同步成功后由 `syncUserData()`（导出用户词典快照）改为 `updateConfig()`（重新加载配置），不再触发 librime 的快照导出。已存在的 `sync/` 是 Rime 自带备份，可手动删除；后台定时同步若开启仍会按 Rime 原逻辑生成 |

⚠️ **重要**：截图里「上传 16」很可能是重装后 APP 重新部署的预设文件被推到了服务器。若远端原本
有同名的自定义配置，可能已被覆盖 —— 建议先去坚果云检查这几个文件的修改时间 / 内容，再点
「从服务器恢复」，否则恢复回来的仍是预设。恢复完成后建议重新部署一次让 Rime 载入配置。

体积 22,045,976 B（+4,096 B），证书 `629c7023…` 一致可覆盖 v37；GitHub 备份继续暂停。

## v39：修复「从服务器恢复」点了没反应

用户反馈点「从服务器恢复」后没有任何反应。排查后不是网络问题，而是**入口被开关挡住 +
结果不可见 + 恢复后没生效**三件事叠在一起：

| 成因 | 处理 |
| --- | --- |
| 恢复条目被置灰 | `updateWebDavUi()` 原来用 `prefs.enabled && isConfigured()` 控制 `isEnabled`。重装后「启用 WebDAV 同步」默认是关的，条目变灰，点击完全无响应；`WebDavSync.sync()` 里的 `require(prefs.enabled)` 也会直接抛错。现在手动「立即同步 / 从服务器恢复」都不再依赖这个开关，它只管 `BackgroundSyncWork` 的后台定时同步；恢复条目恒可点，配置没填时给明确弹窗而非静默 |
| 点了看不到动作 | 点击后立刻 Toast「从服务器恢复 开始…」，状态栏实时显示进度（`(n/N) 文件名` + 完整远端地址）；进度回调从 IO 线程投递到主线程，并用 `AtomicBoolean` 防止迟到进度覆盖最终状态 |
| 结果一闪而过 | 成功也改用弹窗：显示远端地址、服务器上可恢复文件数、下载覆盖数、本地独有保留数、失败数。失败弹窗标题区分「从服务器恢复失败」并补上目录/开关排查项 |
| 远端目录指错＝下载 0 个 | `WebDavClient` 新增 `rootUrl()` / `topLevelNames()`；`WebDavSync.Result` 增加 `remoteFiles` / `remoteRootUrl` / `remoteTopLevel`。远端一个文件都扫不到时弹「服务器上没有找到可恢复的文件」，列出该地址下的一级内容，提示把「远端目录」改成备份所在的子目录名；这种情形也不再刷新「上次同步」时间，结果标「未完成」 |
| 文件回来了但配置没生效 | 恢复成功且有下载时自动 `rime.runOnReady { deploy() }` 全量重新部署（yaml / 词库需要重新编译），不再只 `updateConfig()`。普通同步仍只 `updateConfig()`，避免每次全量编译；`deploy()` 不调 `syncUserData()`，不会再生成 `sync/` 目录 |

装 v39 后不需要打开「启用 WebDAV 同步」开关，只要地址 / 账号 / 应用密码填对，
直接点「从服务器恢复」→ 确定，就能看到进度和结果弹窗。若弹「服务器上没有找到可恢复的文件」，
按弹窗列出的目录名把「远端目录」改对再试一次。

体积 22,050,072 B（+4,096 B），sha 前缀 `4324d6c0`，证书 `629c7023…` 一致可覆盖 v38；
`apksigner` v1/v2/v3 通过。GitHub 备份继续暂停，改动仍留在工作树未提交。
未实机验证：真机上的 PROPFIND 响应与坚果云目录结构。

## v40：恢复「同步成功但少了一部分文件」

用户装 v39 后反馈：WebDAV 上 trime 目录里的文件恢复到手机时漏了一部分，界面却报成功。
排查到两个真实成因，并补上逐项核对清单：

| 成因 | 处理 |
| --- | --- |
| href 编码不一致，整批子目录被丢弃 | `WebDavClient.childName()` 原先拿**编码后**的 `collection.path` 与 href 做 `startsWith`。上传用 `%20` / `%E4%B8%AD`，服务器 href 可能返回已解码的空格或中文，于是整个子目录被判成「不属于本集合」而静默丢掉。新增 `normalizePath()`：去掉 query/fragment、统一 URL 解码、去尾部斜杠；集合 base 与三种 href 候选（集合相对、root 相对、href 本身）都在归一化后比较 |
| 目录被误判成文件 | 部分服务器不给 `<d:collection/>`，但 href 以 `/` 结尾；原来会把它当文件去 GET，目录下的文件全部漏掉。`parsePropfind()` 现在把「href 以 / 结尾」也视为集合；`MAX_DEPTH` 8 → 16 |
| 恢复后自动部署把文件对齐掉 | `deploy()` 内部会跑 `UserDataManager.sync()`；在「使用者资料夹 / 外部同步」模式下，外部目录与本地做双向对齐，可能覆盖或删除刚恢复的文件。取消自动部署，改为结果弹窗里的「重新部署并关闭」按钮，由用户主动触发，并在弹窗里说明该风险 |
| 排除规则吞掉用户资料 | 恢复模式放宽 `sync/`、`cache/`、`.git/`、`.gradle/`：只排除 `voice/`（模型）和 `build/`（编译缓存）。`sync/<id>/` 里的 `*.userdb.txt` 是 librime 用户词典快照，重装后本就该一起拉回来；普通同步仍按老规则排除 |
| 少文件却报成功 | 恢复结束重新扫本地，逐项比对远端清单，`Result.missingLocal` 列出「服务器有、本地没有」的文件；`describe()` 追加「未恢复 N」，状态栏标「未完成」。结果弹窗显示 PROPFIND 原始 response 数、解析出条目数、可恢复文件数、按规则跳过清单、未恢复清单与文件路径示例，并提供「复制核对清单」按钮（可发回定位）。若示例路径都落在同一子目录，会提示把「远端目录」改成该目录名 |

装 v40 后再点一次「从服务器恢复」，看结果弹窗：
- 「核对通过」＝扫描到的远端文件都已写入本地；
- 「从服务器恢复不完整」＝把清单复制出来，看缺失路径是不是含中文 / 空格 / 特殊符号，或是否在被跳过的清单里；
- 文件路径示例若全是 `trime/xxx`，说明「远端目录」多指了一层，改成 `trime` 再恢复。

体积 22,054,168 B（+4,096 B），sha 前缀 `b582a418`，证书 `629c7023…` 一致可覆盖 v39；
`apksigner` v1/v2/v3 通过。GitHub 备份继续暂停，改动仍留在工作树未提交。

## v41：本地语音设置页精简文案与收紧间距

| 项目 | 改动 |
| --- | --- |
| 模型状态 | 未检测到模型时只显示「未检测到完整模型」；`status.ready` 时才显示「已检测到：<模型名称>」（优先取 `ModelVariant.displayName`，退回到已安装目录名）。去掉「· 家族名」「路径 · 点击查看详情」和「点击一键下载推荐模型」。点击该项仍可查看详情 / 触发推荐下载 |
| 语音识别引擎 | 没有可用模型时显示「当前：空」，检测到才显示模型名称；去掉「· 自动选择」「（未安装）」和「点击选择已安装的语音模型」 |
| 其余摘要 | 推理线程数、简繁转换、翻译语言包、下载源、模型列表、删除模型的第二行说明与「点击…」提示全部去掉，摘要统一压成一行 |
| 条目间距 | 新增 `onViewCreated()`，用 `RecyclerView.OnChildAttachStateChangeListener` 在 item 绑定后把普通设置项的上下内边距收到 4dp、最小高度置 0；分组标题（无 `android.R.id.summary`）保持原留白，避免标题贴住上一组。配合摘要单行化，整页明显更紧凑 |

体积 22,054,168 B（与 v40 相同，ZIP 对齐巧合；`classes.dex` 9,269,172 → 9,269,668 B），
sha 前缀 `dbbc76d7`，证书 `629c7023…` 一致可覆盖 v40；`apksigner` v1/v2/v3 通过。
截至 v41 的全部改动已于 2026-11-07 提交 `7f0f5c0b` 并推送 `origin/localvoice`。

## v42：voice 目录跟随「使用者资料夹」

| 项目 | 改动 |
| --- | --- |
| 默认位置 | `VoiceModels.candidateRoots()` 首选「使用者资料夹 / voice」，不再固定在应用私有 `Android/data/<包名>/files/rime/voice` |
| SAF 解析 | 新增 `UserDataManager.externalRimeDirAsFile()`：把 `externalRimeTreeUri` 的 tree document id（如 `primary:Documents/rime`）还原为 `/storage/emulated/0/Documents/rime`；外置 SD 卡走 `/storage/<volume>/`；解析失败返回 null |
| 可写探测 | `probeWritable()` 在 voice 里写临时探针并读回，确认 native 引擎能按真实路径打开模型；结果按路径缓存，避免每次状态检查都做磁盘 IO |
| 回退与兼容 | 未选资料夹 / APP_STORAGE / 目录不可读写时自动回退到应用私有目录；`candidateRoots()` 保留全部历史位置，`status()` 与 `installedModels()` 都扫描所有位置，旧模型不会消失（列表里标「旧位置」） |
| 文案 | `README.txt`、`installed.txt`、安装完成弹窗、设置页「模型状态」详情同步更新，并显示当前是跟随资料夹还是已回退 |
| 同步 | voice 仍不参与使用者资料夹镜像与 WebDAV 同步（既有过滤规则不变） |

体积 22,054,168 B，sha 前缀 `171b9d3b`，`classes.dex` 9,269,668 → 9,273,220 B，
证书 `629c7023…` 一致可覆盖 v41；`apksigner` v1/v2/v3 通过。
v42 改动仍留在工作树未提交（最新提交仍是 `7f0f5c0b`）。

## v43：release 优化构建（修复「部署慢 / 笔画卡」）

| 项目 | 改动 |
| --- | --- |
| 根因 | 此前 v18–v42 全部是 **debug 包**：merge manifest 带 `android:debuggable="true"`，且 native 层编译命令只有 `-g`、无任何 `-O`，整库 librime 以 `-O0` 运行。官方原版同文是 release 包（`-O3 -DNDEBUG` + 不可调试 + AOT），所以同一套方案在官方不卡、在我们分支卡 |
| 优化等级 | `app/src/main/jni/CMakeLists.txt` 对所有**非 Debug** 变体（Release / RelWithDebInfo / MinSizeRel）强制 `-O3 -DNDEBUG`。NDK r30 的 toolchain 会把 `CMAKE_*_FLAGS_RELEASE` 置空，且 AGP 的 release 变体实际走 `RelWithDebInfo`，故三种类型全部兜底 |
| PNG | `app/build.gradle.kts` release 块加 `isCrunchPngs = false`，绕过本地 AAPT2 对第三方 AAR（含 sherpa-onnx）PNG/9-patch 资源编译失败 |
| ABI | `BUILD_ABI=arm64-v8a` 只编一个 ABI |

**核验**：relwithdebinfo 的 `build.ninja` 中 **343 条编译命令全部含 `-O3`，0 条 `-O2/-O0`**；merge manifest 无 `debuggable`（release 默认 false）。
交付 `Trime-LocalVoice-v43-arm64-v8a.apk` = `release-unsigned` 经 `repack_apk.py` 重压 + zipalign + 同一 debug key 重签。
体积 18,987,362 B（release + R8 比 debug 小 ~3.2 MB），`librime_jni.so` 5.8 MB（`-O3` 无调试符号），
证书 `629c7023…` 一致可覆盖 v42；`apksigner` v1/v2/v3 通过。

> 注：`app/src/main/jni/boost` 目录由 `cmake/Boost.cmake` 从 `boost-1.92.0.tar.xz` 解压得来（68084 条目）；
> Git Bash 的 `tar` 缺 `xz`、且 Windows MAX_PATH 会截断超长测试路径，已用 Python（`\\?\` 前缀）手动解压到 `jni/boost/`。
> 该目录未入库、非构建必需缓存，合并上游或清理时勿误删。v43 改动同样未提交（最新提交仍 `7f0f5c0b`）。

## v44：语音目录回归私有目录 + 手动导出备份（含安装取消崩溃修复）

| 项目 | 改动 |
| --- | --- |
| 崩溃修复 | `LocalAsrSettingsFragment` 安装取消分支原用 `main.post { toast(ctx, getString(...)) }`，Fragment 已 detach 时 `this.getString()` → `requireContext()` 抛 `not attached to a context` 直接崩进程。改为 `ctx.getString(...)`；`onStage` 进度回调与 `refresh()` 入口加 `isAdded` 守卫。提交 `239c2c65` |
| 目录结论 | v42 的「语音目录跟随使用者资料夹」在 Android 11+ 不可行：SAF 授权只对 `ContentResolver`/`DocumentFile` 生效，`java.io.File` 与 native `fopen` 读写公共目录会被 scoped storage 拒绝（本包 targetSdk=37）。v41 及更早语音一直固定在应用私有目录 `Android/data/<包名>/files/rime/voice`，所以「以前能用」= 私有目录能用 |
| VoiceModels | 移除 `externalVoiceRoot()` / `probeWritable()` 首选逻辑，`primaryRoot` 固定为 `DataManager.userDataDir/voice`；`isUsingUserDataFolder()` 改为 `canExportToUserDataFolder()`（判断 `hasExternalAccess()`），仅用于导出入口可用性 |
| UserDataManager | 新增 `externalRimeDirUri()` 暴露 SAF tree Uri（导出备份必须走 SAF 写，不能用还原的真实路径） |
| ModelInstaller | 新增 `exportToExternal()`：递归用 `StorageAccess.mkdirp` + `pasteLocalFile` 把已安装模型复制到 `使用者目录/voice/<相对路径>/`，返回复制字节数 |
| 设置页 | 「模型」分组新增「导出模型到使用者目录」入口；列出已安装模型，选中后进度对话框 + IO 协程复制；识别仍读私有目录 |
| 文案 | `readmeText()` / `installed.txt` 说明改为「固定私有目录 + 手动导出备份」 |

**交付**：`Trime-LocalVoice-v44-arm64-v8a.apk` = release（`-O3` + R8）经 `repack_apk.py` 重签，18,991,458 B，证书 `629c7023…`，无 debuggable，`targetSdk 37`，可覆盖 v43。

> 构建注意：本次用默认 `~/.gradle`（含完整的 gradle-9.8.1 分发）；`gradle-cache/wrapper/dists` 下只有 4.5 MB 残缺 `.part`，设 `GRADLE_USER_HOME=gradle-cache` 会每次卡在下载 gradle。

## v45：模型住用户目录（native 可直读）+ 识别中常驻 + 设置页重排（对齐咩版）

| 项目 | 改动 |
| --- | --- |
| 需求背景 | 回归 / 对齐 trime-mie（咩版）：① 模型直接住使用者资料夹、sherpa-onnx native 按真实路径 `fopen` 直读；② 松手后保留语音态 + 常驻「识别中」提示，直到 `onFinal` 上屏才 `cleanup`，且识别过程中可取消；③ 设置页重排成咩版风格（状态 / 语音输入 / 模型 分区清晰） |
| VoiceModels | 重新加入 `hasAllFilesAccess()`（`R` 以下或 `Environment.isExternalStorageManager()`）、`probeWritable()`、`externalVoiceRoot()`（开启用户目录 + 持有所有文件权限 + 已选资料夹 + 写探测通过才返回）、`isUsingUserDataFolder()`；`candidateRoots()` 首选 `externalVoiceRoot`，否则回退私有目录等旧逻辑 |
| Manifest | 新增 `android.permission.MANAGE_EXTERNAL_STORAGE`（R+ 必需，native 才能按路径读写使用者资料夹） |
| LocalVoiceSettings | 新增 `useUserDataDir`（默认 true，对应开关「模型存放位置：用户目录」） |
| LocalVoiceController | 新增 `isRecognizing`（`finishing && !finished`）、`onRecognizingChanged`、`publishRecognizing()`；`finish()` 松手后 `publishRecording(false)` + `publishRecognizing(true)` 保留语音态（删原「正在本地识别」Toast）；`cleanup()` 增加 `publishRecognizing(false)`；`cancel()` 去掉「识别中不可取消」限制，识别中亦可中止 |
| InputBarDelegate | `setVoiceRecording` 改为驱动 `updateVoicePage()`；新增 `setVoiceRecognizing()`（非录音时显示「识别中…」）、`updateVoicePage()`（录音或识别中任一为真则常驻语音页）；`switchUiByState` 守卫从仅 `voiceRecording` 扩展到 `voiceRecording || voiceRecognizing`，防止识别中被候选更新抢走页面 |
| CommonKeyboardActionListener | 懒加载绑定 `onRecognizingChanged`；`onRelease` 区分 `isRecognizing`→`cancel()` / 否则 `finish()`；`handleLocalVoiceInput` 识别中再次触发走 `cancel()` |
| 设置页 | `LocalAsrSettingsFragment` 重排为「当前状态 / 语音输入（启用 + 麦克风 + 模型存放位置：用户目录开关）/ 识别服务与模型 + 模型（下载/导入/导出/迁移/删除/清理）/ 识别选项」；用户目录开关开启且未授权时引导去 `Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`；新增「迁移已有模型到用户目录」按钮（复制私有目录旧模型到使用者资料夹）；`refresh()` 展示当前落点与迁移可用性 |
| ModelInstaller | `exportToExternal()` 修复相对路径（模型不在主目录时改用 `model.dir.name` 作为子路径）；新增 `migrateToExternal()` |

**说明**：v44 的结论「SAF 授权无法让 native 直读用户目录」成立，但漏算了 `MANAGE_EXTERNAL_STORAGE` 这条路——持有该权限后 `File` / `fopen` 即可读写使用者资料夹。方案 B 即走此路；未授权 / 未选资料夹时自动回退私有目录，识别行为不变。

## v46：资料儲存模式补齐「直接使用外部文件夹」（对齐咩版 配置>儲存）

| 项目 | 改动 |
| --- | --- |
| 需求背景 | 咩版「配置 > 儲存 > 資料儲存模式」是三选一单选（直接使用外部文件夹 / 從外部存储同步 / 应用专用存储）；本支原只有后两项，缺第一项。补上 `EXTERNAL_DIRECT`。 |
| DataStorageMode | 新增 `EXTERNAL_DIRECT(R.string.use_external_folder_directly)`，放在枚举首位，列表顺序与咩版一致。 |
| UserDataManager | `userDataDir` 改为按模式解析：EXTERNAL_DIRECT 且已持有「所有文件访问」权限时，返回 `externalRimeDirAsFile()` 的真实路径（native 直读）；否则回退私有目录；新增 `isExternalDirect()` / `hasAllFilesAccess()`；`externalRimeDirAsFile()` 解除「仅 EXTERNAL_SYNC」限制也支持 DIRECT；`isStorageChoiceDone` / `isStorageAvailable` / `sync()` 均纳入 DIRECT（DIRECT 不做双向镜像）。 |
| DataManager | `userDataDir` 改为委托 `UserDataManager.userDataDir`，使主题 / 语音 / WebDAV 等全部跟随存储模式，避免与 rime 根目录不一致。 |
| VoiceModels | `externalVoiceRoot()` 增加「全局 EXTERNAL_DIRECT 也算开启」分支，模型跟随使用者资料夹。 |
| ProfileSettingsFragment | 存储模式 `ListPreference` 自动列出三项；DIRECT 下「资料目录」行可点选；选中 DIRECT 时若无资料夹则弹窗选目录、若未授权则跳系统设置申请「所有文件访问权限」；选完目录后自动申请权限，授权后自动重新部署。 |
| SetupFragment + 布局 | 初始设置页 RadioGroup 增加「直接使用外部文件夹」选项（含描述），映射 `R.id.external_direct_option`。 |
| 字符串 | 新增 `use_external_folder_directly` / `use_external_folder_directly_desc` / `external_direct_select_folder_message` / `external_direct_needs_all_files`。 |

## 构建状态（2026-11-08，本地成功出包）

- 工具链位置：`…/2026-11-02-19-43-31/toolchain/{jdk17/jdk17, android-sdk}`（JDK 17.0.20.1 Temurin、Android SDK platform-37 / build-tools 37.0.0 / NDK 30、Gradle 9.8.1 分发缓存在 `~/.gradle`）。
- 命令：`export JAVA_HOME=…/toolchain/jdk17/jdk17; export ANDROID_HOME=…/toolchain/android-sdk; unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY; ./gradlew :app:assembleRelease --no-daemon`（`gradle.properties` 已设 `buildABI=arm64-v8a`）。
- release 未配 keystore → 产出 **unsigned** APK，再用 `python scripts/repack_apk.py <unsigned> <signed>` 以 `~/.android/debug.keystore`（alias `androiddebugkey` / pwd `android`）zipalign + 重签，apksigner verify 通过（v1/v2/v3）。
- 本次构建修复一处编译错误：`ProfileSettingsFragment.allFilesLauncher` 回调内 `runOnReady{deploy()}` 是 suspend，须包进 `lifecycleScope.launch`（提交 `bbacb12c`）。
- 产出 APK：`app/build/outputs/apk/release/com.osfans.trime-c5179423-arm64-v8a-release-signed.apk`（约 18.1 MB，含 v45+v46）。
- 注：`MANAGE_EXTERNAL_STORAGE` 在 `targetSdk 37` 下上架 Play 受限，仅适合侧载 / F-Droid / 自用。

**说明**：此模式即 v45 方案 B 的「通用化」——之前 v45 只在语音设置里用 `useUserDataDir` 开关表达，现在收归到全局资料儲存模式，与咩版一致；语音模型目录（`candidateRoots` 首选 `externalVoiceRoot`）随之落在外部资料夹 `/voice`，sherpa-onnx 按路径直读。DIRECT 与 EXTERNAL_SYNC 的区别：前者按真实路径原地读写，后者与 SAF 资料夹做双向镜像。

## v48：voice 下多套引擎可切换 + 简繁「跟随输入法开关」修复

| 问题 | 根因 | 处理 |
| --- | --- | --- |
| 目录里放了好几套引擎，只能识别 / 选中一个 | ① `ModelInstaller.localPackages` 按文件组推断家族，而 sensevoice / firered_asr / telespeech 的必需文件完全相同，`firstOrNull` 永远返回第一个家族；② 选中项只存 `familyId + 目录名`，识别时拼成 `voice/<family>/<目录名>`，手动放的目录不在这层就解析失败，回退到自动扫描的第一套；③ `VoiceModels.attempt()` 把整棵目录树的文件合成一张索引，多套模型会跨目录拼凑或只命中第一套 | 新增 `VoiceModels.inferFamilyByName()`（官方包名特征词：qwen3 / funasr / paraformer / x-asr / parakeet / firered / telespeech / sensevoice）与 `inferFamilyByFiles()`；新增 `LocalVoiceSettings.selectedModelDir` 记住选中模型的**绝对目录**，`status()` 最高优先级按它解析；`attempt()` 改为「直属文件优先，再逐个单独尝试子目录」，绝不跨目录混用；引擎列表用绝对路径做选项值并列出每一套本地包 |
| 目录名不含特征词时引擎推断可能不对 | 文件组相同就无从区分 | 「本地语音包」操作菜单新增「更换识别引擎类型」，可手动指定 8 个家族之一；菜单里显示当前推断的引擎名 |
| 简繁转换选「跟随输入法简/繁开关」没生效 | `TrimeInputMethodService.isTraditionalMode` 只认 `simplification` / `traditional`，且要求它们出现在当前方案 `switches` 里。预设 `luna_pinyin_simp` 用的是 `zh_simp`，`luna_pinyin_fluency` 没有 switches，主题里的简/繁键又可能 toggle 任意 option → 恒返回 false，跟随档永远输出简体 | `KeyAction.traditionalWhenToggleOn()` 从主题 `states` 顺序读出「option=true 是简还是繁」；`handleSwitchCharset` 把键盘简/繁键实际使用的 option 名与语义记进 `LocalVoiceSettings`；`isTraditionalMode` 优先读这个 option（最贴合用户看到的开关），其次读方案确实声明过的常见简/繁 option（`zh_simp`/`zh_hans`/`zh_hant`/`s2t`…），最后用 native `statusCached.isTraditional` 兜底；不再每次 `currentSchema()` 重新打开配置，改用 `schemaCached`；上屏日志打出 `mode` 与 `traditional` 便于诊断 |

**构建**：release（`-O3` + R8）→ `repack_apk.py` 重压 + 同一 debug key 重签，18,995,554 B，`apksigner` v1/v2/v3 通过，可覆盖 v47。
交付：`app/build/outputs/apk/release/com.osfans.trime-v48-arm64-v8a-release-signed.apk`。
按「暂停备份」要求，本轮未 commit / 未 push，改动留在工作树。


