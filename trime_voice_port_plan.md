# BiBi-Keyboard ASR → trime（同文输入法）源码移植清单

> 目标：把 BiBi-Keyboard 的「本地 Qwen3-ASR 语音输入」能力源码级移植进 trime（同文），
> 达到图片效果：同文键盘内置「语音模型/识别服务与模型」设置页 + 长按空格（或语音键）本地录音识别上屏，无需切到 BiBi。
>
> 仓库：
> - BiBi：https://github.com/BryceWG/BiBi-Keyboard（Apache-2.0，根包 `com.brycewg.asrkb`）
> - trime：https://github.com/osfans/trime（GPL-3.0+，包 `com.osfans.trime`，namespace 已含 `com.osfans.trime`）
>
> 许可注意：BiBi 是 Apache-2.0（宽松，允许并入 GPL 项目并保留版权声明）；trime 是 GPL-3.0。
> 把 BiBi 代码移植进来后，trime 侧整体仍是 GPL，BiBi 的 Apache 头/版权声明需保留在对应文件里。

## 0. 现状与结论

- trime（develop）**目前没有任何语音/ASR 代码**（`app/build.gradle.kts` 无 sherpa-onnx、无录音依赖、无语音权限），
  所以图片里那个「识别服务与模型」页不是上游 trime 自带的，需要本次移植补上。
- BiBi 的 ASR 是**独立、可剥离的 Kotlin 模块**，集中在 `com.brycewg.asrkb` 下，
  本地识别走 `sherpa-onnx-2.0.1.aar`（`app/libs/`），核心本地类已在 BiBi 内按引擎抽象好，**不需要从 sherpa-onnx 原始项目重写**。

## 1. 从 BiBi 摘哪些（按子包，精确到类）

BiBi `asrkb` 包文件量：asr=119、ime=50、ui=200、store=38、imebridge=10、api=5、clipboard=19、translation=7、util=5。
不必整包搬（BiBi 的 `ime` 是它自己整套键盘，trime 已有键盘体系，不要覆盖）。

### 必须搬（本地 ASR 核心，最小子集）
- `asr/AsrEngine.kt` — 引擎接口契约（`Listener.onAmplitude` 等回调），所有引擎实现它。
- `asr/AsrEngineModeResolver.kt` / `AsrEngineInvocationMode.kt` — 本地/云端/流式模式判定。
- `asr/BaseSherpaOfflineRecognizerManager.kt` — **本地 sherpa-onnx 离线识别核心**（离线 PCM 识别入口）。
- `asr/SherpaReflectionSupport.kt` / `SherpaPunctuationManager.kt` — sherpa-onnx 反射调用 + 标点恢复。
- `asr/LocalAsrCallLogger.kt`、`LocalModelIntegrity.kt`、`LocalModelLoadCoordinator.kt`、
  `LocalModelPreload.kt`、`LocalModelPseudoStreamAsrEngine.kt`、`LocalOfflinePcmChunking.kt`、
  `LocalBackupResidencyManager.kt` — 本地模型完整性/加载/预载/驻留。
- `asr/AsrLocalModelCatalog.kt` — 本地模型目录（Qwen3-ASR 0.6B/1.7B 等条目、下载源、SHA-256 校验）。
- `asr/AsrLocalVendorLifecycle.kt`、`asr/AsrVendorRegistry.kt`、`asr/AsrVendor.kt`、`asr/AsrVendorAvailability.kt` — 供应商注册/生命周期。
- `asr/AudioCaptureManager.kt`、`AudioFrameSink.kt`、`AudioFrameSinkOwner.kt`、`AudioUtils.kt` — 录音采集/送帧。
- `asr/Recording*`（`RecordingAudioFocusController`、`RecordingDurationLimiter`、`RecordingKeepScreenOnController`）— 录音配套控制。
- `asr/Qwen3AsrFileAsrEngine.kt` — 你图里选的 Qwen3-ASR 引擎（文件/整段模式）。
- 可选：`asr/SenseVoice*`、`FunAsrNano*`、`Parakeet*`、`XAsr*`、`FireRedAsr*` 等其它本地引擎（按需）。

> 云端 18 引擎（Dashscope/OpenAI/Volc/Tencent/ElevenLabs/Gemini/Zhipu/Soniox…）**可先不搬**，
> 先只搬「本地 sherpa-onnx + 模型目录 + 模型下载/导入/清理」这条链，对应你截图 2/3 的全部内容。

### 模型下载/管理（对应截图里的「下载并自动安装模型 / 从本地导入 zip / 清理 / 识别线程数 / ITN」）
- `AsrLocalModelCatalog.kt`（模型清单 + 默认下载源「GitHub 镜像3」+ 断点续传）
- `LocalModelIntegrity.kt`（SHA-256 校验）、`LocalModelLoadCoordinator.kt`（线程数/卸载策略）
- 注意：BiBi 里「下载到应用内部目录 + 校验 + zip 导入」的 UI/网络层分散在 `ui` 与 `store`，
  移植时要一并拿对应的 `*Preferences`/`*Repository`（在 `store` 包，见第 2 节）。

