/*
 * 原生流式离线识别：sherpa-onnx OnlineRecognizer（Transducer）。
 *
 * 与 OfflineSherpaEngine 的区别：按住语音键期间每来一块 PCM 就 acceptWaveform + 增量解码，
 * 把中间结果经 AsrListener.onPartial 送到候选栏语音栏实时显示；松手后 inputFinished 收尾，
 * 立即给出最终文本，不再对整段音频重新推理一次。
 *
 * 模型同样只从「用户目录 / voice」读取，音频只在设备本地推理，不联网。
 * 所有 sherpa 调用走反射，避免与 AAR 的 Kotlin 版本/混淆规则耦合。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private fun onlineFindMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method =
    try {
        cls.getMethod(name, *params)
    } catch (_: Throwable) {
        cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
    }

/**
 * 流式识别器缓存：600MB 级的 Transducer 首次加载很慢，会话之间复用同一个 OnlineRecognizer，
 * 每次按住说话只用 createStream() 新建一个轻量流。与离线缓存共用「跟随键盘」的卸载策略。
 */
internal object SherpaOnlineManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cached: Any? = null

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var unloadJob: Job? = null

    fun cancelScheduledUnload() {
        unloadJob?.cancel()
        unloadJob = null
    }

    fun scheduleUnload(delayMs: Long) {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(delayMs.coerceAtLeast(1_000L))
            unload()
        }
        Timber.d("sherpa online unload scheduled in %d ms", delayMs)
    }

    /** 键盘弹出时后台预加载，与正式会话共用同一把锁与指纹。 */
    fun preload(config: Any, key: String) {
        get(config, key, null)
    }

    /** 取（或创建）缓存的识别器；返回的对象由本管理器持有，调用方不要 release。 */
    fun get(config: Any, key: String, onLoadStart: (() -> Unit)?): Any = synchronized(this) {
        unloadJob?.cancel()
        unloadJob = null
        val hit = cached
        if (hit != null && cachedKey == key) return@synchronized hit
        onLoadStart?.invoke()
        hit?.let { releaseRecognizer(it) }
        Timber.i("sherpa online model loading")
        val created = createRecognizer(config)
        cached = created
        cachedKey = key
        Timber.i("sherpa online model loaded")
        created
    }

    fun unload() {
        val snapshot = synchronized(this) {
            val old = cached
            cached = null
            cachedKey = null
            unloadJob?.cancel()
            unloadJob = null
            old
        } ?: return
        releaseRecognizer(snapshot)
        Timber.d("sherpa online recognizer unloaded")
    }

    private fun createRecognizer(config: Any): Any {
        val cls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
        val cfg = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
        // 2.0.1 的主构造是 (AssetManager?, OnlineRecognizerConfig)；传 null 走 newFromFile，
        // 才能读取 voice 目录里的绝对路径（同 sherpa-onnx issue 2562）。
        val ctor = try {
            cls.getDeclaredConstructor(android.content.res.AssetManager::class.java, cfg)
        } catch (_: Throwable) {
            cls.getDeclaredConstructor(cfg)
        }
        return if (ctor.parameterTypes.size == 2) {
            ctor.newInstance(null, config)
        } else {
            ctor.newInstance(config)
        }
    }

    private fun releaseRecognizer(instance: Any) {
        runCatching {
            onlineFindMethod(instance.javaClass, "release").invoke(instance)
        }.onFailure { Timber.w(it, "sherpa online release failed") }
    }
}

/** 一次会话的反射包装：持有 recognizer 与它创建的 stream，负责增量解码与文本提取。 */
private class OnlineSession(private val recognizer: Any) {
    private val cls = recognizer.javaClass

    private val createStream: Method = cls.methods.firstOrNull {
        it.name == "createStream" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
    } ?: cls.methods.firstOrNull {
        it.name == "createStream" && it.parameterTypes.isEmpty()
    } ?: throw NoSuchMethodException("OnlineRecognizer.createStream")

    val stream: Any = if (createStream.parameterTypes.isEmpty()) {
        createStream.invoke(recognizer)!!
    } else {
        createStream.invoke(recognizer, "")!!
    }

    private val streamClass = stream.javaClass
    private val accept = onlineFindMethod(
        streamClass, "acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType!!,
    )
    private val inputFinished = runCatching {
        onlineFindMethod(streamClass, "inputFinished")
    }.getOrNull()
    private val releaseStream = runCatching {
        onlineFindMethod(streamClass, "release")
    }.getOrNull()

    private val isReady = onlineFindMethod(cls, "isReady", streamClass)
    private val decode = onlineFindMethod(cls, "decode", streamClass)
    private val getResult = onlineFindMethod(cls, "getResult", streamClass)

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        accept.invoke(stream, samples, sampleRate)
    }

    /** 松手后通知引擎音频结束，把尾部缓存的特征一次解码完。 */
    fun finishInput() {
        inputFinished?.let { runCatching { it.invoke(stream) } }
    }

    fun decodeReady() {
        // isReady 为 true 表示有足够特征可以跑一次 decoder；正常每 200ms 块只有 0~1 次。
        repeat(64) {
            if (isReady.invoke(recognizer, stream) as? Boolean != true) return
            decode.invoke(recognizer, stream)
        }
    }

    fun text(): String {
        val result = getResult.invoke(recognizer, stream) ?: return ""
        val byField = runCatching {
            result.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(result) as? String
        }.getOrNull()
        if (byField != null) return byField
        return runCatching {
            onlineFindMethod(result.javaClass, "getText").invoke(result) as? String
        }.getOrNull().orEmpty()
    }

    fun release() {
        releaseStream?.let { runCatching { it.invoke(stream) } }
    }
}

