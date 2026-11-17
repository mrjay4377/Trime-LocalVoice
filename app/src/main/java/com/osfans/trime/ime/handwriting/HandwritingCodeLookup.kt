/*
 * 手写候选「提示码」与「联想词」的数据层：全部来自 Rime 已部署目录下的 *.dict.yaml。
 *
 * 提示码来源：只列「已启用方案」（default.custom.yaml 的 schema_list）引用的字典，按启用
 * 顺序去重；*.schema.yaml 只用来补中文方案名。不再全量扫 *.dict.yaml —— 部署目录里同名
 * dict 在多个 root（用户目录/共享目录/build/）各有一份，全量扫会列出大量重复条目。
 * 未启用任何方案时 sources() 返回空，设置页只保留「关闭提示码」。不列 luna_pinyin.dict.yaml。
 *
 * 字典正文常规格式 `字<TAB>码[<TAB>权重]`，也有 `词语<TAB>码` 词组行，少数码表反序写
 * 成 `码<TAB>字`。解析走 runCatching，绝不向调用方抛异常；结果常驻内存、懒加载一次。
 *
 * 联想词：词组行按「首字 → 词组」建索引（常用短词优先），手写单字上屏后用首字取词；
 * 上屏后发布联想词时会过滤掉以该已上屏字开头且更长的词组（如「叶」上屏后「叶子」），
 * 避免再点它上屏与已上屏字拼成「叶叶子」之类重复。
 */
package com.osfans.trime.ime.handwriting

import android.content.Context
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import timber.log.Timber

/** 一个可选的提示码来源：对应一份已部署的 Rime 字典。 */
data class HandwritingCodeSource(
    /** 稳定标识：方案 id（唯一，如 luna_quanpin）；旧版本可能是 dict 名 / "asset:" 前缀，仍兼容。 */
    val id: String,
    /** 显示名：方案中文名，取不到时按文件名推断。 */
    val name: String,
    /** 副标题：字典文件名 + 是否已启用 / 已部署。 */
    val detail: String,
    /** 是否在 default.custom.yaml 的 schema_list 里（仅用于排序）。 */
    val enabled: Boolean,
    /** 实际字典文件名，用于查码与兼容旧配置（旧配置存的 id 就是它）。 */
    val dictName: String = "",
)

object HandwritingCodeLookup {
    /** 关闭提示码。 */
    const val OFF = ""

    private const val ASSET_PREFIX = "asset:"

    /** 联想词索引上限：避免超大词库把 IME 进程内存吃满。 */
    private const val MAX_PHRASES = 120_000
    private const val MAX_PER_CHAR = 48

    /**
     * 内置「方案 → 主词库」映射（参照 CyIME 共享码表固定枚举）：
     * 这些方案在 schema 里用 __include 继承 / 共享码表、未声明独立 dictionary，
     * 但它们的编码提示应取自共享的主 dict，直接退回 schema_id 会指向不存在的字典。
     * key 为 schema_id，value 为主 dict 的基名（不含 .dict.yaml）。
     */
    private val KNOWN_DICT_BY_SCHEMA: Map<String, String> =
        mapOf(
            // 朙月拼音系：全拼/简拼/流畅/繁体共享 luna_pinyin.dict.yaml。
            "luna_quanpin" to "luna_pinyin",
            "luna_pinyin" to "luna_pinyin",
            "luna_pinyin_simp" to "luna_pinyin",
            "luna_pinyin_fluency" to "luna_pinyin",
            "luna_pinyin_tw" to "luna_pinyin",
            // 常见「拼音」别名（用户 schema 可能叫 pinyin / py）。
            "pinyin" to "luna_pinyin",
            "py" to "luna_pinyin",
            // 笔画（独立 dict，保留）。
            "stroke" to "stroke",
            // 五笔86 系：不同发行版 dict 基名不一，优先用 schema 自身声明（已在上面 m.dictionary
            // 命中），此处只兜底最常见的 wubi86fc / wubi86。
            "wubi86fc" to "wubi86fc",
            "wubi86" to "wubi86",
        )

