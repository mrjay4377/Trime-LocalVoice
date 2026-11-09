/*
 * 离线识别：sherpa-onnx 反射调用，按住说话累积 PCM，松手后整体推理一次。
 * config 构造与识别器缓存方法参考 trime-mie / BiBi-Keyboard（Apache-2.0）。
 * 模型路径全部来自「用户目录 / voice」，见 [VoiceModels]。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method =
    try {
        cls.getMethod(name, *params)
    } catch (_: Throwable) {
        cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
    }

internal class ReflectiveStream(val instance: Any) {
    private val withRate = runCatching {
        findMethod(instance.javaClass, "acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType!!)
    }.getOrNull()
    private val withoutRate = runCatching {
        findMethod(instance.javaClass, "acceptWaveform", FloatArray::class.java)
    }.getOrNull()
    private val release = runCatching { findMethod(instance.javaClass, "release") }.getOrNull()

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        val withRateMethod = withRate
        if (withRateMethod != null) {
            try {
                withRateMethod.invoke(instance, samples, sampleRate)
                return
            } catch (t: Throwable) {
                val fallback = withoutRate ?: throw t
                fallback.invoke(instance, samples)
                return
            }
        }
        (withoutRate ?: throw NoSuchMethodException("acceptWaveform")).invoke(instance, samples)
    }

    fun release() {
        release?.let { runCatching { it.invoke(instance) } }
    }
}

internal class ReflectiveRecognizer(private val instance: Any, private val cls: Class<*>) {
    private val createStream = findMethod(cls, "createStream")
    private val releaseMethod = runCatching { findMethod(cls, "release") }.getOrNull()
    private val streamClass = createStream.returnType
    private val decode = findMethod(cls, "decode", streamClass)
    private val getResult = findMethod(cls, "getResult", streamClass)

    fun createStream(): ReflectiveStream = ReflectiveStream(createStream.invoke(instance)!!)

    fun decode(stream: ReflectiveStream): String? {
        decode.invoke(instance, stream.instance)
        val result = getResult.invoke(instance, stream.instance) ?: return null
        val fieldText = runCatching {
            result.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(result) as? String
        }.getOrNull()
        if (fieldText != null) return fieldText
        val getter = runCatching { findMethod(result.javaClass, "getText") }.getOrNull()
        return getter?.invoke(result) as? String ?: result.toString()
    }

    fun release() {
        releaseMethod?.let { runCatching { it.invoke(instance) } }
    }
}

/** 反射写 sherpa 配置字段；兼容驼峰字段与 setter / 下划线字段。 */
internal fun sherpaTrySetField(target: Any, name: String, value: Any?): Boolean {
    val direct = runCatching {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
        true
    }.getOrDefault(false)
    if (direct) return true
    val snake = name.replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }
    val candidates = listOf(name, snake)
    for (key in candidates) {
        val byField = runCatching {
            val field = target.javaClass.getDeclaredField(key)
            field.isAccessible = true
            field.set(target, value)
            true
        }.getOrDefault(false)
        if (byField) return true
        val setterName = "set" + key.replaceFirstChar { it.uppercase() }
        val bySetter = runCatching {
            val setter = target.javaClass.methods.firstOrNull {
                it.name == setterName && it.parameterTypes.size == 1
            } ?: return@runCatching false
            setter.invoke(target, value)
            true
        }.getOrDefault(false)
        if (bySetter) return true
    }
    Timber.w("sherpa: cannot set field %s", name)
    return false
}

