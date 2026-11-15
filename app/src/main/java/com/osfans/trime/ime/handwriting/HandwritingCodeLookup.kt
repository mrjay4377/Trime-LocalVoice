/*
 * 手写候选「提示码」与「联想词」的数据层：全部来自 Rime 已部署目录下的 *.dict.yaml。
 *
 * 提示码来源：扫描部署目录里真实存在的每一份 *.dict.yaml（按文件名去重），全部列给用户在
 * 设置页选择；*.schema.yaml 只用来补中文方案名，default.custom.yaml（回退 default.yaml）
 * 的 schema_list 只用来把已启用方案排在前面。不再列随包兜底字典，也不列 luna_pinyin.dict.yaml。
 *
 * 字典正文常规格式 `字<TAB>码[<TAB>权重]`，也有 `词语<TAB>码` 词组行，少数码表反序写
 * 成 `码<TAB>字`。解析走 runCatching，绝不向调用方抛异常；结果常驻内存、懒加载一次。
 *
 * 联想词：词组行按「首字 → 词组」建索引（常用短词优先），手写单字上屏后用首字取词。
 */
package com.osfans.trime.ime.handwriting

import android.content.Context
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/** 一个可选的提示码来源：对应一份已部署的 Rime 字典。 */
data class HandwritingCodeSource(
    /** 稳定标识：字典文件名（旧版本可能是 "asset:" 前缀，仍兼容读取）。 */
    val id: String,
    /** 显示名：方案中文名，取不到时按文件名推断。 */
    val name: String,
    /** 副标题：字典文件名 + 是否已启用 / 已部署。 */
    val detail: String,
    /** 是否在 default.custom.yaml 的 schema_list 里（仅用于排序）。 */
    val enabled: Boolean,
)

object HandwritingCodeLookup {
    /** 关闭提示码。 */
    const val OFF = ""

    private const val ASSET_PREFIX = "asset:"

    /** 用户明确要求不列出的随包朙月拼音字典。 */
    private const val EXCLUDED_DICT = "luna_pinyin.dict.yaml"

    /** 联想词索引上限：避免超大词库把 IME 进程内存吃满。 */
    private const val MAX_PHRASES = 120_000
    private const val MAX_PER_CHAR = 48

    @Volatile private var cachedSources: List<HandwritingCodeSource>? = null
    private val maps = HashMap<String, Map<Char, String>>()

    /** 联想词索引：首字 -> 词组（短词优先、同长度保持字典顺序）。 */
    private val phraseIndex = HashMap<Char, List<String>>()

    @Volatile private var phrasesReady = false

    @Volatile private var phrasesBuilding = false

    /**
     * 重新部署方案后调用，清掉「来源列表」与「联想词索引」缓存（设置页打开选择器时会先调一次）。
     * 单字编码表缓存按 id 保留：面板发布候选在主线程，不能因刷新列表而重新解析几 MB 文本。
     */
    fun invalidate() {
        synchronized(this) { cachedSources = null }
        synchronized(phraseIndex) {
            phraseIndex.clear()
            phrasesReady = false
            phrasesBuilding = false
        }
    }

    /** 列出所有可选来源；结果缓存，避免每次点按钮都扫目录。 */
    fun sources(context: Context): List<HandwritingCodeSource> =
        cachedSources ?: synchronized(this) {
            cachedSources ?: runCatching { scan(context) }
                .getOrDefault(emptyList())
                .also { cachedSources = it }
        }

    /** 当前来源的显示名（设置页 / 状态栏用）。 */
    fun sourceLabel(
        context: Context,
        id: String,
    ): String =
        if (id == OFF) {
            "关闭"
        } else {
            sources(context).firstOrNull { it.id == id }?.name
                ?: id.removePrefix(ASSET_PREFIX).substringBeforeLast('.').substringAfterLast('/')
        }

    /** 生成主候选栏 comment：多字候选逐字查码，空格连接；查不到返回空串。 */
    fun comment(
        context: Context,
        text: String,
        sourceId: String,
    ): String {
        if (sourceId == OFF || text.isEmpty()) return ""
        val map = dictionary(context, sourceId) ?: return ""
        if (map.isEmpty()) return ""
        val sb = StringBuilder()
        for (ch in text) {
            val code = map[ch] ?: continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(code)
            if (sb.length >= 24) break
        }
        return sb.toString().take(24)
    }