    /**
     * 内置「参照 CyIME 共享码表固定枚举」的候选提示码方案清单（id=方案 id、name=显示名、dict=主码表）。
     * 无条件作为基础列表列出，解决在设置页场景引擎 enabledSchemata() 拿不到（rime 未 ready /
     * 未部署）、default.custom.yaml 又解析不到时弹窗只剩「关闭提示码」的问题。
     * 运行时检测到的额外方案追加在其后，因此换方案也不会丢。
     */
    private data class KnownSchema(val id: String, val name: String, val dict: String)

    private val KNOWN_SCHEMAS =
        listOf(
            KnownSchema("luna_quanpin", "全拼", "luna_pinyin"),
            KnownSchema("pinyin", "拼音", "luna_pinyin"),
            KnownSchema("stroke", "笔画", "stroke"),
            KnownSchema("wubi86fc", "五笔86", "wubi86fc"),
        )

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

    /**
     * 带「引擎权威启用方案」的列出：[enabledSchemas] 是 Rime enabledSchemata() 返回的
     * 当前实际启用方案（id + 中文名），比解析 default.custom.yaml 更可靠（不依赖文件缩进/写法）。
     * 优先用它列方案；解析出的 dict 映射仍用来补「该方案用哪本 dict」与共享码表修正。
     * 不走缓存（方案开关随时在变）。
     */
    fun sources(
        context: Context,
        enabledSchemas: List<Pair<String, String>>?,
    ): List<HandwritingCodeSource> =
        runCatching { scan(context, enabledSchemas) }.getOrDefault(emptyList())

