# BiBi 本地 ASR → trime 移植：核心接口契约文档（选项 C）

> 从 BiBi-Keyboard（Apache-2.0）拉取的关键类型定义，用于移植前审查。
> 所有类型都是 `internal`（BiBi 内部可见），移植进 trime 时去掉 `internal` 限定即可对外暴露。

## 1. 引擎接口层（AsrEngine.kt）

### AsrEngine — 引擎基础契约
```kotlin
interface AsrEngine {
    val isRunning: Boolean
    fun start()   // 开始识别
    fun stop()    // 停止识别
}
```

### StreamingAsrEngine — 流式引擎（边录边出字）
```kotlin
interface StreamingAsrEngine : AsrEngine {
    interface Listener {
        fun onFinal(text: String)     // 最终结果 → 进 trime 上屏通道
        fun onError(message: String)
        fun onPartial(text: String)   // 中间结果（可选）
        fun onStopped()              // 录音阶段结束（松手/静音判停）
        fun onAmplitude(amplitude: Float)  // 0.0–1.0 振幅，驱动波形动画
    }
}
```

**→ trime 侧用法**：键盘「语音键」按下时 `engine.start()`，在 `Listener.onFinal` 里把文本 commit 进 Rime 候选/上屏；`onAmplitude` 接 KeyView 上的波形；`onStopped` 恢复麦克风按钮态。

### BackupAwareAsrEngine — 主备引擎（本地兜底，可选）
```kotlin
internal interface BackupAwareAsrEngine : StreamingAsrEngine {
    val primaryVendor: AsrVendor
    val backupVendor: AsrVendor
    val backupStrategy: AsrParallelEngineDecision
    fun wasLastResultFromBackup(): Boolean
}
```
本地 Qwen3-ASR 做「主引擎」时，云端 18 引擎做「backupVendor」；纯本地可先不实现这层。

---

## 2. 本地识别核心（BaseSherpaOfflineRecognizerManager.kt）

sherpa-onnx 离线识别器骨架。每个具体模型（Qwen3/SenseVoice/Parakeet…）各写一个 `*OnnxManager` 继承它。

**抽象成员（子类要实现）**：
```kotlin
protected abstract val tag: String
protected abstract fun initClasses()                 // 类加载/so 初始化
protected abstract fun buildRecognizerConfig(config: Any): Any  // 模型配置
protected abstract fun createRecognizer(assetManager: AssetManager?, cfg: Any): Any  // 建 recognizer
protected abstract fun offlineRecognizerClass(): Class<*>
```

**对外方法**：
```kotlin
fun unload()            // 卸载（可延时 keepAliveMs）
fun isPrepared()        // recognizer 是否已就绪
fun isPreparing()       // 是否正在加载
protected suspend fun ensurePreparedLocked(assetManager, config, onLoadStart, onLoadDone): ReflectiveRecognizer?
```

**内部状态**（`@Volatile`）：`cachedRecognizer / cachedConfig / preparing / unloadJob`，配 `Mutex` 做线程安全 + 旧 recognizer 自动释放。

**→ 关键依赖**：`ReflectiveRecognizer`（sherpa-onnx 的反射包装，见第 4 节）和 `sherpaScheduleAutoUnload` / `sherpaInvokeCallbackSafely` 两个工具函数（在 `SherpaReflectionSupport.kt`，尚未拉取，但契约已清晰）。

---

## 3. 模型目录（AsrLocalModelCatalog.kt + AsrLocalVendorLifecycle.kt）

### AsrVendor — 供应商枚举
```kotlin
enum class AsrVendor(val id: String) {
    Volc, SiliconFlow, ElevenLabs, OpenAI, OpenRouter, DashScope, Gemini, Soniox, StepAudio, Zhipu, Cohere,
    SenseVoice, FunAsrNano, Qwen3Asr,   // ← 本地 7 引擎
    Parakeet, GigaAm, FireRedAsr, XAsr,
    MiMo, Tencent
}
```

