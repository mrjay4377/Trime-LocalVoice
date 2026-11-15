/*
 * 统一的「热词 / 上下文词库」文件：使用者资料夹 / voice / hotwords.txt。
 *
 * 之前热词分散在三处：自定义热词在私有 SharedPreferences、Rime 词库是 30 分钟内存快照、
 * 输入框上下文是运行时字符串。本文件把「可持久、可编辑、可跨设备拷贝」的静态词条
 * 收拢到一个纯文本文件里，用户可直接用文本编辑器改，或拷到另一台设备复用。
 *
 * 运行时仍会动态合并两路来源（不写入本文件，避免运行时反复改写用户文件）：
 *  - Rime 用户词库快照（VoiceHotwords.refreshDictionary，30 分钟刷新一次）
 *  - 输入框光标前文字（TrimeInputMethodService.voiceContextText）
 * 设置页的「从 Rime 用户词库导入」可以把动态快照固化进本文件。
 *
 * 线程约定：按住语音键的主线程只做内存读取 + 每 3 秒最多一次 stat；
 * 创建文件、写回、导入一律在后台或设置页完成，绝不走 VoiceModels.ensureRoot() 的写探针。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.SystemClock
import timber.log.Timber
import java.io.File

object VoiceHotwordBook {
    const val FILE_NAME = "hotwords.txt"

    /** 文件里词条上限；真正喂给 ASR 的仍由 VoiceHotwords.MAX_WORDS 收敛到 60 条。 */
    private const val MAX_FILE_WORDS = 200
    private const val MAX_WORD_LENGTH = 12
    private const val CHECK_INTERVAL_MS = 3_000L

    private val lock = Any()

    @Volatile
    private var words: List<String> = emptyList()

    @Volatile
    private var loaded = false

    @Volatile
    private var stamp = -1L

    @Volatile
    private var size = -1L

    @Volatile
    private var lastCheck = 0L

    /** 上一次成功加载的文件绝对路径；路径变了必须强制重读，不能只看 mtime / size。 */
    @Volatile
    private var loadedPath: String? = null

    /**
     * 纯只读定位，候选目录与 VoiceModels.readOnlyRoot() 一致，读写永远指向同一个文件。
     * 不再把路径永久缓存在进程里：切换使用者资料夹 / 存储模式后，旧路径会让词库
     * 一直读写已经不存在的目录。ensureFresh 每 3 秒才走到这里，重算只做几次 stat。
     */
    private fun bookFile(context: Context): File =
        File(VoiceModels.readOnlyRoot(context), FILE_NAME)

    fun filePath(context: Context): String = bookFile(context).absolutePath

    /** 内存快照，主线程可安全调用。 */
    fun words(): List<String> = words

    fun wordCount(): Int = words.size

    /**
     * 主线程调用：首次或用户改动文件后重读。只在 [CHECK_INTERVAL_MS] 之外做一次 stat，
     * 文件没变就完全不读磁盘；首次不存在时也只置空，不创建文件（创建交给后台）。
     */
    fun ensureFresh(context: Context) {
        val now = SystemClock.elapsedRealtime()
        // 节流判据不能带 loaded：读失败时 loaded=false，否则会退化成每帧读盘。
        // lastCheck==0 表示进程内尚未检查过，首次按键立即加载。
        if (lastCheck != 0L && now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now
        reload(context, create = false)
    }

    /**
     * 读文件到内存。路径 + mtime + 长度都未变时直接返回。
     * @param create 文件不存在时是否创建带说明头的模板；主线程路径必须传 false。
     * @param force 忽略 mtime 判定强制重读（设置页手动重载、刚写回文件后使用）。
     */
    fun reload(
        context: Context,
        create: Boolean = true,
        force: Boolean = false,
    ): File {
        val file = bookFile(context)
        val path = file.absolutePath
        synchronized(lock) {
            val unchanged = !force && loaded && loadedPath == path && file.isFile &&
                file.lastModified() == stamp && file.length() == size
            when {
                unchanged -> Unit
                !file.isFile && (!create || !createTemplate(context, file)) -> {
                    // 主线程首次读取且文件尚未创建：词库为空即可，等后台去建文件。
                    // loadedPath 留空，文件一旦出现下一轮就会真正读进来。
                    words = emptyList()
                    loaded = true
                    loadedPath = null
                    stamp = -1L
                    size = -1L
                }
                else -> loadInto(file)
            }
        }
        return file
    }

    private fun loadInto(file: File) {
        val result = runCatching { file.readText(Charsets.UTF_8) }
        result.onFailure { Timber.w(it, "voice hotword book: read failed, will retry") }
        if (result.isFailure) {
            // 读失败绝不能固化 loaded=true + 空词库：那会让 unchanged 恒真，
            // 热词永久静默失效。保留上一份成功快照，交给 3 秒后的 ensureFresh 重试。
            loaded = false
            loadedPath = null
            stamp = -1L
            size = -1L
            return
        }
        words = parse(result.getOrThrow())
        stamp = file.lastModified()
        size = file.length()
        loadedPath = file.absolutePath
        loaded = true
        Timber.d("voice hotword book: %d words from %s", words.size, file.absolutePath)
    }

    /** 设置页读取全文供编辑；文件不存在时返回模板文本，不写盘。 */
    fun text(context: Context): String {
        val file = bookFile(context)
        return if (file.isFile) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrDefault(template(""))
        } else {
            template(LocalVoiceSettings.hotwords(context))
        }
    }

    /** 设置页保存整份文本；成功后立即刷新内存，下一次按键就生效。 */
    fun saveText(
        context: Context,
        text: String,
    ): Boolean {
        val file = bookFile(context)
        return runCatching {
            val dir = file.parentFile
            if (dir != null && !dir.isDirectory) dir.mkdirs()
            file.writeText(text, Charsets.UTF_8)
            // 用同一个 File 刷新内存：二次解析路径时若目录刚被切走，会「写旧文件、读新文件」。
            synchronized(lock) { loadInto(file) }
            true
        }.onFailure { Timber.w(it, "voice hotword book: save failed") }
            .getOrDefault(false)
    }

    /**
     * 把当前 Rime 用户词库快照固化进文件（只追加文件里还没有的词）。
     * 由设置页按钮触发，属于一次性手动操作；返回新增条数。
     */
    fun importFromRime(context: Context): Int {
        val file = reload(context, create = true)
        val existing = LinkedHashSet<String>(words)
        if (existing.size >= MAX_FILE_WORDS) return 0
        val fresh = runCatching { VoiceHotwords.collectRimeWords(context) }
            .onFailure { Timber.w(it, "voice hotword book: rime export failed") }
            .getOrDefault(emptyList())
        val addition = StringBuilder()
        var added = 0
        for (word in fresh) {
            if (existing.size >= MAX_FILE_WORDS) break
            if (word.length > MAX_WORD_LENGTH || !VoiceHotwords.isUsableWord(word)) continue
            if (!existing.add(word)) continue
            addition.append(word).append('\n')
            added += 1
        }
        if (added == 0) return 0
        val original = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault(template(""))
        val merged =
            if (original.endsWith("\n")) {
                original + addition.toString()
            } else {
                original + "\n" + addition.toString()
            }
        return if (runCatching { file.writeText(merged, Charsets.UTF_8) }.isSuccess) {
            synchronized(lock) { loadInto(file) }
            added
        } else {
            0
        }
    }

    /** 每行一个词；# 开头为注释，词后也可跟 # 备注；空行忽略。 */
    private fun parse(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            if (out.size >= MAX_FILE_WORDS) break
            val word = raw.substringBefore('#').trim()
            if (word.isEmpty() || word.length > MAX_WORD_LENGTH) continue
            if (VoiceHotwords.isUsableWord(word)) out += word
        }
        return out.toList()
    }

    /** 首次创建：写说明头，并把旧版私有 SharedPreferences 里的自定义热词迁移过来。 */
    private fun createTemplate(
        context: Context,
        file: File,
    ): Boolean =
        runCatching {
            val dir = file.parentFile
            if (dir != null && !dir.isDirectory) dir.mkdirs()
            val legacy = LocalVoiceSettings.hotwords(context)
            file.writeText(template(legacy), Charsets.UTF_8)
            true
        }.onFailure { Timber.w(it, "voice hotword book: cannot create %s", file.absolutePath) }
            .getOrDefault(false)

    private fun template(legacy: String): String {
        val sb = StringBuilder()
        sb.append("# 本地语音 · 热词 / 上下文词库\n")
        sb.append("# 每行一个词；# 开头为注释，词后也可跟 # 备注；空行忽略。\n")
        sb.append("# 词条长度 2-12 字，最多保存 200 条，识别时取前 60 条。\n")
        sb.append("# 保存后重新弹出键盘（或按一次语音键）即生效；本文件可直接编辑、可拷到其它设备的 voice 目录复用。\n")
        sb.append("# 运行时还会动态合并 Rime 用户词库快照与光标前 120 字上下文，这两路不写入本文件。\n")
        sb.append("# 例：张三\n#     同文输入法\n")
        sb.append('\n')
        for (word in splitLegacy(legacy)) sb.append(word).append('\n')
        return sb.toString()
    }

    /** 旧版自定义热词是逗号 / 顿号 / 空格 / 换行分隔的一整串，复用原有解析规则。 */
    private fun splitLegacy(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        VoiceHotwords.parseCustom(text, out, MAX_FILE_WORDS)
        return out.toList().take(MAX_FILE_WORDS)
    }
}