    /** 当前来源的显示名（设置页 / 状态栏用）。 */
    fun sourceLabel(
        context: Context,
        id: String,
    ): String =
        if (id == OFF) {
            "关闭"
        } else {
            sources(context).firstOrNull { it.id == id || it.dictName == id }?.name
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

    /**
     * 诊断串：显示当前来源 id 解析成哪本字典、是否找到、载入多少字、样例码。
     * 用于手写面板状态条自查「设置了提示码却没显示」的问题。
     */
    fun diagnose(
        context: Context,
        id: String,
    ): String {
        if (id == OFF) return "提示码：关闭"
        val dict = resolveDict(id)
        val present = findDict(context, dict)?.absolutePath ?: "无部署文件"
        val asset = runCatching { context.assets.open("shared/$dict") != null }.getOrDefault(false)
        val map = dictionary(context, id).orEmpty()
        val sample = map.entries.firstOrNull()?.let { "${it.key}=${it.value}" } ?: "无"
        return "码源=$id 字典=$dict 部署=$present assets=$asset 字=${map.size} 样例=$sample"
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
            // 关键：空结果绝不缓存。某次字典未就绪（部署中/IO 抖动）若把空表写进缓存，
            // 该方案从此永久查不到码，表现为「有时有码有时没码」的跷跷板现象。
            val cached = maps[id]
            if (cached != null && cached.isNotEmpty()) cached else {
            // id 可能是方案 id（新）或 dict 名（旧）：统一解析成 dict 文件名再找文件。
            val dictName = resolveDict(id)
            // 直接读部署目录（用户实际部署的码表最准确，也是原先能工作的路径）；
            // 找不到或解析为空再用 assets 内置码表兜底。逐个试，取第一份真正解析出码的。
            val candidates = LinkedHashMap<String, () -> InputStream?>()
            if (dictName.startsWith(ASSET_PREFIX)) {
                val p = dictName.removePrefix(ASSET_PREFIX)
                candidates["asset:$p"] = { runCatching { context.assets.open(p) }.getOrNull() }
            } else {
                // 1) 部署目录：精确文件名（resolveDict 结果）。
                findDict(context, dictName)?.let { f ->
                    candidates["deploy:${f.name}"] = { runCatching { f.inputStream() }.getOrNull() }
                }
                // 2) 五笔等方案基名不固定（wubi86fc/wubi86/wb86/wbx）：
                //    按关键字把部署目录里所有候选都找出来，逐个试，跳过空壳继续。
                if (dictName.contains("wubi", true) || dictName.contains("wb86", true) || dictName.contains("wbx", true)) {
                    for (kw in listOf("wubi", "wb86", "wbx")) {
                        for (f in findDictsByKeyword(context, kw)) {
                            candidates.putIfAbsent("fuzzy:${f.absolutePath}") {
                                runCatching { f.inputStream() }.getOrNull()
                            }
                        }
                    }
                }
                // 3) assets 内置兜底（拼音 luna_pinyin / 笔画 stroke 在包里就有完整码表）。
                candidates["asset:$dictName"] = {
                    runCatching { context.assets.open("shared/$dictName") }.getOrNull()
                }
            }
            var result = emptyMap<Char, String>()
            for ((label, opener) in candidates) {
                // parseDict 内部已 use 关闭流，这里不要再包一层 use。
                val parsed =
                    runCatching {
                        opener()?.let { parseDict(it) } ?: emptyMap<Char, String>()
                    }.getOrDefault(emptyMap())
                if (parsed.isNotEmpty()) {
                    Timber.tag("HwCode").w("dictionary %s -> %s (%d chars)", id, label, parsed.size)
                    result = parsed
                    break
                }
            }
            if (result.isEmpty()) Timber.tag("HwCode").w("dictionary %s: NO TABLE (tried %s)", id, candidates.keys)
            // 只缓存非空结果。
            if (result.isNotEmpty()) maps[id] = result
            result
            }
        }

    /** 在部署目录里按文件名关键字模糊找「所有」候选字典（用于五笔等基名不固定的方案）。
     *  返回全部匹配，调用方逐个尝试解析，遇到空壳文件也能继续找下一份。 */
    private fun findDictsByKeyword(
        context: Context,
        keyword: String,
    ): List<File> {
        val out = ArrayList<File>()
        for (root in roots(context).filter { it.isDirectory }) {
            runCatching {
                root
                    .walkTopDown()
                    .maxDepth(4)
                    .filter {
                        it.isFile && it.name.endsWith(".dict.yaml", ignoreCase = true) &&
                            it.name.contains(keyword, ignoreCase = true)
                    }
                    .forEach { out += it }
            }
        }
        return out.distinctBy { it.absolutePath }
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
     * 扫描部署目录生成可选来源：列出「已启用方案」（default.custom.yaml 的 schema_list）
     * 引用的字典，按启用顺序去重；*.schema.yaml 补中文名。未启用任何方案时返回空，
     * 设置页只保留「关闭提示码」。
     */
    private fun scan(
        context: Context,
        enabledSchemas: List<Pair<String, String>>? = null,
    ): List<HandwritingCodeSource> {
        val dirs = roots(context).filter { it.isDirectory }

        // 1) 已启用方案 id：优先用引擎权威的 enabledSchemata()（id+中文名，最可靠，不依赖 yaml 写法/缩进）；
        //    拿不到再解析 default.custom.yaml（回退 default.yaml）。
        val fileBasedIds: List<String> =
            runCatching {
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
                defaultFile?.let { parseSchemaList(it) }.orEmpty()
            }.getOrDefault(emptyList())
        // 引擎权威列表的 id（保持引擎顺序）；没有则退回文件解析。
        val enabledIds =
            if (enabledSchemas.isNullOrEmpty()) fileBasedIds
            else enabledSchemas.map { it.first }
        val engineNames = enabledSchemas?.associate { it.first to it.second }.orEmpty()
        // release 包 Timber 只输出 INFO 及以上，诊断用 w 级 + 固定 tag HwCode 方便 logcat 过滤。
        Timber.tag("HwCode").w(
            "scan roots=%s engineSchemas=%s fileBasedIds=%s usedIds=%s",
            dirs.map { it.absolutePath }, enabledSchemas, fileBasedIds, enabledIds,
        )
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
                        // 参照 CyIME「共享码表固定枚举」：schema 未声明独立 dictionary 时
                        // （如 luna_quanpin 继承 luna_pinyin、双拼系共享同一码表），
                        // 先查内置映射，再退回 schema_id，保证提示码指向真实存在的 dict。
                        val dict =
                            if (m.dictionary.isNotEmpty()) m.dictionary + ".dict.yaml"
                            else (KNOWN_DICT_BY_SCHEMA[m.id] ?: m.id) + ".dict.yaml"
                        dictBySchemaId.putIfAbsent(m.id, dict)
                        if (m.name.isNotEmpty()) nameByDict.putIfAbsent(dict, m.name)
                    }
            }
        }
        Timber.tag("HwCode").w(
            "dictMap=%s",
            dictBySchemaId,
        )
        // 3) 列「已启用方案」对应的字典：schema 解析到的 dict 优先，解析不到时退回内置映射
        //    （如 pinyin/wubi86fc 的 schema 不在部署目录，仍能用 KNOWN_DICT_BY_SCHEMA 给出主 dict），
        //    最后退回 schema_id 本身。保证启用的每个方案都有一项可查。
        //    显示名优先级：引擎权威中文名 > 该方案 schema 的 name > dict 名推测。
        val nameBySchemaId = HashMap<String, String>()
        for (dir in dirs) {
            runCatching {
                dir
                    .walkTopDown()
                    .maxDepth(3)
                    .filter { it.isFile && it.name.endsWith(".schema.yaml", ignoreCase = true) }
                    .forEach { f ->
                        val m = parseSchema(f)
                        if (m.id.isNotEmpty() && m.name.isNotEmpty()) nameBySchemaId.putIfAbsent(m.id, m.name)
                    }
            }
        }
        // 4) 组装：内置固定方案（参照 CyIME 共享码表枚举）为基础，运行时检测到的方案合并进去。
        //    内置项保证即使引擎未 ready / yaml 解析不到，弹窗也有全拼/拼音/笔画/五笔86 可选；
        //    内置 4 个方案强制用固定中文名（设备 schema 的 name 可能是「微软双拼」等，不是用户想看的
        //    启用方案名），引擎中文名只用于额外方案。按「方案 id」去重（每个启用方案一行，全拼/拼音虽
        //    共用 luna_pinyin.dict.yaml 仍各占一行）；HandwritingCodeSource.id 仍存 dict 名，查码链路不变。
        data class Row(val schemaId: String, val dict: String, val name: String, val enabled: Boolean)

