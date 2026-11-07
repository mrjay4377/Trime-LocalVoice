/*
 * 空音频判定：对齐说点啥 v4.6.0（Silero V6）里「空音频检测」的用途，
 * 但本分支是按住说话、松手整段解码，不需要 VAD 自动断句，也不额外打包模型。
 * 用 200 ms 分块的能量门限拦掉纯静音，避免 SenseVoice 等模型对静音吐出幻觉文字，
 * 同时省掉一次大模型加载的内存与耗电。
 */
package com.osfans.trime.ime.voice

object VoiceActivity {
    /** 16 kHz / 单声道 / 16 bit，200 ms = 3200 字节。 */
    private const val BLOCK_BYTES = AudioRecorder.SAMPLE_RATE / 5 * 2
    private const val LEVEL_THRESHOLD = 0.10f
    private const val MIN_ACTIVE_BLOCKS = 1

    data class Result(
        val hasSpeech: Boolean,
        val peak: Float,
        val activeBlocks: Int,
        val blocks: Int,
    )

    fun analyze(pcm: ByteArray): Result {
        var blocks = 0
        var active = 0
        var peak = 0f
        var offset = 0
        while (offset + BLOCK_BYTES <= pcm.size) {
            val level = AudioRecorder.pcmLevel(pcm, offset, BLOCK_BYTES)
            if (level > LEVEL_THRESHOLD) active++
            if (level > peak) peak = level
            blocks++
            offset += BLOCK_BYTES
        }
        return Result(
            hasSpeech = peak >= LEVEL_THRESHOLD && active >= MIN_ACTIVE_BLOCKS,
            peak = peak,
            activeBlocks = active,
            blocks = blocks,
        )
    }
}