### AIDL 对外接口（可选，仅当你也想让 trime 当「语音服务提供方」给别的 IME 用才要）
- `aidl/IExternalSpeechService.aidl`、`ISpeechCallback.aidl`、`SpeechConfig.aidl`
- `api/ExternalSpeechService.kt`、`api/ExternalSpeechSession.kt`、`api/AsrRecognitionService.kt`
- 纯「内置到同文键盘」不需要这层，先跳过。

## 2. trime 侧落点（往哪接）

trime `com.osfans.trime` 关键目录（已确认）：
- `app/build.gradle.kts` — **加依赖**：`implementation(files("libs/sherpa-onnx-2.0.1.aar"))`（把 BiBi 的 aar 拷进来，或换成 maven 坐标），
  加 `RECORD_AUDIO`/`FOCUS_AUDIO` 等权限（Manifest）。
- `core/`、`ime/core/TrimeInputMethodService.kt`、`ime/core/LifecycleInputMethodService.kt` — 生命周期/服务挂载点，
  初始化 ASR 引擎（加载模型、预热）挂在这里。
- `ime/keyboard/KeyBehavior.kt`、`KeyAction.kt`、`Key.kt` — **新增一个「语音键」行为/动作**
  （类似现有「切换/回车」键的写法），绑定「长按空格 → 录音 → 松手识别上屏」。
- `ime/core/InputDeviceManager.kt`、`ime/bar/QuickBarStateMachine.kt` — 处理长按空格手势触发的录音状态机。
- `ui/main/NavigationRoute.kt` + `ui/main/settings/` — **新增 `VoiceSettingsFragment`（识别服务与模型）**，
  挂进主导航；里面就是截图 2/3 的「启用语音输入 / 流式输出 / 识别服务商 / 本地模型目录 / 模型下载/导入/清理 / 线程数 / ITN」。
- `data/prefs/`、`data/db/` — 存识别服务商、模型路径、ITN 开关等配置（Kodein DI 注册）。
- 模型文件目录：trime 用 `files/rime/mie/语音模型`（截图里路径即此），对应 trime 的资源/文件管理惯例（`util/FileUtils.kt`）。

## 3. 落地步骤（可执行）

1. 把 BiBi `app/libs/sherpa-onnx-2.0.1.aar` 拷到 trime `app/libs/`，`build.gradle.kts` 加 `implementation(files(...))`。
2. 从 BiBi 摘第 1 节「必须搬」的类到 trime 新建包 `com.osfans.trime.asr`（改包名、保留 Apache 头）。
   去掉对 `com.brycewg.asrkb.*` 的引用，换成 trime 的 prefs/db（Kodein）。
3. Manifest 加权限：`RECORD_AUDIO`、`FOCUS_AUDIO`（如用）；`TrimeApplication.kt` 里按代码注册初始化。
4. 新增 `VoiceSettingsFragment`（设置页）+ 对应字符串/图标资源，挂进 `NavigationRoute` 与主菜单（截图 3 顶部那个键盘图标入口）。
5. `KeyBehavior`/`KeyAction` 加「语音」键；`QuickBarStateMachine` 里把「长按空格」映射为开始/停止录音；
   识别完走 trime 现有的 `commit` 上屏通道（替换/回填候选），不切输入法。
6. 模型下载/导入/清理逻辑接 `AsrLocalModelCatalog`（默认 GitHub 镜像 3 + 断点续传 + SHA-256 + zip 导入），
   存储路径写进 `files/rime/mie/语音模型`。
7. 编译验证 → 安装 → 同文键盘长按空格录音识别上屏 → 设置页切换 Qwen3-ASR 本地模型。

## 4. 主要风险 / 坑
- **包名与 DI**：BiBi 用自有 DI/Store，trime 用 Kodein + 自家 prefs/db，搬过来要替换全部数据存取（最大工作量）。
- **aar 体积**：sherpa-onnx aar 较大，加上 Qwen3-ASR 0.6B Int8 ≈ 806MB 模型，包与存储都会变重（与截图一致）。
- **本地推理性能**：Qwen3-ASR 0.6B 在手机上走 CPU，整段识别有延迟（截图「边说边出字」需开流式引擎，
  即多搬 `*PseudoStream`/`PushPcm` 引擎 + VAD；纯离线整段则走 `LocalModelPseudoStreamAsrEngine`）。
- **键盘高度/布局**：trime 键盘布局由 Rime yaml 驱动，新增「语音键」需在 `trime.yaml`/按键 scheme 加键，
  不只是 Kotlin 层——别漏了 Rime 侧键位定义。
- **许可证**：BiBi Apache-2.0 文件并入 GPL-3.0 的 trime 后整体随 GPL 走，须保留各文件顶部 Apache 版权声明。

## 5. 下一步（按需选）
- A：我直接把第 1 节「必须搬」的类从 BiBi 拉下来、改包名塞进 trime 的 `com.osfans.trime.asr`（自动完成步骤 1–2 的代码搬运）。
- B：先只做「本地 Qwen3-ASR + 模型管理 + 设置页」最小闭环（跳过云端 18 引擎），出可编译骨架。
- C：先不搬代码，我把 BiBi 关键类（`AsrEngine`/`BaseSherpaOfflineRecognizerManager`/`AsrLocalModelCatalog`）
  拉出来贴成接口契约文档，你审完再定。