    /** 后台预热：首次出候选前把选中字典读进内存，避免在主线程解析几 MB 文本。 */
    fun warmUp(
        context: Context,
        sourceId: String,
    ) {
        if (sourceId == OFF) return
        runCatching { dictionary(context, sourceId) }
    }

    /** 后台预热联想词索引；在 IO 线程调用一次，之后 associations() 只做纯内存查表。 */
    fun warmUpPhrases(context: Context) {
        if (phrasesReady || phrasesBuilding) return
        synchronized(phraseIndex) {
            if (phrasesReady || phrasesBuilding) return
            phrasesBuilding = true
            runCatching { buildPhraseIndex(context) }
            phrasesReady = true
            phrasesBuilding = false
        }
    }

    /** 单字上屏后的联想词；索引尚未建好时直接返回空，绝不让主线程等后台的文件 IO。 */
    fun associations(
        anchor: Char?,
        limit: Int = 20,
    ): List<String> {
        if (anchor == null || !phrasesReady || phrasesBuilding) return emptyList()
        return synchronized(phraseIndex) {
            phraseIndex[anchor]?.take(limit).orEmpty()
        }
    }

    private fun dictionary(
        context: Context,
        id: String,
    ): Map<Char, String>? =
        synchronized(maps) {
            maps.getOrPut(id) {
                runCatching {
                    val source: InputStream? =
                        if (id.startsWith(ASSET_PREFIX)) {
                            runCatching { context.assets.open(id.removePrefix(ASSET_PREFIX)) }.getOrNull()
                        } else {
                            findDict(context, id)?.inputStream()
                        }
                    if (source == null) emptyMap<Char, String>() else parseDict(source)
                }.getOrDefault(emptyMap<Char, String>())
            }
        }

    /** Rime 数据根目录：跟随 UserDataManager 的存储模式，兼容 EXTERNAL_DIRECT。 */
    private fun roots(context: Context): List<File> =
        buildList {
            runCatching { add(DataManager.userDataDir) }
            runCatching { add(UserDataManager.userDataDir) }
            runCatching { add(DataManager.sharedDataDir) }
            context.getExternalFilesDir(null)?.let { add(File(it, "rime")) }
            add(File(context.filesDir, "rime"))
        }.distinctBy { it.absolutePath }