        val rows = LinkedHashMap<String, Row>() // key = 方案 id
        fun add(id: String, fixedName: String?, fromEngine: Boolean) {
            val dict = dictBySchemaId[id] ?: (KNOWN_DICT_BY_SCHEMA[id] ?: id) + ".dict.yaml"
            val name =
                fixedName
                    ?: engineNames[id]?.takeIf { it.isNotEmpty() }
                    ?: nameBySchemaId[id]
                    ?: guessName(dict)
            val prev = rows[id]
            if (prev == null) {
                rows[id] = Row(id, dict, name, fromEngine)
            } else if (fromEngine && !prev.enabled) {
                rows[id] = Row(id, dict, name, true)
            }
        }
        // 内置基础项先入列（固定名 + 固定顺序）；enabled 由引擎/文件是否含该 id 决定。
        KNOWN_SCHEMAS.forEach { add(it.id, it.name, it.id in enabledIds) }
        // 运行时检测到的额外方案（用户自定义方案）：复用引擎中文名或 schema 名。
        val knownIds = KNOWN_SCHEMAS.map { it.id }.toSet()
        dictBySchemaId.keys.filter { it !in knownIds }.forEach { add(it, null, it in enabledIds) }
        enabledIds.filter { it !in knownIds }.forEach { add(it, null, true) }

