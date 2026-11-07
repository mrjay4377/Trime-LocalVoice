/*
 * 离线模型下载 + 自动安装。
 * 下载走 HTTPS（HttpURLConnection，支持 Range 断点续传与多源回退），不引入第三方网络库，
 * 以免把刚精简掉的 APK 体积又加回来。压缩包解压、逐文件 SHA-256 校验、原子发布与
 * 自动设为当前引擎的逻辑参考 trime-mie / BiBi-Keyboard（Apache-2.0）。
 *
 * 安装目标为「使用者资料夹 / voice/<家族>/<变体>」（默认）；资料夹无法按真实路径读写时
 * 自动回退到应用私有外部目录，旧位置的模型仍会被扫描到。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ModelIntegrityException(message: String) : IOException(message)

/** voice 目录里真实存在的一套模型，供「删除已安装模型」统一管理。 */
data class InstalledModel(
    val dir: File,
    val label: String,
    val sizeBytes: Long,
    /** true = 模型平铺在 voice 根目录；删除时只删模型文件，保留 README / .nomedia / installed.txt。 */
    val flatRoot: Boolean,
)

/**
 * 本地语音包：包括一键下载安装的规格模型、ZIP 导入的 import_* 目录、
 * 以及用户手动放到 voice 里的目录。设置页据此列出「可直接选用的本地包」。
 */
data class LocalVoicePackage(
    val familyId: String,
    /** 目录名；空串表示平铺在 voice 根目录，用自动扫描。 */
    val key: String,
    val label: String,
    val sizeBytes: Long,
    val dir: File,
    /** 平铺在 voice 根目录时删除只能删模型文件，不能递归删掉整个 voice。 */
    val flatRoot: Boolean,
)

object ModelInstaller {
    /**
     * 删除 / 替换模型文件前，必须先让 native 识别器真正释放：
     * 旧实现是 fire-and-forget 的 unload()，文件可能正在被 ONNX 会话占用，
     * 释放与删除并发时会触发 native 崩溃，表现为「删除模型后 APP 直接退出」。
     */
    suspend fun releaseEngines() {
        SherpaOnlineManager.unloadNow()
        SherpaOfflineManager.getInstance().unloadNow()
    }

    const val PHASE_DOWNLOAD = "下载模型"
    const val PHASE_EXTRACT = "解压模型"
    const val PHASE_VERIFY = "校验文件"
    const val PHASE_INSTALL = "安装模型"
    const val PHASE_IMPORT = "导入本地模型"
    const val PHASE_READY = "完成"

    /** 安装后的目录：voice/<家族>/<变体>。 */
    fun installedDir(context: Context, variant: ModelVariant): File =
        File(File(VoiceModels.primaryRoot(context), variant.familyId), variant.key)

    fun isInstalled(context: Context, variant: ModelVariant): Boolean {
        val dir = installedDir(context, variant)
        return variant.files.all { spec ->
            File(dir, spec.name).let { it.isFile && it.length() == spec.sizeBytes }
        }
    }

    fun installedKeys(context: Context, familyId: String): Set<String> =
        ModelCatalog.variantsOf(familyId).filter { isInstalled(context, it) }.map { it.key }.toSet()

    suspend fun uninstall(context: Context, variant: ModelVariant): Boolean {
        releaseEngines()
        val dir = installedDir(context, variant)
        if (LocalVoiceSettings.selectedVariant(context, variant.familyId) == variant.key) {
            LocalVoiceSettings.setSelectedVariant(context, variant.familyId, "")
        }
        val result = runCatching { !dir.exists() || dir.deleteRecursively() }.getOrDefault(false)
        writeInstalledIndex(context)
        return result
    }