/**
 * 按住说话的流式引擎。feed() 只把 PCM 投进无界队列，所有 sherpa 调用都在单个 worker 协程里
 * 串行执行，避免 native 识别器被多线程同时访问，也不会因为解码耗时反压录音线程。
 */
class StreamingSherpaEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val model: ResolvedVoiceModel,
    private val onLoadStart: () -> Unit = {},
) : AsrEngine {
    companion object {
        /** 构造 OnlineRecognizerConfig；返回 (config, 稳定指纹) 供预加载与缓存复用。 */
        fun buildConfig(context: Context, model: ResolvedVoiceModel): Pair<Any, String> {
            val paths = model.paths
            val transducer = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
                .getDeclaredConstructor().newInstance()
            sherpaTrySetField(transducer, "encoder", paths.getValue("encoder.onnx"))
            sherpaTrySetField(transducer, "decoder", paths.getValue("decoder.onnx"))
            sherpaTrySetField(transducer, "joiner", paths.getValue("joiner.onnx"))

            val modelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
                .getDeclaredConstructor().newInstance()
            sherpaTrySetField(modelConfig, "transducer", transducer)
            sherpaTrySetField(modelConfig, "tokens", paths.getValue("tokens.txt"))
            sherpaTrySetField(modelConfig, "numThreads", LocalVoiceSettings.threads(context, VoiceModels.STREAMING_FAMILY))
            sherpaTrySetField(modelConfig, "provider", "cpu")
            sherpaTrySetField(modelConfig, "debug", false)

            val feat = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
                .getDeclaredConstructor().newInstance()
            sherpaTrySetField(feat, "sampleRate", AudioRecorder.SAMPLE_RATE)
            sherpaTrySetField(feat, "featureDim", 80)

            val config = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
                .getDeclaredConstructor().newInstance()
            sherpaTrySetField(config, "featConfig", feat)
            sherpaTrySetField(config, "modelConfig", modelConfig)
            sherpaTrySetField(config, "decodingMethod", "greedy_search")
            sherpaTrySetField(config, "maxActivePaths", 4)
            sherpaTrySetField(config, "enableEndpoint", true)
            // endpointConfig 使用库内置的三条默认规则，不显式构造 EndpointRule。
            return config to fingerprint(model)
        }

        private fun fingerprint(model: ResolvedVoiceModel): String = buildString {
            append(VoiceModels.STREAMING_FAMILY).append('|').append(model.dir.absolutePath).append('|')
            model.paths.entries.sortedBy { it.key }.forEach { (name, path) ->
                val file = File(path)
                append(name).append('=').append(path)
                append('(').append(file.length()).append(':').append(file.lastModified()).append(");")
            }
            append("|online|provider=cpu")
        }
    }

    private val running = AtomicBoolean(false)
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private var worker: Job? = null

    @Volatile
    private var session: OnlineSession? = null

    private var lastPartial = ""
    private var totalBytes = 0

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (model.paths.values.any { !File(it).isFile }) {
            listener.onError("本地流式模型文件已失效，请重新放置到 voice 目录")
            return
        }
        running.set(true)
        lastPartial = ""
        totalBytes = 0
        worker = scope.launch(Dispatchers.IO) {
            var online: OnlineSession? = null
            try {
                val (config, key) = buildConfig(context, model)
                val recognizer = SherpaOnlineManager.get(config, key, onLoadStart)
                val s = OnlineSession(recognizer)
                online = s
                session = s
                for (chunk in channel) {
                    if (!isActive) break
                    totalBytes += chunk.size
                    s.acceptWaveform(toFloatArray(chunk), AudioRecorder.SAMPLE_RATE)
                    s.decodeReady()
                    val text = s.text().trim()
                    if (text != lastPartial) {
                        lastPartial = text
                        listener.onPartial(text)
                    }
                }
                if (!isActive) return@launch
                if (totalBytes < 3200) {
                    listener.onError("录音太短，请按住语音键说完再松手")
                    return@launch
                }
                s.finishInput()
                s.decodeReady()
                val finalText = sanitize(s.text())
                Timber.i("streaming asr done: %.1fs audio, %d chars", totalBytes / 32000f, finalText.length)
                listener.onFinal(finalText)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Timber.e(t, "streaming asr failed")
                listener.onError("流式语音识别失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                session = null
                online?.release()
            }
        }
    }

    override fun feed(pcm: ByteArray) {
        if (running.get()) channel.trySend(pcm)
    }

    /** 松手：关闭队列，worker 处理完剩余 PCM 后直接给出最终文本。 */
    override fun stop() {
        if (!running.get()) return
        running.set(false)
        channel.close()
    }

    override fun cancel() {
        running.set(false)
        runCatching { channel.close() }
        worker?.cancel()
        worker = null
        // stream 由 worker 的 finally 统一释放，避免双重 release 触发 native 崩溃。
    }

    private fun sanitize(text: String): String =
        text.replace(Regex("<\\|[^>]*\\|>"), "").trim()

    private fun toFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = bb.short.toFloat() / 32768f
        return out
    }
}