/** 识别器缓存：config 相同即复用，互斥加载，keep-alive 自动卸载。 */
internal class SherpaOfflineManager {
    companion object {
        @Volatile
        private var instance: SherpaOfflineManager? = null

        fun getInstance(): SherpaOfflineManager = instance ?: synchronized(this) {
            instance ?: SherpaOfflineManager().also { instance = it }
        }

        fun isOnnxAvailable(): Boolean = try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            true
        } catch (t: Throwable) {
            Timber.e(t, "sherpa-onnx not available")
            false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var cachedRecognizer: ReflectiveRecognizer? = null

    @Volatile
    private var cachedConfig: Any? = null

    /** 配置的稳定指纹。config 对象每次识别都会新建，用对象引用比较会让缓存永远失效。 */
    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var unloadJob: Job? = null

    fun isLoaded(key: String): Boolean = cachedRecognizer != null && cachedKey == key

    fun cancelScheduledUnload() {
        unloadJob?.cancel()
        unloadJob = null
    }

    /** 键盘隐藏后延迟释放；期间用户再次唤起键盘会取消这次卸载。 */
    fun scheduleUnload(delayMs: Long) {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(delayMs.coerceAtLeast(1_000L))
            unload()
        }
        Timber.d("sherpa offline unload scheduled in %d ms", delayMs)
    }

    /**
     * 只创建识别器、不做解码，供「键盘弹出时预加载」使用。
     * 与 decodeOffline 共用互斥锁和缓存指纹，预加载过的模型下次识别直接命中缓存。
     */
    suspend fun preload(config: Any, key: String) {
        mutex.withLock {
            val cached = cachedRecognizer
            if (cached != null && cachedKey == key) {
                // 已加载时不要再取消待执行的空闲卸载：预加载（设置页预热）在任意档位都会触发，
                // 无脑 cancel 会让「空闲 N 分钟后卸载」的计时凭空消失、模型永久驻留。
                // 「跟随键盘」档的取消由 VoiceLifecycle.onKeyboardShown 显式完成。
                return@withLock
            }
            cached?.release()
            cachedRecognizer = null
            cachedConfig = null
            cachedKey = null
            val native = createRecognizer(config)
            val raw = ReflectiveRecognizer(native, native.javaClass)
            cachedRecognizer = raw
            cachedConfig = config
            cachedKey = key
            Timber.i("sherpa offline model preloaded")
        }
    }

    fun unload() {
        val snapshot = cachedRecognizer ?: return
        scope.launch {
            mutex.withLock {
                if (cachedRecognizer !== snapshot) return@withLock
                cachedRecognizer = null
                cachedConfig = null
                cachedKey = null
            }
            snapshot.release()
            Timber.d("sherpa offline recognizer unloaded")
        }
    }

    /** 删除模型文件前同步释放：等正在进行的解码结束，再真正 free native 对象后返回。 */
    suspend fun unloadNow() {
        val snapshot = mutex.withLock {
            unloadJob?.cancel()
            unloadJob = null
            val old = cachedRecognizer
            cachedRecognizer = null
            cachedConfig = null
            cachedKey = null
            old
        }
        snapshot?.release()
    }

