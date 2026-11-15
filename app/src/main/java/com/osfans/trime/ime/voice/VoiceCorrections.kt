/*
 * 语音识别结果的「文本层纠错」词库。
 *
 * 与 ASR 层热词偏置互补：热词只在解码打分时给指定词加分，SenseVoice / Paraformer /
 * CTC 等架构根本不吃；而纠错是在识别原文上屏前做一次字符串替换，
 * 对全部 8 类模型都生效，也不依赖 sherpa-onnx 的架构白名单。
 *
 * 语义参照 CyIME 的 VoiceCorrections（GPL-3.0）：
 *  - 最长匹配优先；
 *  - 英文只在整词边界替换，避免把 "cat" 规则套进 "category"；
 *  - 一次扫描、替换结果不再参与匹配，杜绝 A→B 与 B→C 连锁替换；
 *  - 默认关闭，模板全是注释，升级后行为不变。
 *
 * 全程本地：只读写使用者资料夹 voice/corrections.txt，不联网。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.SystemClock
import timber.log.Timber
import java.io.File

object VoiceCorrections {
    const val FILE_NAME = "corrections.txt"
    private const val MAX_RULES = 200
    private const val MAX_SIDE_LENGTH = 20
    private const val STAT_INTERVAL_MS = 3_000L

    data class Rule(val from: String, val to: String)

    /** 已按 from 长度降序排好；主线程只读这份内存快照。 */
    @Volatile
    private var rules: List<Rule> = emptyList()

    @Volatile
    private var loaded = false

    @Volatile
    private var stamp = 0L
    private var cachedStamp = -1L
    private var cachedSize = -1L

    fun file(context: Context): File = File(VoiceModels.readOnlyRoot(context), FILE_NAME)

    fun ruleCount(): Int = rules.size

    fun template(): String =
        """
        # 语音识别结果纠错词库
        # 每行一条：错词=正词（也支持 错词=>正词 / 错词→正词），# 后面是注释。
        # 只替换「识别原文」，不会改动已上屏的文字；默认关闭，需要在上面打开开关。
        # 规则最长匹配优先，英文只在整词边界生效，替换结果不会被下一条规则再改一次。
        # 最多 200 条，单侧不超过 20 字。改完保存后点「重新载入」立即生效。
        #
        # 下面是示例，去掉行首的 # 才会生效：
        # 尧舜禹=姚顺雨
        # 赵庆=肇庆
        # 淮吉=怀集
        # 立马逗=利玛窦
        # 未来=蔚来
        # 卡萨地=卡萨帝
        # 无疆星=邬江兴
        # 啊四批灵=阿司匹林
        # 极客=极氪
        # 蓝图=岚图
        # 升腾=昇腾
        #
        # 注意：「权利 / 权力」「在 / 再」「定金 / 订金」这类两个写法都合法、
        # 需要靠语义判断的词，不要用本表替换——会制造新的错字。
        """.trimIndent() + "\n"

    /** 从磁盘重载。IO 协程或设置页调用；识别结果上屏的主线程不会碰磁盘。 */
    fun reload(context: Context, force: Boolean = false) {
        val f = file(context)
        val now = SystemClock.elapsedRealtime()
        if (!force && loaded && now - stamp < STAT_INTERVAL_MS) return
        stamp = now
        synchronized(this) {
            val currentStamp = f.lastModified()
            val currentSize = f.length()
            if (!force && loaded && currentStamp == cachedStamp && currentSize == cachedSize) return
            val parsed =
                runCatching { parse(if (f.isFile) f.readText(Charsets.UTF_8) else "") }
                    .onFailure { Timber.w(it, "voice corrections: read failed") }
                    .getOrDefault(emptyList())
            rules = parsed
            cachedStamp = currentStamp
            cachedSize = currentSize
            loaded = true
            Timber.d("voice corrections: %d rules from %s", parsed.size, f.name)
        }
    }

    /** 设置页「编辑全文」用；文件不存在时给出注释模板。 */
    fun text(context: Context): String =
        runCatching {
            val f = file(context)
            if (f.isFile) f.readText(Charsets.UTF_8) else template()
        }.onFailure { Timber.w(it, "voice corrections: read text failed") }
            .getOrDefault(template())

    /** 设置页保存：写回文件并立即刷新内存快照，绕开 mtime 精度问题。 */
    fun saveText(context: Context, text: String): Boolean =
        runCatching {
            val f = file(context)
            f.parentFile?.let { parent -> if (!parent.isDirectory) parent.mkdirs() }
            f.writeText(text, Charsets.UTF_8)
            reload(context, force = true)
            true
        }.onFailure { Timber.w(it, "voice corrections: save failed") }
            .getOrDefault(false)

    internal fun parse(raw: String): List<Rule> {
        val out = LinkedHashSet<Rule>()
        for (line in raw.lineSequence()) {
            val body = line.substringBefore('#').trim()
            if (body.isEmpty()) continue
            val at = body.indexOfAny(charArrayOf('=', '→'))
            if (at <= 0) continue
            val from = body.substring(0, at).trim().trimEnd('>').trim()
            // "A=>B" 时 at 指向 '='，剩下的 ">B" 要去掉多余的 '>'。
            val to = body.substring(at + 1).trim().trimStart('>').trim()
            if (from.isEmpty() || to.isEmpty() || from == to) continue
            if (from.length > MAX_SIDE_LENGTH || to.length > MAX_SIDE_LENGTH) continue
            out += Rule(from, to)
            if (out.size >= MAX_RULES) break
        }
        // 最长匹配优先；同长度保持文件里的先后顺序（sortedByDescending 是稳定排序）。
        return out.sortedByDescending { it.from.length }
    }

    /** 上屏前调用：只读内存快照，无磁盘 IO、无规则重载。 */
    fun apply(context: Context, input: String): String {
        if (input.isEmpty()) return input
        if (!LocalVoiceSettings.isVoiceCorrectionsEnabled(context)) return input
        return applyWith(rules, input)
    }

    fun applyWith(snapshot: List<Rule>, input: String): String {
        if (snapshot.isEmpty() || input.isEmpty()) return input
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            var hit: Rule? = null
            for (r in snapshot) {
                val end = i + r.from.length
                if (end > input.length) continue
                if (!input.regionMatches(i, r.from, 0, r.from.length)) continue
                if (!boundaryOk(input, i, end, r.from)) continue
                hit = r
                break
            }
            if (hit != null) {
                out.append(hit.to)
                // 直接跳过原片段：替换出来的文字不参与后续匹配，避免 A→B、B→C 连锁改写。
                i += hit.from.length
            } else {
                out.append(input[i])
                i++
            }
        }
        return out.toString()
    }

    /** 英文规则只在整词边界生效；纯中文规则不受影响。 */
    private fun boundaryOk(text: String, start: Int, end: Int, from: String): Boolean {
        fun asciiWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
        if (asciiWord(from.first()) && start > 0 && asciiWord(text[start - 1])) return false
        if (asciiWord(from.last()) && end < text.length && asciiWord(text[end])) return false
        return true
    }
}
