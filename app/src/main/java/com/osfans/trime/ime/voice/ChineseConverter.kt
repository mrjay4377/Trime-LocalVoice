/*
 * 语音识别结果的字形转换（简体 / 繁体 / 台湾正体），全程离线。
 *
 * 数据来自项目自带的 OpenCC 字典（Apache-2.0），由 scripts/gen_zh_convert.py
 * 在编译前压成 assets/voice/zh_convert.txt：词组最长匹配优先、单字兜底，
 * 与 OpenCC s2t.json / s2tw.json / t2s.json 的转换链等价。
 *
 * 这里刻意不调用 OpenCCDictManager.openCCLineConv()：那个 JNI 需要部署后生成的
 * .ocd2 字典，缺任何一个都会加载失败；语音上屏不该依赖部署状态。转换失败时
 * 一律原样输出，默认「不转换」时连表都不会读进内存。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import timber.log.Timber

object ChineseConverter {
    const val MODE_OFF = "off"
    const val MODE_S2T = "s2t"
    const val MODE_S2TW = "s2tw"
    const val MODE_T2S = "t2s"
    const val MODE_AUTO = "auto"

    private const val ASSET = "voice/zh_convert.txt"

    private class Chain(
        /** 结果与逐字转换不同的词组，直接查表取值。 */
        val phrases: Map<String, String>,
        /** 结果与逐字转换相同的词组：只用于屏蔽错误的短词命中，运行时按字重算。 */
        val shields: Set<String>,
        val maxPhraseLen: Int,
        val chars: Map<Char, String>,
    )

    private class Tables(
        val s2t: Chain,
        val tw: Chain,
        val t2s: Chain,
    )

    @Volatile
    private var cached: Tables? = null

    /** off 档完全不占内存。 */
    fun needsTable(mode: String): Boolean = mode != MODE_OFF

    /** 输入法启动 / 录音开始时在后台预热，避免首次上屏才读 assets。 */
    fun warmUp(context: Context) {
        runCatching { load(context.applicationContext) }
            .onFailure { Timber.w(it, "ChineseConverter: load failed") }
    }

    /** 设置页预览可直接调用；auto 档按简体状态预览。 */
    fun preview(
        context: Context,
        text: String,
        mode: String,
    ): String = convert(context, text, mode, false)

    /**
     * 按设置转换语音文本。[isTraditional] 来自 Rime 当前状态，仅 auto 档使用：
     * 输入法切到繁体时输出繁体，简体时输出简体（顺带纠正模型偶尔吐出的繁体字）。
     */
    fun convert(
        context: Context,
        text: String,
        mode: String,
        isTraditional: Boolean = false,
    ): String {
        if (text.isEmpty()) return text
        val effective = when (mode) {
            MODE_S2T, MODE_S2TW, MODE_T2S -> mode
            MODE_AUTO -> if (isTraditional) MODE_S2T else MODE_T2S
            else -> return text
        }
        return runCatching {
            val tables = load(context.applicationContext)
            when (effective) {
                MODE_S2T -> apply(text, tables.s2t)
                MODE_S2TW -> apply(apply(text, tables.s2t), tables.tw)
                MODE_T2S -> apply(text, tables.t2s)
                else -> text
            }
        }.onFailure { Timber.w(it, "ChineseConverter: convert failed, keeping raw text") }
            .getOrDefault(text)
    }

    private fun load(context: Context): Tables = cached ?: synchronized(this) {
        cached ?: parse(
            context.assets.open(ASSET).bufferedReader().use { it.readText() },
        ).also { cached = it }
    }

    private fun parse(raw: String): Tables {
        val sections = mutableMapOf<String, MutableList<Pair<String, String>>>()
        var current = ""
        for (line in raw.lineSequence()) {
            when {
                line.isBlank() || line.startsWith("#") -> Unit
                line.startsWith("[") && line.endsWith("]") -> {
                    current = line.substring(1, line.length - 1)
                    sections.getOrPut(current) { mutableListOf() }
                }
                else -> {
                    val tab = line.indexOf('\t')
                    val key = if (tab > 0) line.substring(0, tab) else line
                    val value = if (tab > 0) line.substring(tab + 1) else ""
                    if (key.isNotEmpty()) sections[current]?.add(key to value)
                }
            }
        }

        fun chain(diffSection: String, shieldSection: String, charSection: String): Chain {
            val chars = HashMap<Char, String>()
            val phrases = HashMap<String, String>()
            val shields = HashSet<String>()
            // 生成脚本已把非 BMP 字符（代理对）写进 DIFF 节，Char 表里都是单 char。
            sections[charSection].orEmpty().forEach { (k, v) -> chars[k[0]] = v }
            sections[diffSection].orEmpty().forEach { (k, v) -> phrases[k] = v }
            sections[shieldSection].orEmpty().forEach { (k, _) -> shields.add(k) }
            val maxLen = maxOf(
                phrases.keys.maxOfOrNull { it.length } ?: 1,
                shields.maxOfOrNull { it.length } ?: 1,
            )
            return Chain(phrases, shields, maxLen, chars)
        }

        return Tables(
            s2t = chain("S2T_DIFF", "S2T_SHIELD", "S2T_CHAR"),
            tw = chain("TW_DIFF", "TW_SHIELD", "TW_CHAR"),
            t2s = chain("T2S_DIFF", "T2S_SHIELD", "T2S_CHAR"),
        )
    }

    /** 与 OpenCC short_circuit 一致：先按最长词组替换，未命中的字再走单字表。 */
    private fun apply(
        text: String,
        chain: Chain,
    ): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val max = minOf(chain.maxPhraseLen, text.length - i)
            var matched = false
            var len = max
            while (len >= 2) {
                val segment = text.substring(i, i + len)
                val value = chain.phrases[segment]
                if (value != null) {
                    out.append(value)
                    matched = true
                    break
                }
                if (segment in chain.shields) {
                    // 生成时已确认：该词的值等于逐字转换结果。
                    for (c in segment) out.append(chain.chars[c] ?: c.toString())
                    matched = true
                    break
                }
                len--
            }
            if (matched) {
                i += len
                continue
            }
            val ch = text[i]
            // 代理对 / emoji 不在单字表里，逐 char 追加后仍是原字符。
            out.append(chain.chars[ch] ?: ch.toString())
            i++
        }
        return out.toString()
    }
}
