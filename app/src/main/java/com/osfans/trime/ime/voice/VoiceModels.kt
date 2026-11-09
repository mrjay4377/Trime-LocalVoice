/*
 * 本地语音模型目录：模型可由「设置 → 本地语音识别」下载并自动安装，
 * 也可手动放到「用户目录 / voice」。APK 不内置模型，识别本身完全离线。
 * 手动放置的模型不做 SHA 校验（便于使用不同量化版本）；下载安装的模型会逐文件校验。
 * 目录布局参考 trime-mie 的 LocalAsrModels，扫描逻辑做了简化与放宽。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Build
import android.os.Environment
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import timber.log.Timber
import java.io.File
import java.util.Locale

/** 每个离线家族需要的文件组；组内是可接受的文件名别名，第一个别名作为 config 键。 */
data class VoiceFamily(
    val id: String,
    val label: String,
    val groups: List<List<String>>,
    val packageName: String,
)

data class ResolvedVoiceModel(
    val dir: File,
    val paths: Map<String, String>,
)

data class VoiceModelStatus(
    val root: File,
    val resolved: ResolvedVoiceModel?,
    val missing: List<String>,
    val checkedDir: File?,
) {
    val ready: Boolean get() = resolved != null
}

object VoiceModels {
    const val DIR_NAME = "voice"

    /** 原生流式家族：使用 sherpa-onnx OnlineRecognizer，按住期间边录边出字。 */
    const val STREAMING_FAMILY = "streaming_xasr"

    /** 流式 Paraformer 家族：OnlineParaformerModelConfig（中英 / 中粤英），同样边录边出字。 */
    const val STREAMING_PARAFORMER_FAMILY = "streaming_paraformer"

    fun isStreaming(familyId: String): Boolean =
        familyId == STREAMING_FAMILY || familyId == STREAMING_PARAFORMER_FAMILY

    val FAMILIES: List<VoiceFamily> = listOf(
        VoiceFamily(
            id = "sensevoice",
            label = "SenseVoice（中英日韩粤，约 240MB，推荐）",
            groups = listOf(
                listOf("tokens.txt"),
                listOf("model.int8.onnx", "model.onnx", "model.fp32.onnx", "model.quant.onnx"),
            ),
            packageName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        ),
        VoiceFamily(
            id = "qwen3_asr",
            label = "Qwen3-ASR 0.6B（中文准确，约 1GB）",
            groups = listOf(
                listOf("conv_frontend.onnx"),
                listOf("encoder.int8.onnx", "encoder.onnx"),
                listOf("decoder.int8.onnx", "decoder.onnx"),
                listOf("vocab.json"),
                listOf("merges.txt"),
                listOf("tokenizer_config.json"),
            ),
            packageName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        ),
        VoiceFamily(
            id = "funasr_nano",
            label = "FunASR Nano（中英，约 690MB）",
            groups = listOf(
                listOf("embedding.int8.onnx", "embedding.onnx"),
                listOf("encoder_adaptor.int8.onnx", "encoder_adaptor.onnx"),
                listOf("llm.int8.onnx", "llm.onnx"),
                listOf("tokenizer.json"),
            ),
            packageName = "sherpa-onnx-funasr-nano-int8-2025-12-30",
        ),
        VoiceFamily(
            id = "firered_asr",
            label = "FireRedASR V2（中英 CTC，约 740MB）",
            groups = listOf(
                listOf("tokens.txt"),
                listOf("model.int8.onnx", "model.onnx"),
            ),
            packageName = "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25",
        ),
        VoiceFamily(
            id = "parakeet",
            label = "Parakeet TDT（英语，约 450MB）",
            groups = listOf(
                listOf("tokens.txt"),
                listOf("encoder.int8.onnx", "encoder.onnx"),
                listOf("decoder.int8.onnx", "decoder.onnx"),
                listOf("joiner.int8.onnx", "joiner.onnx"),
            ),
            packageName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        ),
        VoiceFamily(
            id = "telespeech",
            label = "TeleSpeech CTC（中文，约 340MB，体积最小）",
            groups = listOf(
                listOf("tokens.txt"),
                listOf("model.int8.onnx", "model.onnx"),
            ),
            packageName = "sherpa-onnx-telespeech-ctc-int8-zh-2024-06-04",
        ),
        // 原生流式：Transducer 三件套（encoder / decoder / joiner）+ tokens。
        // 官方包名带 -480ms 后缀，这里把两种命名都列为别名，手动放置与 ZIP 导入都能识别。
        VoiceFamily(
            id = STREAMING_FAMILY,
            label = "流式识别（中英，边说边出字，约 590MB）",
            groups = listOf(
                listOf("tokens.txt"),
                listOf("encoder.onnx", "encoder-480ms.onnx", "encoder.int8.onnx"),
                listOf("decoder.onnx", "decoder-480ms.onnx", "decoder.int8.onnx"),
                listOf("joiner.onnx", "joiner-480ms.onnx", "joiner.int8.onnx"),
            ),
            packageName = "sherpa-onnx-streaming-x-asr-480ms-zh-en",
        ),
        // 流式 Paraformer：BiBi-Keyboard models release 里另外两套 ZIP 流式包。
        // 只需 encoder / decoder / tokens（没有 joiner），走 OnlineParaformerModelConfig。
        VoiceFamily(
            id = STREAMING_PARAFORMER_FAMILY,
            label = "流式 Paraformer（中英 / 中粤英，边说边出字）",
            groups = listOf(
                listOf("tokens.txt"),
                listOf("encoder.int8.onnx", "encoder.onnx", "paraformer-encoder.onnx"),
                listOf("decoder.int8.onnx", "decoder.onnx", "paraformer-decoder.onnx"),
            ),
            packageName = "sherpa-onnx-streaming-paraformer-bilingual-zh-en",
        ),
    )