    /**
     * 扫描 voice 目录里所有真实存在的模型：下载安装的规格目录、ZIP 导入的 import_* 目录、
     * 手动平铺/解压的目录，以及安装失败留下的 .xxx.installing / .xxx.previous 残留。
     * 只有目录内含受支持家族的模型文件才算一套模型，避免把普通文件夹列进来。
     */
    fun installedModels(context: Context): List<InstalledModel> {
        val result = mutableListOf<InstalledModel>()
        val primary = VoiceModels.primaryRoot(context).absolutePath

        // v42 起主目录跟随「使用者资料夹」，旧版模型可能还留在应用私有目录；
        // 两个位置都要列出来，否则换目录后旧模型会在「删除模型」里凭空消失。
        fun scanRoot(root: File) {
            if (!root.isDirectory) return
            val legacy = root.absolutePath != primary
            val flatFiles = root.listFiles()?.filter(::isModelFile).orEmpty()
            if (flatFiles.isNotEmpty()) {
                result += InstalledModel(
                    dir = root,
                    label = if (legacy) "根目录（手动平铺 · 旧位置）" else "根目录（手动平铺）",
                    sizeBytes = flatFiles.sumOf { it.length() },
                    flatRoot = true,
                )
            }

            fun scan(parent: File, depth: Int) {
                if (depth > 2) return
                parent.listFiles()?.forEach { dir ->
                    if (!dir.isDirectory || dir.name == "__MACOSX") return@forEach
                    val hasModel = dir.listFiles()?.any(::isModelFile) == true
                    if (hasModel) {
                        val label = when {
                            dir.name.startsWith(".") -> "${dir.name}（安装残留）"
                            depth == 0 -> dir.name
                            else -> "${parent.name}/${dir.name}"
                        }
                        result += InstalledModel(dir, label, dirSize(dir), flatRoot = false)
                        // 已作为一整套列出，不再把内部子目录重复列一遍
                    } else {
                        scan(dir, depth + 1)
                    }
                }
            }
            scan(root, 0)
        }

        VoiceModels.roots(context).forEach(::scanRoot)
        return result.distinctBy { it.dir.absolutePath }.sortedBy { it.label }
    }

    /** 删除选中的模型目录，返回实际释放的字节数；同时重置选中项并卸载识别器缓存。 */
    suspend fun deleteModels(context: Context, models: List<InstalledModel>): Long {
        releaseEngines()
        val app = context.applicationContext
        var freed = 0L
        models.forEach { model ->
            val dir = model.dir
            if (!dir.exists()) return@forEach
            val files = dir.listFiles()?.filter(::isModelFile).orEmpty()
            val before = if (model.flatRoot) files.sumOf { it.length() } else dirSize(dir)
            val ok = if (model.flatRoot) {
                files.all { file -> runCatching { file.delete() }.getOrDefault(false) }
            } else {
                runCatching { dir.deleteRecursively() }.getOrDefault(false)
            }
            if (ok) freed += before
        }
        // 选中项指向已被删掉的目录时清空，避免设置页仍显示「已就绪」而引擎找不到文件。
        VoiceModels.FAMILIES.forEach { family ->
            val selected = LocalVoiceSettings.selectedVariant(app, family.id)
            if (selected.isNotBlank() &&
                !File(File(VoiceModels.primaryRoot(app), family.id), selected).exists()
            ) {
                LocalVoiceSettings.setSelectedVariant(app, family.id, "")
            }
        }
        writeInstalledIndex(app)
        return freed
    }

