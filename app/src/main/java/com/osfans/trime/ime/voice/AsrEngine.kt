/*
 * 本地 ASR 引擎统一接口（方法参考 trime-mie / 说点啥，仅保留离线部分）。
 * PCM 约定：16kHz、单声道、16bit 小端；按下开始录音，松手后一次性推理。
 */
package com.osfans.trime.ime.voice

interface AsrListener {
    /** 成功（文本可能为空串）；一次会话一定回调一次。 */
    fun onFinal(text: String)

    fun onError(message: String)

    fun onPartial(text: String) {}
}

interface AsrEngine {
    val isRunning: Boolean

    fun start()

    fun feed(pcm: ByteArray)

    /** 结束录音并触发识别，结果经 [AsrListener.onFinal] 回调。 */
    fun stop()

    fun cancel()
}