    private fun createRecognizer(config: Any): Any {
        val clsRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        val clsConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
        // 绝对路径加载必须 assetManager = null（sherpa-onnx issue 2562）
        val ctor = try {
            clsRecognizer.getDeclaredConstructor(clsConfig)
        } catch (_: Throwable) {
            clsRecognizer.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsConfig)
        }
        return if (ctor.parameterTypes.size == 2) ctor.newInstance(null, config) else ctor.newInstance(config)
    }

    suspend fun decodeOffline(
        config: Any,
        configKey: String,
        samples: FloatArray,
        sampleRate: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null,
    ): String? {
        // 键盘预加载可能正在持锁；等待它完成比直接抛错更稳（说点啥统一交付流程的做法）。
        mutex.lock()
        val began = SystemClock.elapsedRealtime()
        return try {
            val cached = cachedRecognizer
            val recognizer = if (cached != null && cachedKey == configKey) {
                Timber.d("sherpa offline model cache hit")
                cached
            } else {
                unloadJob?.cancel()
                cachedRecognizer = null
                cachedConfig = null
                cachedKey = null
                cached?.release()
                onLoadStart?.invoke()
                Timber.i("sherpa offline model loading, samples=%d", samples.size)
                val nativeRecognizer = createRecognizer(config)
                val raw = ReflectiveRecognizer(nativeRecognizer, nativeRecognizer.javaClass)
                cachedRecognizer = raw
                cachedConfig = config
                cachedKey = configKey
                onLoadDone?.invoke()
                Timber.i("sherpa offline model loaded in %d ms", SystemClock.elapsedRealtime() - began)
                raw
            }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                val text = recognizer.decode(stream)
                Timber.i("sherpa decode done in %d ms, chars=%d", SystemClock.elapsedRealtime() - began, text?.length ?: 0)
                // 跟随键盘档（alwaysKeep）的卸载由 VoiceLifecycle 在键盘隐藏时安排，
                // 这里不能顺手 cancel，否则识别一次之后模型就再也不会卸载了。
                if (!alwaysKeep) {
                    unloadJob?.cancel()
                    if (keepAliveMs <= 0L) {
                        unload()
                    } else {
                        unloadJob = scope.launch {
                            delay(keepAliveMs)
                            unload()
                        }
                    }
                }
                text
            } finally {
                stream.release()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Timber.e(t, "sherpa offline decode failed")
            throw t
        } finally {
            mutex.unlock()
        }
    }
}