    fun family(id: String): VoiceFamily? = FAMILIES.firstOrNull { it.id == id }

    /**
     * 目录名里的引擎特征词。官方模型包解压出来的目录名基本都带这些词，
     * 用它区分「文件组完全相同」的 sensevoice / firered_asr / telespeech。
     * 顺序按特异性排列：paraformer 要排在泛化的 streaming / transducer 之前。
     */
    private val FAMILY_NAME_HINTS: List<Pair<String, List<String>>> = listOf(
        "qwen3_asr" to listOf("qwen3", "qwen-3", "qwen"),
        "funasr_nano" to listOf("funasr", "fun-asr"),
        STREAMING_PARAFORMER_FAMILY to listOf("paraformer"),
        STREAMING_FAMILY to listOf("x-asr", "xasr", "zipformer", "transducer", "480ms"),
        "parakeet" to listOf("parakeet"),
        "firered_asr" to listOf("firered", "fire-red", "fire_red"),
        "telespeech" to listOf("telespeech", "tele-speech"),
        "sensevoice" to listOf("sensevoice", "sense-voice", "sense_voice"),
    )

    /** 结合当前目录、父目录（家族目录名）与再上一层目录名推断引擎家族；判不出返回 null。 */
    fun inferFamilyByName(dir: File): String? {
        val haystack = listOfNotNull(
            dir.name,
            dir.parentFile?.name,
            dir.parentFile?.parentFile?.name,
        ).joinToString(" ").lowercase(Locale.ROOT)
        return FAMILY_NAME_HINTS.firstOrNull { (_, hints) -> hints.any { it in haystack } }?.first
    }

    /**
     * 按目录里的文件组合推断可能的家族（可能多个）。
     * 只需「每个必需文件组至少命中一个别名」，不要求目录结构符合 voice/<家族>/<变体>。
     */
    fun inferFamilyByFiles(dir: File): List<String> {
        val names = dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.map { it.name.lowercase(Locale.ROOT) }
            ?.toSet()
            .orEmpty()
        if (names.isEmpty()) return emptyList()
        // 必需文件多的家族先判，避免把 Qwen3 / Paraformer 这类多件套误判成 SenseVoice。
        return FAMILIES.sortedByDescending { it.groups.size }
            .filter { family ->
                family.groups.all { group -> group.any { it.lowercase(Locale.ROOT) in names } }
            }
            .map { it.id }
    }