        Timber.tag("HwCode").w("rows=%s", rows.values.map { "${it.name}(${it.schemaId}->${it.dict})" })
        return rows.values.map {
            HandwritingCodeSource(
                // id 用方案 id（唯一）：全拼/拼音虽共用 luna_pinyin.dict.yaml，但作为两个独立可选项，
                // 各自 id 不同，选中态才不会串。dict 名只用于查码（见 dictionary()）和兼容旧配置。
                id = it.schemaId,
                name = it.name,
                detail = "${it.dict} · ${if (it.enabled) "已启用方案" else "内置码表"}",
                enabled = it.enabled,
                dictName = it.dict,
            )
        }
    }

    /**
     * 把来源 id 解析成实际字典文件名。新版本 id = 方案 id（如 luna_quanpin），需要映射回 dict；
     * 旧版本存的 id 直接是 dict 名（如 luna_pinyin.dict.yaml / asset:xxx），原样返回以兼容老配置。
     */
    private fun resolveDict(id: String): String =
        when {
            id.startsWith(ASSET_PREFIX) || id.endsWith(".dict.yaml") -> id
            KNOWN_DICT_BY_SCHEMA.containsKey(id) -> KNOWN_DICT_BY_SCHEMA[id] + ".dict.yaml"
            else -> id + ".dict.yaml"
        }

    /**
     * 解析 schema_list：兼容 default.yaml（顶层 schema_list:）与
     * default.custom.yaml（patch: 下的 schema_list:）。同时支持两种 YAML 写法：
     *  - 块状：`- schema: wubi86` / `- schema: 'wubi86'`
     *  - flow：`- {schema: wubi86}` / `- schema=wubi86`
     * 收集 schema_list: 之下的所有条目，遇到同级或更浅的键即结束。返回顺序即文件中的顺序。
     */
    private fun parseSchemaList(f: File): List<String> {
        val out = ArrayList<String>()
        runCatching {
            f.useLines { lines ->
                var listIndent = -1 // schema_list: 所在行的缩进
                for (raw in lines) {
                    val noComment = raw.substringBefore('#')
                    val trimmed = noComment.trim()
                    if (trimmed.isEmpty()) continue
                    val indent = noComment.length - noComment.trimStart().length
                    val startsItem = trimmed.startsWith("-")
                    if (listIndent < 0) {
                        if (trimmed.startsWith("schema_list:")) listIndent = indent
                        continue
                    }
                    // 列表项（以 - 开头）只要缩进 >= schema_list 行就继续收集：
                    // 兼容 flow（- {schema: x}）与 schema_list 同级缩进、或更深缩进两种写法。
                    if (startsItem && indent >= listIndent) {
                        val item = trimmed.removePrefix("-").trim()
                        // 先试 block：- schema: xxx
                        if (item.startsWith("schema:")) {
                            val id = item.substringAfter(':').trim().trim('\'', '"')
                            if (id.isNotEmpty()) out += id
                        } else {
                            // flow / 等号 / 裸 id
                            val m = Regex("""schema\s*[=:]\s*['"]?([^'"}\s]+)""").find(item)
                            if (m != null) {
                                out += m.groupValues[1]
                            } else if (item.isNotEmpty() && !item.startsWith("schema_list")) {
                                val bare = item.removeSurrounding("{", "}").removeSurrounding("}", "{")
                                    .trim().trim('\'', '"')
                                if (bare.isNotEmpty()) out += bare
                            }
                        }
                        continue
                    }
                    // 非列表项（普通键）且缩进 <= schema_list 行：说明 schema_list 块结束。
                    if (!startsItem && indent <= listIndent) break
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