/** 五个离线家族共用的按住说话引擎，家族差异只在 config 构造。 */
class OfflineSherpaEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val familyId: String,
    private val model: ResolvedVoiceModel,
    private val onLoadStart: () -> Unit = {},
    private val onLoadDone: () -> Unit = {},
) : AsrEngine {
    companion object {
        private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val probeListener =
            object : AsrListener {
                override fun onFinal(text: String) {}

                override fun onError(message: String) {}
            }

        /** 键盘预加载复用同一套 config 构造与缓存指纹，避免两份实现走偏。 */
        internal fun buildConfig(
            context: Context,
            familyId: String,
            model: ResolvedVoiceModel,
        ): Pair<Any, String> {
            val probe = OfflineSherpaEngine(context, probeScope, probeListener, familyId, model)
            return probe.buildRecognizerConfig(model.paths) to probe.configFingerprint()
        }
    }

    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()
    private var decodeJob: Job? = null

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (model.paths.values.any { !File(it).isFile }) {
            listener.onError("本地模型文件已失效，请重新放置到 voice 目录")
            return
        }
        running.set(true)
        buffer.reset()
    }

    override fun feed(pcm: ByteArray) {
        if (running.get()) buffer.write(pcm)
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        val pcm = buffer.toByteArray()
        buffer.reset()
        decodeJob = scope.launch(Dispatchers.IO) {
            Timber.i(
                "offline asr start: %d bytes (%.2fs) family=%s",
                pcm.size,
                pcm.size / 32000f,
                familyId,
            )
            if (pcm.size < 3200) {
                listener.onError("录音太短，请按住语音键说完再松手")
                return@launch
            }
            // 说点啥 v4.6.0 的「空音频判定」：纯静音直接拦下，不加载模型也不产生幻觉文字。
            val vad = VoiceActivity.analyze(pcm)
            Timber.i("voice activity: peak=%.2f active=%d/%d", vad.peak, vad.activeBlocks, vad.blocks)
            if (!vad.hasSpeech) {
                listener.onError("没有检测到语音，请靠近麦克风后重试")
                return@launch
            }
            try {
                check(SherpaOfflineManager.isOnnxAvailable()) { "本地识别库不可用" }
                val config = buildRecognizerConfig(model.paths)
                val minutes = LocalVoiceSettings.keepAliveMinutes(context, familyId)
                val text = SherpaOfflineManager.getInstance().decodeOffline(
                    config = config,
                    configKey = configFingerprint(),
                    samples = pcmToFloatArray(pcm),
                    sampleRate = AudioRecorder.SAMPLE_RATE,
                    keepAliveMs = if (minutes <= 0) 0L else minutes.toLong() * 60_000L,
                    alwaysKeep = minutes < 0,
                    onLoadStart = onLoadStart,
                    onLoadDone = onLoadDone,
                )
                val cleaned = text?.trim()?.let(::sanitizeResult).orEmpty()
                listener.onFinal(cleaned)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Timber.e(t, "offline asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        decodeJob?.cancel()
        decodeJob = null
        running.set(false)
        buffer.reset()
    }

    private fun featConfig(): Any {
        val feat = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig").getDeclaredConstructor().newInstance()
        sherpaTrySetField(feat, "sampleRate", AudioRecorder.SAMPLE_RATE)
        sherpaTrySetField(feat, "featureDim", 80)
        if (familyId == "parakeet" || familyId == "firered_asr" || familyId == "telespeech") {
            sherpaTrySetField(feat, "dither", 0.0f)
        }
        return feat
    }

    private fun baseModelConfig(tokens: String): Any {
        val modelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
            .getDeclaredConstructor().newInstance()
        sherpaTrySetField(modelConfig, "tokens", tokens)
        sherpaTrySetField(modelConfig, "numThreads", LocalVoiceSettings.threads(context, familyId))
        sherpaTrySetField(modelConfig, "provider", "cpu")
        sherpaTrySetField(modelConfig, "debug", false)
        return modelConfig
    }

    private fun finalizeRecognizerConfig(modelConfig: Any, withFeat: Boolean): Any {
        val recConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
            .getDeclaredConstructor().newInstance()
        sherpaTrySetField(recConfig, "modelConfig", modelConfig)
        if (withFeat) {
            sherpaTrySetField(recConfig, "featConfig", featConfig())
            sherpaTrySetField(recConfig, "decodingMethod", "greedy_search")
            sherpaTrySetField(recConfig, "maxActivePaths", 4)
        }
        return recConfig
    }

    private fun buildRecognizerConfig(paths: Map<String, String>): Any = when (familyId) {
        "sensevoice" -> {
            val sv = Class.forName("com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig")
                .getDeclaredConstructor().newInstance()
            sherpaTrySetField(sv, "model", paths["model.int8.onnx"] ?: paths["model.onnx"].orEmpty())
            sherpaTrySetField(sv, "language", LocalVoiceSettings.language(context, familyId).ifBlank { "auto" })
            if (!sherpaTrySetField(sv, "useInverseTextNormalization", LocalVoiceSettings.isItn(context, familyId))) {
                sherpaTrySetField(sv, "useItn", LocalVoiceSettings.isItn(context, familyId))
            }
            val modelConfig = baseModelConfig(paths.getValue("tokens.txt"))
            sherpaTrySetField(modelConfig, "senseVoice", sv)
            finalizeRecognizerConfig(modelConfig, withFeat = false)
        }

        "qwen3_asr" -> {
            val q = Class.forName("com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig")
                .getDeclaredConstructor().newInstance()
            // tokenizer 参数是目录：vocab / merges / tokenizer_config 与模型在同一目录
            val tokenizerDir = File(paths.getValue("vocab.json")).parentFile?.absolutePath.orEmpty()
            sherpaTrySetField(q, "convFrontend", paths.getValue("conv_frontend.onnx"))
            sherpaTrySetField(q, "encoder", paths.getValue("encoder.int8.onnx"))
            sherpaTrySetField(q, "decoder", paths.getValue("decoder.int8.onnx"))
            sherpaTrySetField(q, "tokenizer", tokenizerDir)
            sherpaTrySetField(q, "maxTotalLen", 512)
            sherpaTrySetField(q, "maxNewTokens", 512)
            val modelConfig = baseModelConfig("")
            sherpaTrySetField(modelConfig, "qwen3Asr", q)
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        "funasr_nano" -> {
            val fn = Class.forName("com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig")
                .getDeclaredConstructor().newInstance()
            val tokenizerDir = File(paths.getValue("tokenizer.json")).parentFile?.absolutePath.orEmpty()
            sherpaTrySetField(fn, "encoderAdaptor", paths.getValue("encoder_adaptor.int8.onnx"))
            sherpaTrySetField(fn, "llm", paths.getValue("llm.int8.onnx"))
            sherpaTrySetField(fn, "embedding", paths.getValue("embedding.int8.onnx"))
            sherpaTrySetField(fn, "tokenizer", tokenizerDir)
            sherpaTrySetField(fn, "userPrompt", "语音转写：")
            sherpaTrySetField(fn, "language", LocalVoiceSettings.language(context, familyId))
            sherpaTrySetField(fn, "itn", LocalVoiceSettings.isItn(context, familyId))
            val modelConfig = baseModelConfig("")
            sherpaTrySetField(modelConfig, "funasrNano", fn)
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        "parakeet" -> {
            val t = Class.forName("com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig")
                .getDeclaredConstructor().newInstance()
            sherpaTrySetField(t, "encoder", paths.getValue("encoder.int8.onnx"))
            sherpaTrySetField(t, "decoder", paths.getValue("decoder.int8.onnx"))
            sherpaTrySetField(t, "joiner", paths.getValue("joiner.int8.onnx"))
            val modelConfig = baseModelConfig(paths.getValue("tokens.txt"))
            sherpaTrySetField(modelConfig, "modelType", "nemo_transducer")
            if (!sherpaTrySetField(modelConfig, "transducer", t)) {
                sherpaTrySetField(modelConfig, "transducerModel", t)
            }
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        "firered_asr" -> {
            val ctc = Class.forName("com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig")
                .getDeclaredConstructor().newInstance()
            sherpaTrySetField(ctc, "model", paths.getValue("model.int8.onnx"))
            val modelConfig = baseModelConfig(paths.getValue("tokens.txt"))
            sherpaTrySetField(modelConfig, "fireRedAsrCtc", ctc)
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        // TeleSpeech CTC：sherpa-onnx 把它做成 OfflineModelConfig 的 teleSpeech 字符串字段
        // （模型路径），配合 tokens + featConfig + greedy_search，不需要单独的 Config 类。
        "telespeech" -> {
            val modelConfig = baseModelConfig(paths.getValue("tokens.txt"))
            sherpaTrySetField(modelConfig, "teleSpeech", paths.getValue("model.int8.onnx"))
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        else -> throw IllegalStateException("未知本地模型家族 $familyId")
    }

    private fun sanitizeResult(text: String): String {
        // SenseVoice 等模型会在文本前带 <|zh|><|NEUTRAL|><|Speech|> 之类的标签，去掉避免上屏乱码。
        val base = text.replace(Regex("<\\|[^>]*\\|>"), "").trim()
        return when (familyId) {
            "qwen3_asr" -> base.replace("<asr_text>", "").replace("</asr_text>", "").trim()

            "firered_asr" -> base.replace("<sil>", "").replace(Regex(" {2,}"), " ").trim()
            else -> base
        }
    }

    /** 模型路径 + 文件时间戳 + 推理配置，作为识别器缓存的键。 */
    private fun configFingerprint(): String = buildString {
        append(familyId).append('|').append(model.dir.absolutePath).append('|')
        model.paths.entries.sortedBy { it.key }.forEach { (name, path) ->
            val file = File(path)
            append(name).append('=').append(path)
            append('(').append(file.length()).append(':').append(file.lastModified()).append(");")
        }
        append("|threads=").append(LocalVoiceSettings.threads(context, familyId))
        append("|itn=").append(LocalVoiceSettings.isItn(context, familyId))
        append("|lang=").append(LocalVoiceSettings.language(context, familyId))
        append("|provider=cpu")
    }

    private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = bb.short.toFloat() / 32768f
        return out
    }
}