    /**
     * 列出 voice 目录里所有可直接选用的本地语音包，并推断所属引擎家族。
     * 下载安装的规格目录用父目录名；手动放置的目录按「必需文件是否齐全」回推家族。
     */
    fun localPackages(context: Context): List<LocalVoicePackage> {
        val fallbackFamily = LocalVoiceSettings.vendor(context)
        return installedModels(context).mapNotNull { model ->
            if (model.flatRoot) {
                return@mapNotNull LocalVoicePackage(
                    familyId = fallbackFamily,
                    key = "",
                    label = "voice 根目录（手动平铺）",
                    sizeBytes = model.sizeBytes,
                    dir = model.dir,
                    flatRoot = true,
                )
            }
            val parent = model.dir.parentFile?.name
            val byParent = parent?.let { VoiceModels.family(it)?.id }
            val names = model.dir.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name.lowercase(Locale.ROOT) }
                ?.toSet()
                .orEmpty()
            val byFiles = VoiceModels.FAMILIES.firstOrNull { family ->
                family.groups.all { group -> group.any { it.lowercase(Locale.ROOT) in names } }
            }?.id
            val familyId = byParent ?: byFiles ?: fallbackFamily
            LocalVoicePackage(
                familyId = familyId,
                key = model.dir.name,
                label = VoiceModels.family(familyId)?.label?.substringBefore("（")
                    ?.let { "$it · ${model.label}" } ?: model.label,
                sizeBytes = model.sizeBytes,
                dir = model.dir,
                flatRoot = false,
            )
        }.sortedWith(compareBy({ it.familyId }, { it.label }))
    }

    /** 下载缓存（未完成的 zip、解压临时目录）占用空间，可单独清理。 */
    fun downloadCacheSize(context: Context): Long = runCatching {
        val dir = cacheRoot(context)
        if (dir.isDirectory) dirSize(dir) else 0L
    }.getOrDefault(0L)

    fun clearDownloadCache(context: Context): Long {
        val size = downloadCacheSize(context)
        runCatching { cacheRoot(context).deleteRecursively() }
        return size
    }

    private val modelFileNames: Set<String> by lazy {
        VoiceModels.FAMILIES.flatMap { it.groups.flatten() }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
    }

    private fun isModelFile(file: File): Boolean =
        file.isFile && file.name.lowercase(Locale.ROOT) in modelFileNames

    private fun dirSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /**
     * 在 voice 根目录写一份清单，让用户打开 voice 文件夹就能看到模型装在哪一层子目录。
     * 回退位置应用私有外部目录（Android/data/...）在部分文件管理器里不可见，清单也用来说明原因。
     */
    fun writeInstalledIndex(context: Context) {
        runCatching {
            val root = VoiceModels.ensureRoot(context)
            val text = buildString {
                appendLine("已安装的本地语音模型（本文件由应用自动生成）")
                appendLine("voice 根目录：${root.absolutePath}")
                appendLine()
                var any = false
                ModelCatalog.VARIANTS.forEach { variant ->
                    if (isInstalled(context, variant)) {
                        any = true
                        appendLine("- ${variant.displayName}")
                        appendLine("  目录：${installedDir(context, variant).absolutePath}")
                        appendLine("  文件：${variant.files.joinToString("、") { it.name }}")
                    }
                }
                if (!any) appendLine("（暂无通过下载安装的模型）")
                appendLine()
                appendLine("本地 ZIP 导入的模型目录：")
                var imported = false
                VoiceModels.FAMILIES.forEach { family ->
                    File(root, family.id).listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith("import_") }
                        ?.sortedBy { it.name }
                        ?.forEach { dir ->
                            imported = true
                            appendLine("- ${family.id}/${dir.name}")
                            appendLine("  目录：${dir.absolutePath}")
                        }
                }
                if (!imported) appendLine("（暂无本地导入的模型）")
                appendLine()
                appendLine("模型目录：${root.absolutePath}")
                appendLine("默认跟随「使用者资料夹」；若这里显示 Android/data/<包名>/files/rime/voice，")
                appendLine("表示资料夹无法按真实路径读写、已自动回退到应用私有目录（旧位置仍会扫描）。")
                appendLine("部分文件管理器无法直接浏览 Android/data，可用 adb 或在设置页复制路径查看。")
            }
            File(root, "installed.txt").writeText(text, Charsets.UTF_8)
        }.onFailure { Timber.w(it, "voice model index write failed") }
    }

    /** 下载缓存目录，优先使用应用私有外部目录（空间比内部存储充足）。 */
    private fun cacheRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.noBackupFilesDir, "voice_download")
            .apply { mkdirs() }

    /**
     * 下载 → 解压 → 校验 → 原子发布 → 自动设为当前引擎。
     * [isActive] 返回 false 时抛 CancellationException；[onStage] 在协程里调用，由 UI 侧切主线程。
     */
    suspend fun install(
        context: Context,
        variant: ModelVariant,
        sourceIndex: Int,
        isActive: () -> Boolean,
        onStage: suspend (phase: String, percent: Int, note: String) -> Unit,
    ): File {
        val app = context.applicationContext
        val root = VoiceModels.ensureRoot(app)
        val dest = installedDir(app, variant)
        val cache = cacheRoot(app)
        val zip = File(cache, variant.zipName)
        val resumeMeta = File(zip.path + ".url")
        val tmpDir = File(cache, "extract_${variant.familyId}_${variant.key}")
        val staged = File(dest.parentFile, ".${variant.key}.installing")
        val previous = File(dest.parentFile, ".${variant.key}.previous")

        fun active() {
            if (!isActive()) throw CancellationException("模型安装已取消")
        }

        return withContext(Dispatchers.IO) {
            try {
                active()
                if (!isInstalled(app, variant) || !dest.isDirectory) {
                    if (!archiveComplete(zip, variant)) {
                        zip.delete()
                        resumeMeta.delete()
                    }
                    if (!archiveComplete(zip, variant)) {
                        downloadWithFallback(
                            urls = ModelCatalog.candidateUrls(variant, sourceIndex),
                            dest = zip,
                            resumeMeta = resumeMeta,
                            isActive = isActive,
                            onStage = onStage,
                        )
                    }
                    active()
                    if (!archiveComplete(zip, variant)) {
                        throw ModelIntegrityException("压缩包不完整，请切换下载源后重试")
                    }
                    onStage(PHASE_EXTRACT, 0, "正在解压 ${variant.zipName}")
                    tmpDir.deleteRecursively()
                    check(tmpDir.mkdirs()) { "无法创建解压目录" }
                    unzip(zip, tmpDir, ::active)

                    active()
                    onStage(PHASE_INSTALL, 0, "准备安装目录")
                    dest.parentFile?.mkdirs()
                    staged.deleteRecursively()
                    check(staged.mkdirs()) { "无法创建安装暂存目录" }
                    variant.files.forEachIndexed { index, spec ->
                        active()
                        onStage(PHASE_VERIFY, index * 100 / variant.files.size, "校验 ${spec.name}")
                        // 官方包可能把同名文件放在多个子目录里。优先按 SHA-256 精确匹配；
                        // release 内容更新导致规格哈希不同时，退化为「同名 + 大小一致」安装，
                        // 避免整个安装被作废、voice 目录里一个文件都看不到。
                        val candidates = findFiles(tmpDir, spec.name)
                        val sized = candidates.filter { it.length() == spec.sizeBytes }
                        val src = sized.firstOrNull { verify(spec, it, ::active) }
                            ?: sized.firstOrNull()
                            ?: candidates.firstOrNull()
                            ?: throw ModelIntegrityException(
                                "模型包缺少 ${spec.name}，请切换下载源重试；" +
                                    "也可手动解压模型包到 voice 目录并使用「自动扫描」",
                            )
                        if (src.length() != spec.sizeBytes) {
                            Timber.w(
                                "voice model %s size mismatch: %d (expected %d), installing anyway",
                                spec.name,
                                src.length(),
                                spec.sizeBytes,
                            )
                        }
                        src.copyTo(File(staged, spec.name), overwrite = true)
                    }

                    active()
                    previous.deleteRecursively()
                    if (dest.exists() && !dest.renameTo(previous)) {
                        throw IOException("无法替换已有模型，请确认 voice 目录可写")
                    }
                    if (!staged.renameTo(dest)) {
                        if (previous.exists()) previous.renameTo(dest)
                        throw IOException("无法发布模型目录，已保留原模型")
                    }
                    previous.deleteRecursively()
                }

                // 自动设为当前引擎与模型；设置变更会卸载旧的 sherpa 识别器缓存。
                LocalVoiceSettings.setVendor(app, variant.familyId)
                LocalVoiceSettings.setSelectedVariant(app, variant.familyId, variant.key)
                writeInstalledIndex(app)
                zip.delete()
                resumeMeta.delete()
                onStage(PHASE_READY, 100, "已安装到 ${dest.absolutePath}")
                Timber.i("Voice model installed at %s", dest.absolutePath)
                dest
            } catch (e: ModelIntegrityException) {
                // 只有压缩包本身缺文件/损坏才丢弃缓存；若包内文件齐全（仅规格哈希不同），
                // 保留 zip，下次重试可直接解压安装，不必重新下载几百 MB。
                if (!archiveComplete(zip, variant)) {
                    zip.delete()
                    resumeMeta.delete()
                }
                throw e
            } finally {
                tmpDir.deleteRecursively()
                staged.deleteRecursively()
            }
        }
    }

    /**
     * 从用户选择的本地 ZIP 安装模型（SAF Uri，不需要存储权限）。
     * 先按官方模型包的文件组合匹配具体规格，再退化为按家族必需文件匹配；
     * 安装到 voice/<家族>/import_<包名>/，与下载安装的规格目录分开，避免互相覆盖。
     * 适用于「手动下载模型 → 本地导入」以及任意 sherpa-onnx 官方模型包。
     */
    suspend fun installFromUri(
        context: Context,
        source: Uri,
        displayName: String,
        isActive: () -> Boolean,
        onStage: suspend (phase: String, percent: Int, note: String) -> Unit,
    ): File {
        val app = context.applicationContext
        val root = VoiceModels.ensureRoot(app)
        val cache = cacheRoot(app)
        val zip = File(cache, "import_source.zip")
        val tmpDir = File(cache, "import_extract")

        fun active() {
            if (!isActive()) throw CancellationException("模型导入已取消")
        }

        return withContext(Dispatchers.IO) {
            try {
                active()
                onStage(PHASE_IMPORT, 0, "正在读取 $displayName")
                zip.delete()
                // SAF Uri 只能流式读取，先落盘成普通文件，之后才能用 ZipFile 检查中央目录。
                val total = runCatching {
                    app.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
                }.getOrDefault(-1L)
                val input = app.contentResolver.openInputStream(source)
                    ?: throw IOException("无法读取所选文件，请重新选择 ZIP")
                input.use { ins ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            active()
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onStage(
                                    PHASE_IMPORT,
                                    percent.coerceAtLeast(0),
                                    "已读取 %.1f MiB".format(done / 1048576.0),
                                )
                            }
                        }
                    }
                }
                active()

                val entryNames = ZipFile(zip).use { archive ->
                    archive.entries().asSequence()
                        .filter { !it.isDirectory }
                        .map { it.name.substringAfterLast('/') }
                        .toList()
                }
                val variant = ModelCatalog.VARIANTS.firstOrNull { v ->
                    v.files.all { spec -> entryNames.any { it.equals(spec.name, ignoreCase = true) } }
                }
                val family = variant?.let { VoiceModels.family(it.familyId) }
                    ?: VoiceModels.FAMILIES.firstOrNull { f ->
                        f.groups.all { group ->
                            entryNames.any { name -> group.any { alias -> name.equals(alias, ignoreCase = true) } }
                        }
                    }
                    ?: throw ModelIntegrityException(
                        "无法识别模型包：未找到受支持引擎所需的模型文件。" +
                            "请使用 sherpa-onnx 官方模型包（例如含 model.int8.onnx 与 tokens.txt）。",
                    )

                val cleaned = displayName.substringBeforeLast('.', displayName)
                    .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
                    .take(40)
                val safeName = "import_" + cleaned.ifBlank { "custom" }
                val dest = File(File(root, family.id), safeName)
                val staged = File(dest.parentFile, ".$safeName.importing")
                val previous = File(dest.parentFile, ".$safeName.previous")

                onStage(PHASE_EXTRACT, 0, "正在解压模型包")
                tmpDir.deleteRecursively()
                check(tmpDir.mkdirs()) { "无法创建解压目录" }
                unzip(zip, tmpDir, ::active)

                active()
                onStage(PHASE_INSTALL, 0, "准备安装目录：${family.id}/$safeName")
                dest.parentFile?.mkdirs()
                staged.deleteRecursively()
                check(staged.mkdirs()) { "无法创建安装暂存目录" }
                family.groups.forEachIndexed { index, group ->
                    active()
                    onStage(PHASE_VERIFY, index * 100 / family.groups.size, "检查 ${group.first()}")
                    val src = pickForGroup(tmpDir, group)
                        ?: throw ModelIntegrityException("模型包缺少 ${group.joinToString(" 或 ")}")
                    // 目标名统一成组内的规范名，VoiceModels 扫描与 config 才能按 key 取到。
                    src.copyTo(File(staged, group.first()), overwrite = true)
                }

                active()
                previous.deleteRecursively()
                if (dest.exists() && !dest.renameTo(previous)) {
                    throw IOException("无法替换已有模型目录，请确认 voice 目录可写")
                }
                if (!staged.renameTo(dest)) {
                    if (previous.exists()) previous.renameTo(dest)
                    throw IOException("无法发布模型目录")
                }
                previous.deleteRecursively()

                LocalVoiceSettings.setVendor(app, family.id)
                LocalVoiceSettings.setSelectedVariant(app, family.id, safeName)
                writeInstalledIndex(app)
                onStage(PHASE_READY, 100, "已安装到 ${dest.absolutePath}")
                Timber.i("Voice model imported from local zip at %s", dest.absolutePath)
                dest
            } finally {
                zip.delete()
                tmpDir.deleteRecursively()
            }
        }
    }

    /** 在解压目录里按别名找一个模型文件，大小写不敏感兜底。 */
    private fun pickForGroup(root: File, group: List<String>): File? {
        for (alias in group) {
            findFiles(root, alias).firstOrNull()?.let { return it }
        }
        val all = mutableListOf<File>()
        collectAll(root, all)
        for (alias in group) {
            all.firstOrNull { it.name.equals(alias, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun collectAll(dir: File, out: MutableList<File>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) collectAll(file, out) else if (file.isFile) out += file
        }
    }

    /** 依次尝试候选源；换源时丢弃上一源留下的不完整分片。 */
    private suspend fun downloadWithFallback(
        urls: List<String>,
        dest: File,
        resumeMeta: File,
        isActive: () -> Boolean,
        onStage: suspend (phase: String, percent: Int, note: String) -> Unit,
    ) {
        var last: Throwable? = null
        urls.forEachIndexed { index, url ->
            try {
                downloadOne(url, dest, resumeMeta, isActive, onStage, "源 ${index + 1}/${urls.size}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "voice model download source failed: %s", url)
                last = t
                dest.delete()
                resumeMeta.delete()
                if (index != urls.lastIndex) {
                    onStage(PHASE_DOWNLOAD, 0, "源 ${index + 1} 不可用，正在尝试下一个…")
                }
            }
        }
        throw IOException("所有下载源均失败：${last?.message ?: "未知错误"}")
    }

    private suspend fun downloadOne(
        url: String,
        dest: File,
        resumeMeta: File,
        isActive: () -> Boolean,
        onStage: suspend (phase: String, percent: Int, note: String) -> Unit,
        tag: String,
    ) = withContext(Dispatchers.IO) {
        var existing = if (dest.isFile) dest.length() else 0L
        val canResume = existing > 0L &&
            resumeMeta.isFile &&
            runCatching { resumeMeta.readText() }.getOrNull() == url
        if (existing > 0L && !canResume) {
            dest.delete()
            existing = 0L
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // 连接超时短一些：某个镜像不通时能快速切到下一个源；读取超时仍保留 30 秒，
            // 避免几百 MB 的大文件下载因短暂抖动被误判为失败。
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "TrimeLocalVoice/1.0")
            if (canResume) conn.setRequestProperty("Range", "bytes=$existing-")
            conn.connect()
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL && canResume
            val bodyLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            // 206 且服务端未给 Content-Length 时无法推算总量，改为只显示已下载字节。
            val total = when {
                append && bodyLength > 0L -> existing + bodyLength
                append -> -1L
                else -> bodyLength
            }
            if (!append) {
                dest.delete()
                existing = 0L
            }
            resumeMeta.writeText(url)
            var done = existing
            var lastPercent = -1
            conn.inputStream.use { ins ->
                FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (!isActive()) throw CancellationException("下载已取消")
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0L) ((done * 100) / total).toInt().coerceIn(0, 99) else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            val note = if (total > 0L) {
                                "%.1f / %.1f MiB · %s".format(done / 1048576.0, total / 1048576.0, tag)
                            } else {
                                "已下载 %.1f MiB · %s".format(done / 1048576.0, tag)
                            }
                            onStage(PHASE_DOWNLOAD, percent.coerceAtLeast(0), note)
                        }
                    }
                }
            }
            if (total > 0L && done != total) {
                throw IOException("下载不完整：$done / $total 字节")
            }
            resumeMeta.delete()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 校验压缩包中央目录里的文件条目，避免拿半截 zip 去解压。
     * 只要求同名文件齐全，不强制大小与规格表一致（release 更新会让大小变化，
     * 若强制匹配会反复判定"压缩包不完整"而无限重下，voice 目录始终为空）。
     */
    private fun archiveComplete(zip: File, variant: ModelVariant): Boolean = runCatching {
        if (!zip.isFile) return false
        ZipFile(zip).use { archive ->
            val entries = archive.entries().asSequence().filter { !it.isDirectory }.toList()
            variant.files.all { spec ->
                entries.any { it.name.substringAfterLast('/') == spec.name }
            }
        }
    }.getOrDefault(false)

    private fun unzip(zip: File, destDir: File, active: () -> Unit) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                active()
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw IOException("模型包包含非法路径：${entry.name}")
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    while (true) {
                        active()
                        val n = zis.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun findFiles(root: File, name: String, out: MutableList<File> = mutableListOf()): List<File> {
        val direct = File(root, name)
        if (direct.isFile) out += direct
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && !child.name.startsWith(".") && child.name != "__MACOSX") {
                findFiles(child, name, out)
            }
        }
        return out
    }

    private fun verify(spec: ModelFileSpec, file: File, active: () -> Unit): Boolean {
        if (file.length() != spec.sizeBytes) return false
        // 上游未提供校验值时按「同名 + 大小一致」放行，省去对几百 MB 文件做无谓的哈希。
        if (spec.sha256.isBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                active()
                val n = ins.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return hex.equals(spec.sha256, ignoreCase = true)
    }
}
