/*
 * 麦克风采集：16kHz / 单声道 / 16bit，200ms 块回调。
 * 方法参考 trime-mie / BiBi-Keyboard AudioCaptureManager（Apache-2.0）。
 * 音源优先 VOICE_RECOGNITION，读到全零帧时回退 MIC。
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.log10
import kotlin.math.sqrt

class AudioRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
    private val onStarted: () -> Unit = {},
    private val onChunk: (ByteArray) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_MILLIS = 200

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun pcmLevel(pcm: ByteArray, size: Int = pcm.size): Float = pcmLevel(pcm, 0, size)

        /** 带偏移的音量计算，供空音频判定逐块扫描，避免为每块复制数组。 */
        fun pcmLevel(pcm: ByteArray, offset: Int, size: Int): Float {
            val count = minOf(size, pcm.size - offset) / 2
            if (count <= 0) return 0f
            var energy = 0.0
            for (i in 0 until count) {
                val p = offset + i * 2
                val sample = ((pcm[p].toInt() and 255) or (pcm[p + 1].toInt() shl 8))
                    .toShort().toDouble() / 32768.0
                energy += sample * sample
            }
            val rms = sqrt(energy / count)
            return if (rms <= 0.001) 0f else ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }

    @Volatile
    private var record: AudioRecord? = null
    private var job: Job? = null

    val isRecording: Boolean get() = job?.isActive == true

    fun start(): Boolean {
        if (isRecording) return true
        if (!hasPermission(context)) return false
        job = scope.launch(Dispatchers.IO) {
            val chunkBytes = (SAMPLE_RATE * CHUNK_MILLIS / 1000) * 2
            var source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            var rec = createRecord(source, chunkBytes)
            if (rec == null) {
                Timber.w("AudioRecorder: VOICE_RECOGNITION unavailable, fallback MIC")
                source = MediaRecorder.AudioSource.MIC
                rec = createRecord(source, chunkBytes)
            }
            var audio = rec ?: run {
                onError("无法初始化麦克风")
                return@launch
            }
            record = audio
            try {
                coroutineContext.ensureActive()
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未进入录音状态" }
                onStarted()
                Timber.d("AudioRecorder started, source=%d", source)
                val buf = ByteArray(chunkBytes)
                var zeroFrames = 0
                while (isActive) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("麦克风读取失败：$n")
                    if (n == 0) continue
                    if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                        zeroFrames = if ((0 until n).all { buf[it].toInt() == 0 }) zeroFrames + 1 else 0
                        if (zeroFrames >= 2) {
                            val fallback = createRecord(MediaRecorder.AudioSource.MIC, chunkBytes)
                            if (fallback != null) {
                                runCatching { audio.stop() }
                                audio.release()
                                audio = fallback
                                record = audio
                                source = MediaRecorder.AudioSource.MIC
                                audio.startRecording()
                                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "备用麦克风未进入录音状态" }
                                continue
                            }
                        }
                    }
                    if (isActive) onChunk(buf.copyOf(n))
                }
            } catch (t: Throwable) {
                Timber.w(t, "AudioRecorder failed")
                if (isActive) onError("录音失败：${t.message.orEmpty()}")
            } finally {
                runCatching { audio.stop() }
                runCatching { audio.release() }
                if (record === audio) record = null
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        runCatching { record?.stop() }
    }

    suspend fun stopAndJoin() {
        val active = job
        stop()
        active?.join()
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun createRecord(source: Int, chunkBytes: Int): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = (minBuf * 2).coerceAtLeast(chunkBytes * 2)
        AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        ).let { if (it.state == AudioRecord.STATE_INITIALIZED) it else { it.release(); null } }
    } catch (t: Throwable) {
        Timber.w(t, "AudioRecorder: createRecord($source) failed")
        null
    }
}