### AsrLocalVendorLifecycle — 每个本地供应商的生命周期
```kotlin
internal interface AsrLocalVendorLifecycle {
    val vendor: AsrVendor
    fun preload(request: AsrLocalVendorPreloadRequest)
    fun unload()
    fun isPrepared(): Boolean
    fun isReady(): Boolean
    fun modelStatus(context: Context, prefs: Prefs): LocalModelCheck<*>   // 模型是否就绪
    fun isModelReady(context: Context, prefs: Prefs): Boolean
}
```
注册表 `AsrLocalVendorLifecycleRegistry` / `AsrLocalVendorLifecycles`（object）把 7 个本地 vendor 各绑一个 `*OnnxManager`，
Qwen3-ASR 走 `qwen3AsrLifecycle()` → `Qwen3AsrOnnxManager`。

### AsrLocalModelCatalog — 目录（运行时共享）
```kotlin
internal object AsrLocalModelCatalog {
    fun all(): List<AsrLocalModelCatalogEntry>
    fun entryFor(vendor: AsrVendor): AsrLocalModelCatalogEntry?
    fun missingModelErrorRes(vendor: AsrVendor): Int?     // 缺模型时 toast 用的字符串资源
    fun modelStatus(context, prefs, vendor): LocalModelCheck<*>?
    fun isModelReady(context, prefs, vendor): Boolean
    fun unload(vendor: AsrVendor): Boolean
}
```

**→ 这就是截图 2/3 那个"识别服务与模型"页的数据源**：`all()` 列出本地模型，`isModelReady` 判模型装没装，`missingModelErrorRes` 给出"请下载/导入模型"的提示。

---

## 4. 悬空引用（移植时必须一并补上）

| 类型 | 说明 |
|---|---|
| `ReflectiveRecognizer` | sherpa-onnx 反射包装（含 `release()`），`BaseSherpaOfflineRecognizerManager` 直接依赖；文件在 `asr/` 下但 raw 拉取为空（可能路径变），需重新定位 |
| `LocalModelCheck<T>` | 模型状态 sealed（`Ready` 等），`AsrLocalModelCatalog.modelStatus` 返回它 |
| `AsrLocalVendorLifecycles` / `AsrLocalVendorLifecycleRegistry` | 已在第 3 节文件内，可直接拿 |
| `AsrVendorRegistry` | `AsrVendor.fromId` 依赖，把 id→vendor |
| `AsrParallelEngineDecision` | `BackupAwareAsrEngine` 用到 |
| `store.Prefs` | BiBi 自有数据层，**移植成 trime 的 Kodein prefs**（最大替换点） |
| `R.string.*` | 字符串资源，**移植到 trime 的 `res/values`** |

---

## 5. 审查后给你 3 个落地选项

1. **最小闭环（推荐先做）**：只搬 `AsrEngine` + `StreamingAsrEngine` + `BaseSherpaOfflineRecognizerManager`
   + `AsrLocalVendorLifecycle`（只留 Qwen3-ASR 一个 vendor）+ `AsrLocalModelCatalog`，
   补上 `ReflectiveRecognizer`/`LocalModelCheck`/`Prefs→Kodein`，配 sherpa-onnx aar + `RECORD_AUDIO` 权限。
   出来就能在 trime 键盘长按空格跑本地 Qwen3-ASR，设置页显示"模型已安装/未安装 + 下载/导入"。

2. **完整本地多引擎**：上面 7 个本地 vendor（SenseVoice/FunAsrNano/Qwen3/Parakeet/GigaAm/FireRed/X-ASR）全搬，
   对应 7 个 `*OnnxManager` + 7 段 `*Lifecycle`，设置页可切换本地引擎。

3. **先停在这里**：这份契约够了，你审完再定搬哪些；我不动代码。

选好告诉我（1/2/3），我据此开始拉齐依赖文件并落包名到 trime 的 `com.osfans.trime.asr`。
