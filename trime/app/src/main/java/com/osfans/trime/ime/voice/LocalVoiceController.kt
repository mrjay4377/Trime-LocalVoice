/*
 * 本地语音会话：按住语音键开始录音，松手结束并识别上屏。
 * 方法参考 trime-mie 的 BuiltInVoiceController，去掉在线引擎、预览与 UI 相位的依赖。
 * 模型只从「用户目录 / voice」读取，音频只在设备本地推理。
 */
package com.osfans.trime.ime.voice

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalVoiceController(private val service: TrimeInputMethodService) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Session(val familyId: String) {
        val pcm = ByteArrayOutputStream()
        var engine: AsrEngine? = null
        var recorder: AudioRecorder? = null
        var watchdog: Job? = null
        var timeoutJob: Job? = null
        var finishing = false
        var finished = false
        var seconds = 0f
        var peakLevel = 0f
    }

    @Volatile
    private var current: Session? = null

    val isActive: Boolean get() = current != null

    /** 录音状态（不含识别阶段）：驱动候选栏的语音标注显隐。回调在主线程执行。 */
    @Volatile
    private var recording = false

    var onRecordingChanged: ((Boolean) -> Unit)? = null

    /** 实时音量（0..1），驱动候选栏两侧的波形起伏；回调在主线程执行。 */
    var onLevelChanged: ((Float) -> Unit)? = null

    private fun publishRecording(active: Boolean) {
        if (recording == active) return
        recording = active
        if (!active) main.post { onLevelChanged?.invoke(0f) }
        main.post { onRecordingChanged?.invoke(active) }
    }

    /** 只保留一个 Toast：多条提示会排队显示，导致上屏文字后仍弹出旧的「正在录音」。 */
    private var toast: Toast? = null

    private fun notice(text: String) {
        main.post {
            runCatching {
                toast?.cancel()
                Toast.makeText(service, text, Toast.LENGTH_SHORT).also { toast = it }.show()
            }.onFailure { Timber.w(it, "LocalVoice toast failed") }
        }
    }

    /** 结果已上屏时立刻收起状态提示。 */
    private fun dismissNotice() {
        main.post {
            runCatching { toast?.cancel() }
            toast = null
        }
    }

    /** 开始一次录音；失败时已给出提示并返回 false。 */
    fun start(): Boolean {
        cancel()
        publishRecording(false)
        main.post { onLevelChanged?.invoke(0f) }
        if (!LocalVoiceSettings.isEnabled(service)) {
            notice("请在设置 → 本地语音识别中启用语音输入")
            return false
        }
        if (!AudioRecorder.hasPermission(service)) {
            notice("请先授予麦克风权限（设置 → 本地语音识别）")
            return false
        }
        val familyId = LocalVoiceSettings.vendor(service)
        val status = VoiceModels.status(service, familyId)
        val model = status.resolved ?: run {
            notice("未找到本地模型，请把模型文件放到：${status.root.absolutePath}")
            return false
        }

        val session = Session(familyId)
        current = session
        val listener = object : AsrListener {
            override fun onFinal(text: String) {
                main.post {
                    if (current !== session || session.finished) return@post
                    session.finished = true
                    if (text.isBlank()) {
                        val hint =
                            if (session.peakLevel < 0.02f) {
                                "没有识别到文字：录音几乎是静音，请检查麦克风"
                            } else {
                                "没有识别到文字：请确认 voice 目录里的模型与所选引擎匹配"
                            }
                        notice(hint)
                    } else {
                        Timber.i("LocalVoice commit %d chars (%.1fs audio)", text.length, session.seconds)
                        dismissNotice()
                        runCatching { service.commitText(text) }
                            .onFailure { Timber.w(it, "LocalVoice commitText failed") }
                    }
                    cleanup(session)
                }
            }

            override fun onError(message: String) {
                main.post {
                    if (current !== session || session.finished) return@post
                    session.finished = true
                    notice(message)
                    cleanup(session)
                }
            }
        }

        val engine = OfflineSherpaEngine(
            context = service,
            scope = scope,
            listener = listener,
            familyId = familyId,
            model = model,
            onLoadStart = { notice("正在加载本地模型，首次加载可能较慢…") },
        )
        session.engine = engine
        engine.start()
        if (!engine.isRunning) return false

        session.recorder = AudioRecorder(
            context = service,
            scope = scope,
            onError = { message -> listener.onError(message) },
            // 候选栏的语音标注已是明确的录音反馈，不再弹 Toast，
            // 否则与「正在识别」提示排队，会在文字上屏之后才冒出来。
            onStarted = {},
            onChunk = { chunk ->
                // 关键：PCM 必须同时喂给识别引擎。只存 session.pcm 会让引擎拿到空 buffer，
                // stop() 直接返回空文本，表现为「按住有语音栏、松手却没有文字」。
                session.pcm.write(chunk)
                session.engine?.feed(chunk)
                val level = AudioRecorder.pcmLevel(chunk)
                if (level > session.peakLevel) session.peakLevel = level
                main.post {
                    if (recording && current === session) onLevelChanged?.invoke(level)
                }
            },
        )
        if (session.recorder?.start() != true) {
            listener.onError("无法开始录音，请检查麦克风权限")
            return false
        }
        // 最长录制 2 分钟，避免忘记结束导致一直占用麦克风。
        session.watchdog = scope.launch {
            delay(120_000)
            main.post {
                if (current === session && !session.finished && !session.finishing) {
                    notice("录音已达 2 分钟，自动开始识别")
                    finish()
                }
            }
        }
        publishRecording(true)
        return true
    }

    /** 结束录音并触发离线识别；结果经 onFinal 上屏。 */
    fun finish() {
        val session = current ?: return
        if (session.finishing || session.finished) return
        session.finishing = true
        // 松手即收起标注，识别仍在后台继续。
        publishRecording(false)
        session.watchdog?.cancel()
        scope.launch {
            session.recorder?.stopAndJoin()
            val pcm = session.pcm.toByteArray()
            session.seconds = pcm.size / (AudioRecorder.SAMPLE_RATE * 2f)
            if (LocalVoiceSettings.isSaveRecording(service)) saveRecording(pcm)
            Timber.i(
                "LocalVoice captured %.1fs (%d bytes), peak level %.2f",
                session.seconds,
                pcm.size,
                session.peakLevel,
            )
            if (current === session && !session.finished) {
                // 大模型首次加载可能要几十秒，给一个明确反馈，避免看起来「松手没反应」。
                notice("正在本地识别（%.1f 秒录音）…".format(session.seconds))
                withContext(Dispatchers.Main) {
                    if (current === session && !session.finished) session.engine?.stop()
                }
            }
        }
        // 识别结果迟迟不返回时兜底释放会话。
        session.timeoutJob = scope.launch {
            delay(120_000)
            main.post {
                if (current === session && !session.finished) {
                    notice("识别超时，请检查模型文件与可用内存")
                    session.finished = true
                    cleanup(session)
                }
            }
        }
    }

    /** 放弃本次会话，不再上屏。 */
    fun cancel() {
        val session = current ?: return
        // 松手后手势层可能补发 onCancel（重复按键任务结束、滑出按键等）。此时会话已进入
        // 收尾/识别阶段，不能再清掉，否则 engine.stop() 不会执行，表现为「松手没有文字」。
        if (session.finishing || session.finished) return
        cleanup(session)
    }

    fun dispose() {
        cancel()
        scope.cancel()
    }

    private fun cleanup(session: Session) {
        publishRecording(false)
        session.finished = true
        session.watchdog?.cancel()
        session.timeoutJob?.cancel()
        session.recorder?.stop()
        session.engine?.cancel()
        if (current === session) current = null
    }

    /** 可选：把最近一次录音以 wav 保存到用户目录 / voice。 */
    private fun saveRecording(pcm: ByteArray) {
        runCatching {
            val dir = VoiceModels.ensureRoot(service)
            val out = File(dir, "last_recording.wav")
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcm.size)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(1) // mono
                putInt(AudioRecorder.SAMPLE_RATE)
                putInt(AudioRecorder.SAMPLE_RATE * 2) // byte rate
                putShort(2) // block align
                putShort(16) // bits per sample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcm.size)
            }
            out.outputStream().use { stream ->
                stream.write(header.array())
                stream.write(pcm)
            }
        }.onFailure { Timber.w(it, "LocalVoice: save recording failed") }
    }
}
