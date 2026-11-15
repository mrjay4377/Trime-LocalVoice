/*
 * 本地语音热词 / 上下文提示：把「统一词库文件 + Rime 用户词库 + 光标前文字」喂给 ASR。
 * 静态词条统一由 VoiceHotwordBook 管理（voice / hotwords.txt，可编辑、可跨设备拷贝）。
 *
 * 全程本地：不联网、不上传音频与文字。热词分两路送进 sherpa-onnx 2.0.1：
 *  1) recognizer.createStream(String hotwords)（native createStreamWithHotwords）——
 *     每次会话可不同，不重建识别器、不影响缓存指纹，因此上下文变化不会造成冷加载；
 *  2) Qwen3-ASR / FunASR Nano 这类 LLM 架构另有 config.hotwords 字符串字段，
 *     只放自定义热词与词库快照这类静态内容（纳入配置指纹，变化时重建识别器）；
 *     输入框上下文刻意不进 config，否则每次按键都换指纹、每次都冷加载。
 *  SenseVoice / Paraformer / CTC 架构原生不支持热词，一律不传（native 会直接退出）。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.SystemClock
import com.osfans.trime.data.userdict.UserDictManager
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

object VoiceHotwords {
    /** 热词太多会拖慢 FST 编译并干扰解码，限制总条数。 */
    private const val MAX_WORDS = 60
    private const val MAX_WORD_LENGTH = 12
    private const val DICT_TTL_MS = 30 * 60_000L

    /** Rime 导出用的临时文件名序号：30 分钟刷新与设置页「导入」可能并发，同名会互删。 */
    private val exportSeq = AtomicInteger(0)

    data class Hotwords(
        /** 给 createStream(String) 的格式：每行一个词，汉字按建模单元以空格拆开。 */
        val tokenized: String,
    ) {
        val isEmpty: Boolean get() = tokenized.isEmpty()
    }

    val EMPTY = Hotwords("")

    @Volatile
    private var dictWords: List<String> = emptyList()

    @Volatile
    private var dictStamp = 0L

    private val dictLock = Any()

    /**
     * 只有 Transducer 架构实现了 createStream(String hotwords)。其它架构
     * （SenseVoice / Paraformer / CTC）走的是 OfflineRecognizerImpl 的默认实现，
     * 里面直接 SHERPA_ONNX_EXIT(-1) —— 会给不支持的模型传热词就是杀进程，必须白名单。
     * 另外 greedy_search 解码不消费 context graph，这条通道实际收益有限，
     * 真正的上下文理解主要靠下方 LLM 架构的 config.hotwords。
     */
    private val STREAM_HOTWORD_FAMILIES = setOf(
        VoiceModels.STREAMING_FAMILY,
        "parakeet",
    )

    fun supportsStreamHotwords(familyId: String): Boolean = familyId in STREAM_HOTWORD_FAMILIES

    /** 只有 Qwen3-ASR / FunASR Nano 的配置类带自然语言 hotwords 字段。 */
    private val CONFIG_HOTWORD_FAMILIES = setOf("qwen3_asr", "funasr_nano")

    fun supportsConfigHotwords(familyId: String): Boolean = familyId in CONFIG_HOTWORD_FAMILIES

    /**
     * 支持 stream.setOption("hotwords", csv) 每次会话覆盖词表的家族。
     * sherpa-onnx v2.0.1 的两个 LLM 架构在 native 里用
     * `stream->HasOption("hotwords") ? ... : config.hotwords` 取词表，因此热词可以
     * 每次会话变化而**不需要重建识别器**，输入框上下文也能真正实时生效。
     * Java 侧 OfflineStream.setOption(String, String) 已用 javap 核过存在。
     */
    fun supportsSessionHotwords(familyId: String): Boolean = familyId in CONFIG_HOTWORD_FAMILIES

    /**
     * 后台刷新 Rime 用户词库快照（IO 线程调用，按住语音键的主线程不碰磁盘）。
     * 30 分钟内只导一次；导出走 Trime 自带的 UserDictManager，失败时静默退化为空词库。
     */
    fun refreshDictionary(context: Context) {
        if (!LocalVoiceSettings.isHotwordsEnabled(context)) return
        if (!LocalVoiceSettings.isHotwordsDictEnabled(context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - dictStamp < DICT_TTL_MS && dictStamp != 0L) return
        synchronized(dictLock) {
            if (now - dictStamp < DICT_TTL_MS && dictStamp != 0L) return
            // 即便失败也记时间戳，避免每次键盘弹出反复调 native 导出。
            dictStamp = now
            dictWords = runCatching { collectRimeWords(context) }
                .onFailure { Timber.w(it, "voice hotwords: export rime user dict failed") }
                .getOrDefault(emptyList())
            Timber.d("voice hotwords: %d words from rime user dict", dictWords.size)
        }
    }

    /**
     * 导出 Rime 用户词库快照（IO 线程调用）。同时供 30 分钟刷新与设置页
     * 「导入到词库文件」按钮使用，避免两处导出/解析逻辑走偏。
     */
    internal fun collectRimeWords(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        val names = UserDictManager.getUserDictList()
        for ((index, name) in names.withIndex()) {
            if (index >= 4 || out.size >= MAX_WORDS) break
            val buffer = ByteArrayOutputStream()
            // UserDictManager 把临时文件放在 cacheDir 并在 finally 里 delete：
            // 30 分钟刷新协程与设置页「导入」可能同时跑，同名文件会被对方删掉，
            // 因此加进程内唯一序号，让两次导出各自持有独立的临时文件。
            val tempName =
                "voice_hotwords_${name.hashCode().toString(16)}_${exportSeq.incrementAndGet()}.txt"
            val result = UserDictManager.exportUserDict(buffer, name, tempName)
            if (result.isFailure) continue
            parseUserDict(buffer.toString("UTF-8"), out)
        }
        return out.toList().take(MAX_WORDS)
    }

    /** librime 的 .userdb.txt：YAML 头之后每行是「词条<TAB>编码<TAB>权重」，只取第一列。 */
    private fun parseUserDict(text: String, out: LinkedHashSet<String>) {
        for (raw in text.lineSequence()) {
            if (out.size >= MAX_WORDS) break
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("---") ||
                line.startsWith("name:") || line.startsWith("version:") ||
                line.startsWith("sort:") || line.startsWith("use_preset_vocabulary:")
            ) {
                continue
            }
            val word = line.substringBefore('\t').substringBefore(' ').trim()
            if (isUsableWord(word)) out += word
        }
    }

    /**
     * 组装本次会话的词表（主线程调用，只读 SharedPreferences 与内存缓存）。
     * 三条通道共用同一份词源：词库文件 + Rime 快照 + 光标前上下文。
     * @param inputContext 光标前的文字，由 TrimeInputMethodService.voiceContextText() 提供。
     */
    private fun collectWords(context: Context, inputContext: String): List<String> {
        if (!LocalVoiceSettings.isHotwordsEnabled(context)) return emptyList()
        val words = LinkedHashSet<String>()

        // 1) 统一词库文件 voice/hotwords.txt（含从旧版 SharedPreferences 迁移的内容）。
        //    ensureFresh 只读内存 + 每 3 秒最多一次 stat；首次创建由后台完成，主线程不写盘。
        VoiceHotwordBook.ensureFresh(context)
        for (w in VoiceHotwordBook.words()) {
            if (words.size >= MAX_WORDS) break
            words += w
        }

        // 2) Rime 用户词库（后台刷新出来的内存快照）。
        if (LocalVoiceSettings.isHotwordsDictEnabled(context)) {
            for (w in dictWords) {
                if (words.size >= MAX_WORDS) break
                words += w
            }
        }

        // 3) 当前输入框上下文：按标点切成短语，取最近的一段，中文人名/专业词常在这里。
        if (LocalVoiceSettings.isHotwordsContextEnabled(context) && inputContext.isNotBlank()) {
            val parts = inputContext.split(Regex("[\\s，。！？；：,.!?;:\\n「」『』\"'()（）【】]+"))
            for (part in parts.asReversed()) {
                if (words.size >= MAX_WORDS) break
                val w = part.trim().take(MAX_WORD_LENGTH)
                if (isUsableWord(w) && w.length >= 2) words += w
                if (words.size >= MAX_WORDS) break
            }
        }

        return words.toList().take(MAX_WORDS)
    }

    /** Transducer 通道：每行一词，汉字按建模单元以空格拆开。 */
    fun build(context: Context, inputContext: String): Hotwords {
        val list = collectWords(context, inputContext)
        if (list.isEmpty()) return EMPTY
        return Hotwords(tokenized = list.joinToString("\n", transform = ::tokenize))
    }

    /**
     * LLM 架构的会话级词表：**ASCII 逗号**分隔的纯词表。
     *
     * 关键点（sherpa-onnx v2.0.1 native 源码核实）：
     * - Qwen3 的 Qwen3FormatHotwordsForPrompt() 用 SplitStringAndTrim(csv, ',')，
     *   只认 ASCII 逗号；用全角「，」会把整份词表当成 1 个超长词。
     * - 该字段是「词表位」而非场景描述输入框，塞自然语言句子模型基本忽略。
     * - 通过 stream.setOption("hotwords", csv) 每次会话传入，不进 config 指纹，
     *   因此可以同时带上光标前上下文，改词库也不需要重建几百 MB 的模型。
     */
    fun buildCsv(context: Context, inputContext: String): String =
        collectWords(context, inputContext)
            .filter { !it.contains(',') }
            .joinToString(",")

    /**
     * 静态词表，只在创建识别器时写进 LLM 架构的 config.hotwords 作为兜底
     * （会话级 setOption 失败时仍有一份基础词表）。**不参与 configFingerprint**：
     * 词库变化现在走会话级通道，重建模型只会白等几分钟。
     */
    fun staticNatural(context: Context): String {
        if (!LocalVoiceSettings.isHotwordsEnabled(context)) return ""
        val words = LinkedHashSet<String>()
        VoiceHotwordBook.ensureFresh(context)
        for (w in VoiceHotwordBook.words()) {
            if (words.size >= 30) break
            words += w
        }
        if (LocalVoiceSettings.isHotwordsDictEnabled(context)) {
            for (w in dictWords) {
                if (words.size >= 30) break
                words += w
            }
        }
        return words.toList().take(30).filter { !it.contains(',') }.joinToString(",")
    }

    /**
     * 旧版 SharedPreferences 里的一整串自定义热词，迁移到统一词库文件时复用。
     * 不再对整串做 take(500) 截断：旧版输入框能存远超 500 字的词表，静默丢词且无从察觉。
     * 改由 [limit] 按「词条数」收敛，单个词仍受 MAX_WORD_LENGTH 限制。
     */
    internal fun parseCustom(text: String, out: LinkedHashSet<String>, limit: Int = MAX_WORDS) {
        if (text.isBlank()) return
        for (raw in text.split(Regex("[\\s,，、;；|/]+"))) {
            if (out.size >= limit) break
            val w = raw.trim().trim('"', '\'', '「', '」').take(MAX_WORD_LENGTH)
            if (isUsableWord(w)) out += w
        }
    }

    /** native 的 hotwords 串按换行分词、按空格拆 token；LLM 的 CSV 通道按 ASCII 逗号切。 */
    private val UNSAFE_WORD_CHARS = setOf(
        ':', '=', '{', '}', '<', '>', '/', '\\', '|', ',', '，', ';', '；', '、', '"',
    )

    internal fun isUsableWord(word: String): Boolean {
        if (word.length !in 2..MAX_WORD_LENGTH) return false
        if (word.all { it.isDigit() }) return false
        // 含空白或分隔符的词会被 native 拆成多段、或被当成特殊标记，一律不收。
        // 原来只挡了 :={}<>；「/ 是否被 native 当分隔符」当时源码 URL 404 没能核实，
        // 按防御性原则把常见分隔符一并过滤，宁可少一个热词，也不让整份词表格式错乱。
        if (word.any { it.isWhitespace() || it in UNSAFE_WORD_CHARS }) return false
        // 至少包含一个汉字或拉丁字母；纯标点、纯符号不作为热词。
        return word.any { it in '\u4e00'..'\u9fff' || it.isLetter() }
    }

    /**
     * sherpa-onnx 的 hotwords 串按行分词、每行按空格拆 token。中文模型的建模单元是单字，
     * 「张三」要写成「张 三」；西文连成一串交给 BPE/词表，遇到不认识的 token native 会忽略。
     */
    private fun tokenize(word: String): String {
        val parts = mutableListOf<String>()
        val latin = StringBuilder()
        for (ch in word) {
            if (ch.code < 128 && ch.isLetterOrDigit()) {
                latin.append(ch)
            } else {
                if (latin.isNotEmpty()) {
                    parts += latin.toString()
                    latin.clear()
                }
                if (!ch.isWhitespace()) parts += ch.toString()
            }
        }
        if (latin.isNotEmpty()) parts += latin.toString()
        return parts.joinToString(" ")
    }
}
