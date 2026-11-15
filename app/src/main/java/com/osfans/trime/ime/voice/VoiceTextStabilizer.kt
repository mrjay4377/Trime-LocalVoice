/*
 * 流式中间结果的「最长稳定前缀」（LSP）决策器，纯 Kotlin、不依赖 Android。
 *
 * 之前 StreamingSherpaEngine 每次把整句 composing 全量替换，已出现的字会被反复擦掉重画，
 * 观感就是「中间字会闪、会被改写」。这里把连续数帧都不变的前缀判为稳定，交给
 * InputConnection.commitText 落定；末尾若干字仍留在 composing 里可改。
 * 借鉴 R2T2 的稳定前缀思路，但不改模型、不加依赖、不动 native。
 *
 * 注意：已落定的文字不能再回改，这是本方案与「整句 composing」的取舍。流式引擎的
 * getResult() 是累积文本、代码里也禁用了 endpoint（不会重置已解码文字），因此回改前缀
 * 极罕见；真发生时保持已落定内容不变，只把当前帧剩余部分作为 composing 尾部显示。
 */
package com.osfans.trime.ime.voice

internal class VoiceTextStabilizer(
    /** 连续多少帧都包含的前缀才算稳定；流式每块约 200ms，3 帧约 0.6s。 */
    private val frames: Int = 3,
    /** 末尾保留多少个字符仍在 composing：标点与分词修正常影响句尾，别过早锁死。 */
    private val keepTail: Int = 4,
) {
    data class Step(
        /** 本次要新落定并 commitText 的增量文字（可能为空）。 */
        val commitDelta: String,
        /** 本次应显示在 composing 里的尾部文字。 */
        val tail: String,
    )

    private val window = mutableListOf<String>()
    private var committed = ""

    val committedPrefix: String get() = committed

    val hasCommitted: Boolean get() = committed.isNotEmpty()

    fun reset() {
        window.clear()
        committed = ""
    }

    fun onPartial(text: String): Step {
        window.add(text)
        if (window.size > frames) window.removeAt(0)
        val lsp = if (window.size < frames) "" else commonPrefix(window)
        val targetLen = (lsp.length - keepTail).coerceAtLeast(committed.length)
        if (targetLen > committed.length && targetLen <= text.length && text.startsWith(lsp)) {
            val target = text.substring(0, targetLen)
            if (target.startsWith(committed)) {
                val delta = target.substring(committed.length)
                committed = target
                return Step(delta, text.substring(committed.length))
            }
        }
        // 引擎若回改了已落定前缀，已落定的实体文字不回滚；composing 里也绝不能再放整句，
        // 否则屏幕上会同时出现「实体前缀 + 整句」。只显示当前帧扣掉已落定长度后的剩余，
        // 宁可少显示一帧，最终定稿由 commitVoiceText 的 mismatch 分支按会话锚点重写。
        val tail = if (text.startsWith(committed)) {
            text.substring(committed.length)
        } else {
            text.substring(committed.length.coerceAtMost(text.length))
        }
        return Step("", tail)
    }

    /**
     * 本帧写输入框失败时回滚已推进的 [committed]。调用方同步回滚后，窗口里的帧仍在，
     * 下一帧会重新按 LSP 把这段文字落定，避免「状态已推进、文字没写进去」的永久缺字。
     */
    fun rollback(step: Step) {
        val delta = step.commitDelta
        if (delta.isEmpty()) return
        if (committed.endsWith(delta)) {
            committed = committed.removeSuffix(delta)
        }
    }

    private fun commonPrefix(items: List<String>): String {
        if (items.isEmpty()) return ""
        var prefix = items.first()
        for (item in items) {
            var i = 0
            val limit = minOf(prefix.length, item.length)
            while (i < limit && prefix[i] == item[i]) i++
            prefix = prefix.substring(0, i)
            if (prefix.isEmpty()) break
        }
        return prefix
    }
}