    /**
     * 按字典文件名定位正文：先查各 root 根下，再在 root 的子目录（如 build/）里找。
     * 部署时字典可能被复制进 build/，只查根目录会读不到编码。
     */
    private fun findDict(
        context: Context,
        id: String,
    ): File? {
        val dirs = roots(context).filter { it.isDirectory }
        dirs.firstNotNullOfOrNull { root -> File(root, id).takeIf { it.isFile } }?.let { return it }
        for (root in dirs) {
            val found =
                runCatching {
                    root
                        .walkTopDown()
                        .maxDepth(3)
                        .firstOrNull { it.isFile && it.name == id }
                }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private data class SchemaMeta(
        val id: String,
        val name: String,
        val dictionary: String,
    )

    /**
     * 扫描部署目录生成可选来源：列出所有真实存在的 *.dict.yaml（排除 luna_pinyin.dict.yaml），
     * *.schema.yaml 补中文名，default.custom.yaml 的 schema_list 决定「已启用」排序。
     */
    private fun scan(context: Context): List<HandwritingCodeSource> {
        val dirs = roots(context).filter { it.isDirectory }

        // 1) default.custom.yaml（回退 default.yaml）：已启用方案，按文件顺序。
        val defaultFile =
            runCatching { File(UserDataManager.userDataDir, "default.custom.yaml") }
                .getOrNull()
                ?.takeIf { it.isFile }
                ?: dirs.firstNotNullOfOrNull { dir ->
                    runCatching {
                        File(dir, "default.custom.yaml").takeIf { it.isFile }
                            ?: File(dir, "default.yaml").takeIf { it.isFile }
                    }.getOrNull()
                }
        val enabledIds = defaultFile?.let { parseSchemaList(it) }.orEmpty()

        // 2) 解析所有 *.schema.yaml：schema_id -> dict 文件名；dict 文件名 -> 中文方案名。
        val dictBySchemaId = HashMap<String, String>()
        val nameByDict = HashMap<String, String>()
        for (dir in dirs) {
            runCatching {
                dir
                    .walkTopDown()
                    .maxDepth(3)
                    .filter { it.isFile && it.name.endsWith(".schema.yaml", ignoreCase = true) }
                    .forEach { f ->
                        val m = parseSchema(f)
                        if (m.id.isEmpty()) return@forEach
                        val dict = (m.dictionary.ifEmpty { m.id }) + ".dict.yaml"
                        dictBySchemaId.putIfAbsent(m.id, dict)
                        if (m.name.isNotEmpty()) nameByDict.putIfAbsent(dict, m.name)
                    }
            }
        }
        val enabledOrder = HashMap<String, Int>()
        enabledIds.forEachIndexed { i, id ->
            dictBySchemaId[id]?.let { enabledOrder.putIfAbsent(it, i) }
        }

        // 3) 扫描全部 *.dict.yaml：按文件名去重，优先前面的 root（用户目录 > 共享目录）。
        val files = LinkedHashMap<String, File>()
        for (dir in dirs) {
            runCatching {
                dir
                    .walkTopDown()
                    .maxDepth(3)
                    .filter {
                        it.isFile &&
                            it.name.endsWith(".dict.yaml", ignoreCase = true) &&
                            !it.name.equals(EXCLUDED_DICT, ignoreCase = true)
                    }.forEach { f -> files.putIfAbsent(f.name, f) }
            }
        }

        // 4) 已启用方案在前（按 schema_list 顺序），其余按名称排列；全部都列出来。
        return files
            .map { (dict, _) ->
                val enabled = dict in enabledOrder
                HandwritingCodeSource(
                    id = dict,
                    name = nameByDict[dict] ?: guessName(dict),
                    detail = "$dict · " + if (enabled) "已启用方案" else "已部署",
                    enabled = enabled,
                )
            }.sortedWith(
                compareBy<HandwritingCodeSource> { enabledOrder[it.id] ?: Int.MAX_VALUE }
                    .thenBy { it.name }
                    .thenBy { it.id },
            )
    }

    /**
     * 解析 schema_list：兼容 default.yaml（顶层 schema_list:）与
     * default.custom.yaml（patch: 下的 schema_list:）。收集缩进在 schema_list:
     * 之下的 `- schema: <id>` 条目，遇到同级或更浅的键即结束。返回顺序即文件中的顺序。
     */
    private fun parseSchemaList(f: File): List<String> {
        val out = ArrayList<String>()
        runCatching {
            f.useLines { lines ->
                var listIndent = -1
                for (raw in lines) {
                    val noComment = raw.substringBefore('#')
                    val trimmed = noComment.trim()
                    if (trimmed.isEmpty()) continue
                    val indent = noComment.length - noComment.trimStart().length
                    if (listIndent < 0) {
                        if (trimmed.startsWith("schema_list:")) listIndent = indent
                        continue
                    }
                    if (indent <= listIndent) break // 遇到同级或更浅的键（如 patch 下其他键）结束
                    val item = trimmed.removePrefix("-").trim()
                    if (item.startsWith("schema:")) {
                        val id = item.substringAfter(':').trim().trim('\'', '"')
                        if (id.isNotEmpty()) out += id
                    }
                }
            }
        }
        return out
    }

    /** 极简 YAML 取值：不引第三方库，只读 schema_id / name / dictionary 三个标量。 */
    private fun parseSchema(f: File): SchemaMeta {
        var id = ""
        var name = ""
        var dictionary = ""
        runCatching {
            f.useLines { lines ->
                for (raw in lines) {
                    val line = raw.substringBefore('#').trim()
                    when {
                        line.startsWith("schema_id:") && id.isEmpty() -> id = scalar(line)
                        // schema 块的 name 出现在最前面，只取第一个，避开 switches 里的 name。
                        line.startsWith("name:") && name.isEmpty() -> name = scalar(line)
                        line.startsWith("dictionary:") && dictionary.isEmpty() -> dictionary = scalar(line)
                    }
                }
            }
        }
        return SchemaMeta(id, name, dictionary)
    }

    private fun scalar(line: String): String =
        line.substringAfter(':').trim().trim('\'', '"')

    private fun guessName(dictName: String): String {
        val n = dictName.lowercase()
        return when {
            "wubi" in n || "wuxing" in n || "wb86" in n || "wb98" in n ->
                "五笔" + when {
                    "86" in n -> "86"
                    "98" in n -> "98"
                    else -> ""
                }
            "stroke" in n || "bihua" in n -> "笔画"
            "pinyin" in n || "quanpin" in n || "shuangpin" in n -> "拼音"
            else -> dictName.removeSuffix(".dict.yaml")
        }
    }

    /** 解析 Rime 字典正文，只保留单字条目；同字多码保留最靠前的一条。 */
    private fun parseDict(input: InputStream): Map<Char, String> {
        val out = HashMap<Char, String>(20000)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var inTable = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("...")) {
                    inTable = true
                    continue
                }
                if (!inTable) {
                    // 有些字典没有 "..." 分隔，遇到第一条词条就当作正文开始。
                    if (hasEntry(line) || phraseFromLine(line) != null) {
                        inTable = true
                        addEntry(out, line)
                    }
                    continue
                }
                addEntry(out, line)
            }
        }
        return out
    }

    /** 建联想词索引：从所有已部署字典的词组行里按首字归集，短词优先。 */
    private fun buildPhraseIndex(context: Context) {
        val dirs = roots(context).filter { it.isDirectory }
        val dictFiles = ArrayList<File>()
        for (dir in dirs) {
            runCatching {
                dir
                    .walkTopDown()
                    .maxDepth(3)
                    .filter { it.isFile && it.name.endsWith(".dict.yaml", ignoreCase = true) }
                    .forEach { f -> dictFiles += f }
            }
        }

        val seenNames = HashSet<String>()
        val perChar = HashMap<Char, LinkedHashSet<String>>()
        var total = 0
        for (f in dictFiles) {
            if (total >= MAX_PHRASES) break
            if (!seenNames.add(f.name)) continue
            runCatching {
                f.useLines { lines ->
                    var inTable = false
                    for (raw in lines) {
                        if (total >= MAX_PHRASES) break
                        if (raw.startsWith("...")) {
                            inTable = true
                            continue
                        }
                        if (!inTable) {
                            if (hasEntry(raw) || phraseFromLine(raw) != null) {
                                inTable = true
                            } else {
                                continue
                            }
                        }
                        val word = phraseFromLine(raw) ?: continue
                        val set = perChar.getOrPut(word[0]) { LinkedHashSet<String>() }
                        if (set.size >= MAX_PER_CHAR) continue
                        if (set.add(word)) total++
                    }
                }
            }
        }

        val result = HashMap<Char, List<String>>(perChar.size)
        for ((ch, set) in perChar) {
            // sortedBy 是稳定排序，同长度内保持字典原有顺序。
            result[ch] = set.sortedBy { it.length }
        }
        phraseIndex.clear()
        phraseIndex.putAll(result)
    }

    /** 从一行字典正文里取出词组（2..6 个汉字），兼容反序码表。 */
    private fun phraseFromLine(line: String): String? {
        val cols = columns(line) ?: return null
        val word =
            when {
                isWord(cols.first) -> cols.first
                isWord(cols.second) && isCode(cols.first) -> cols.second
                else -> null
            }
        return word?.takeIf { it.length <= 6 }
    }

    private fun isWord(s: String): Boolean =
        s.length >= 2 && s.all { it.code in 0x3400..0x9FFF }

    private fun isHan(s: String): Boolean =
        s.length == 1 && s[0].code in 0x3400..0x9FFF

    /** 编码列：五笔/拼音/笔画码都是小写拉丁字母（笔画可能带数字），不含汉字。 */
    private fun isCode(s: String): Boolean =
        s.isNotEmpty() && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    private fun columns(line: String): Pair<String, String>? {
        val tab = line.indexOf('\t')
        if (tab < 0) return null
        val first = line.substring(0, tab).trim()
        val second = line.substring(tab + 1).substringBefore('\t').trim()
        if (first.isEmpty() || second.isEmpty() || second[0] == '#') return null
        return first to second
    }

    private fun hasEntry(line: String): Boolean {
        val cols = columns(line) ?: return false
        return isHan(cols.first) || (isHan(cols.second) && isCode(cols.first))
    }

    /**
     * 标准 Rime 字典是 `字<TAB>码`；五笔「字词」码表还常含 `词语<TAB>码` 的词组行，
     * 第一列多于一个字会被跳过。少数码表反过来写成 `码<TAB>字`，这里也一并兼容。
     */
    private fun addEntry(
        out: MutableMap<Char, String>,
        line: String,
    ) {
        val cols = columns(line) ?: return
        val a = cols.first
        val b = cols.second
        when {
            isHan(a) -> out.putIfAbsent(a[0], b)
            isHan(b) && isCode(a) -> out.putIfAbsent(b[0], a)
        }
    }
}