    /**
     * 是否已获得「所有文件访问权限」：Android 11+ 分区存储下，只有持此权限，
     * 应用才能用 File / native 的 fopen 直接读写使用者资料夹（SAF 还原出的真实路径）。
     * Android 10 及以下不需要此权限，File 直写公共目录本就允许。
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * 所有可能的 voice 目录；第一个是主目录（用于创建、安装与识别）。
     *
     * 若已开启「模型存放用户目录」且持有所有文件权限，首选使用者资料夹下的 voice/：
     * 它会被还原成真实文件路径，sherpa-onnx native 按路径 fopen 直接读取，无需复制进私有目录。
     * 否则回退到应用私有目录（Android/data/<包名>/files/rime/voice），native 同样可直读。
     */
    @Suppress("DEPRECATION")
    private fun candidateRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        // 首选：使用者资料夹 / voice（需所有文件权限 + 已选资料夹 + 可写探测通过）。
        externalVoiceRoot(context)?.let { roots += it }
        // 回退与兼容：应用私有外部目录，native 可直读。
        runCatching { roots += File(DataManager.userDataDir, DIR_NAME) }
        runCatching { roots += File(UserDataManager.userDataDir, DIR_NAME) }
        context.getExternalFilesDir(null)?.let { roots += File(it, DIR_NAME) }
        runCatching { roots += File(Environment.getExternalStorageDirectory(), "rime/$DIR_NAME") }
        roots += File(context.filesDir, DIR_NAME)
        return roots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    /** 探测目录能否真正写入并读回文件（native 引擎要按路径打开模型）。 */
    private fun probeWritable(dir: File): Boolean =
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) {
                false
            } else {
                val probe = File(dir, ".voice_write_probe")
                probe.writeText("ok", Charsets.UTF_8)
                val ok = probe.isFile && probe.canRead() && probe.length() > 0 && dir.canWrite()
                probe.delete()
                ok
            }
        }.getOrDefault(false)

    /** 使用者资料夹 / voice；未开启、未授权、未选资料夹或不可写时返回 null。
     *  全局存储模式为 EXTERNAL_DIRECT 时也算开启（模型跟随使用者资料夹）。 */
    private fun externalVoiceRoot(context: Context): File? {
        val generalExternal = UserDataManager.isExternalDirect()
        if (!LocalVoiceSettings.useUserDataDir(context) && !generalExternal) return null
        if (!hasAllFilesAccess()) return null
        val base = UserDataManager.externalRimeDirAsFile() ?: return null
        val voice = File(base, DIR_NAME)
        return if (probeWritable(voice)) voice else null
    }

    /** 主目录是否落在「使用者资料夹」下；用于设置页提示当前模型位置。 */
    fun isUsingUserDataFolder(context: Context): Boolean = externalVoiceRoot(context) != null

    /**
     * 是否已配置并授权「使用者资料夹」，用于「导出模型到使用者目录」的可用性判断。
     * 注意：这仅决定能否导出备份，识别始终使用应用私有目录里的模型。
     */
    fun canExportToUserDataFolder(): Boolean =
        runCatching { UserDataManager.hasExternalAccess() }.getOrDefault(false)

    /** 所有可扫描的 voice 目录（含旧版私有目录），供模型列表与删除使用。 */
    fun roots(context: Context): List<File> = candidateRoots(context)

    fun primaryRoot(context: Context): File = candidateRoots(context).first()

    /** 创建用户目录 / voice，并放一份说明文件，方便用户知道该放什么。 */
    fun ensureRoot(context: Context): File {
        val root = primaryRoot(context)
        runCatching {
            if (!root.isDirectory) root.mkdirs()
            val noMedia = File(root, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile()
            val readme = File(root, "README.txt")
            if (!readme.exists()) readme.writeText(readmeText(), Charsets.UTF_8)
        }.onFailure { Timber.w(it, "VoiceModels: cannot prepare voice directory %s", root.absolutePath) }
        return root
    }

    private fun readmeText(): String = buildString {
        appendLine("本地语音模型目录（voice）")
        appendLine()
        appendLine("可在「设置 → 本地语音识别 → 模型下载与管理」中一键下载并自动安装，")
        appendLine("也可把 sherpa-onnx 官方模型包解压后的文件手动放到本文件夹。")
        appendLine("支持三种放法：")
        appendLine("  1) 直接平铺：voice/模型文件...")
        appendLine("  2) 按家族分目录：voice/sensevoice/模型文件...")
        appendLine("  3) 直接放入解压出来的整个文件夹：voice/<解压目录>/模型文件...")
        appendLine("识别引擎只会使用「设置 → 本地语音识别」里选中的那一套。")
        appendLine()
        FAMILIES.forEach { f ->
            appendLine("[${f.label}]")
            appendLine("  官方包名：${f.packageName}")
            appendLine("  必需文件：${f.groups.joinToString("、") { it.first() }}")
        }
        appendLine()
        appendLine("[流式识别 / 流式 Paraformer] 是真正「边说边出字」的引擎：按住语音键时逐块解码，")
        appendLine("中间结果直接写进目标输入框，松手后立即定稿上屏；其余引擎是松手后整段推理一次。")
        appendLine("流式 Transducer 模型（含 encoder/decoder/joiner/tokens）放到 voice/streaming_xasr/ 下；")
        appendLine("流式 Paraformer 模型（含 encoder/decoder/tokens）放到 voice/streaming_paraformer/ 下。")
        appendLine()
        appendLine("注意：本 voice 目录固定在应用私有目录 Android/data/<包名>/files/rime/voice。")
        appendLine("识别引擎是 native 库，只能按真实文件路径打开模型；而使用者资料夹是 SAF 授权目录，")
        appendLine("Android 11+ 下 native / File 无法直接读写，所以模型不能直接放在使用者资料夹里。")
        appendLine("如需备份或换机迁移，请在「设置 → 本地语音识别 → 模型」点「导出模型到使用者目录」，")
        appendLine("应用会用 SAF 把模型复制到所选的 rime/voice/ 下（识别仍使用私有目录里这一份）。")
        appendLine("voice 不参与资料夹与 WebDAV 同步（模型体积大，避免部署时被反复哈希、复制或误删）。")
        appendLine()
        appendLine("模型来源：https://github.com/k2-fsa/sherpa-onnx/releases （或 BiBi-Keyboard models release）")
        appendLine("录音格式：16kHz / 单声道 / 16bit PCM；本应用只在设备本地推理，不上传音频。")
    }

    private fun searchDirs(root: File, family: VoiceFamily): List<File> {
        val out = LinkedHashSet<File>()
        val preferred = File(root, family.id)
        out += preferred
        out += root
        listOf(preferred, root).forEach { base ->
            base.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX" }
                ?.forEach { sub ->
                    out += sub
                    sub.listFiles()
                        ?.filter { it.isDirectory && !it.name.startsWith(".") }
                        ?.forEach { out += it }
                }
        }
        return out.filter { it.isDirectory }
    }

    private data class Attempt(val dir: File, val paths: Map<String, String>?, val missing: List<String>)

    private fun match(
        index: Map<String, File>,
        family: VoiceFamily,
    ): Pair<Map<String, String>?, List<String>> {
        val paths = LinkedHashMap<String, String>()
        val missing = mutableListOf<String>()
        for (group in family.groups) {
            var found: File? = null
            for (alias in group) {
                val candidate = index[alias.lowercase(Locale.ROOT)]
                if (candidate != null) {
                    found = candidate
                    break
                }
            }
            if (found == null) {
                missing += group.joinToString(" 或 ")
            } else {
                paths[group[0]] = found.absolutePath
            }
        }
        return (if (missing.isEmpty()) paths else null) to missing
    }

    /**
     * 解析一个目录是否含指定家族的完整模型。
     *
     * 原实现把整个目录树（含所有子目录）的文件合并成一张索引，voice 下放多套引擎时
     * 会拿 A 包的 tokens.txt 拼 B 包的 model.onnx，或者永远只命中扫描到的第一套。
     * 现在：先只看本目录直属文件；不够时逐个**单独**尝试子目录（子目录内部允许再嵌一层），
     * 绝不跨不同子目录拼凑，多个引擎才能各归各的。
     */
    private fun attempt(dir: File, family: VoiceFamily): Attempt {
        val direct = mutableMapOf<String, File>()
        collect(dir, direct, 0, maxDepth = 0)
        val (directPaths, directMissing) = match(direct, family)
        if (directPaths != null) return Attempt(dir, directPaths, emptyList())

        var best = Attempt(dir, null, directMissing)
        dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX" }
            ?.forEach { sub ->
                val index = mutableMapOf<String, File>()
                collect(sub, index, 0, maxDepth = 2)
                val (paths, missing) = match(index, family)
                if (paths != null) return Attempt(sub, paths, emptyList())
                if (missing.size < best.missing.size) best = Attempt(sub, null, missing)
            }
        return best
    }

    private fun collect(
        dir: File,
        index: MutableMap<String, File>,
        depth: Int,
        maxDepth: Int = 3,
    ) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (file.isDirectory) {
                if (file.name.startsWith(".") || file.name == "__MACOSX") continue
                collect(file, index, depth + 1, maxDepth)
            } else if (file.isFile && file.length() > 0) {
                val key = file.name.lowercase(Locale.ROOT)
                if (!index.containsKey(key)) index[key] = file
            }
        }
    }

    /** 找到所选家族的完整模型文件，返回绝对路径表。 */
    fun resolve(context: Context, familyId: String): ResolvedVoiceModel? =
        status(context, familyId).resolved

    /** 检查模型状态；未就绪时返回缺失最少的一种布局所缺的文件。 */
    fun status(context: Context, familyId: String): VoiceModelStatus {
        val family = family(familyId) ?: FAMILIES.first()
        val root = ensureRoot(context)

        // 最高优先级：用户在「语音识别引擎」里手动选中的那套模型目录（绝对路径）。
        // 用户把多个引擎的解压包直接放进 voice 时，靠这条才能切换，不再永远命中扫描到的第一套。
        val pinned = LocalVoiceSettings.selectedModelDir(context)
        if (pinned.isNotBlank()) {
            val dir = File(pinned)
            if (dir.isDirectory) {
                val result = attempt(dir, family)
                if (result.paths != null) {
                    return VoiceModelStatus(
                        root,
                        ResolvedVoiceModel(result.dir, result.paths),
                        emptyList(),
                        result.dir,
                    )
                }
            }
        }

        // 通过「下载并安装模型」装的规格模型有精确目录，优先使用，避免多个变体的同名文件混用。
        val selected = LocalVoiceSettings.selectedVariant(context, familyId)
        if (selected.isNotBlank()) {
            val candidates = LinkedHashSet<File>()
            candidates += File(File(root, family.id), selected)
            // 兼容旧版：手动包曾把目录名存在 selectedVariant 里，但目录并不在 <family>/ 下。
            candidateRoots(context).forEach { r ->
                candidates += File(r, selected)
                r.listFiles()?.filter { it.isDirectory }?.forEach { candidates += File(it, selected) }
            }
            for (dir in candidates) {
                if (!dir.isDirectory) continue
                val result = attempt(dir, family)
                if (result.paths != null) {
                    return VoiceModelStatus(
                        root,
                        ResolvedVoiceModel(result.dir, result.paths),
                        emptyList(),
                        result.dir,
                    )
                }
            }
        }
        var best: Attempt? = null
        for (candidate in candidateRoots(context)) {
            if (!candidate.isDirectory) continue
            for (dir in searchDirs(candidate, family)) {
                val result = attempt(dir, family)
                if (result.paths != null) {
                    return VoiceModelStatus(root, ResolvedVoiceModel(dir, result.paths), emptyList(), dir)
                }
                val current = best
                if (current == null || result.missing.size < current.missing.size) best = result
            }
        }
        return VoiceModelStatus(
            root = root,
            resolved = null,
            missing = best?.missing ?: family.groups.map { it.joinToString(" 或 ") },
            checkedDir = best?.dir,
        )
    }
}
