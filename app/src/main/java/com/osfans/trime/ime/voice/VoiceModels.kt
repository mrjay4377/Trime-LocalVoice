/*
 * 本地语音模型目录：模型可由「设置 → 本地语音识别」下载并自动安装，
 * 也可手动放到「用户目录 / voice」。APK 不内置模型，识别本身完全离线。
 * 手动放置的模型不做 SHA 校验（便于使用不同量化版本）；下载安装的模型会逐文件校验。
 * 目录布局参考 trime-mie 的 LocalAsrModels，扫描逻辑做了简化与放宽。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Environment
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import timber.log.Timber
import java.io.File

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

    /** 所有可能的 voice 目录；第一个是主目录（用于创建与展示）。 */
    @Suppress("DEPRECATION")
    private fun candidateRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        // 首选：使用者资料夹 / voice。默认资料夹模式就是 EXTERNAL_SYNC，用户自己选的 rime
        // 目录多半是 SAF（content://），而 sherpa-onnx 是 native 库、只能按真实路径打开模型，
        // 所以先把它还原成文件路径并确认可写；不可用则静默回退到应用私有目录。
        externalVoiceRoot()?.let { roots += it }
        // 以下为回退与旧版兼容：v42 之前模型固定在应用私有目录，仍要能扫描到旧模型。
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

    @Volatile private var writableRootPath: String? = null

    @Volatile private var writableRootOk = false

    /** 使用者资料夹 / voice；资料夹不可用、无法解析或不可写时返回 null。 */
    private fun externalVoiceRoot(): File? {
        val base = runCatching { UserDataManager.externalRimeDirAsFile() }.getOrNull() ?: return null
        val voice = File(base, DIR_NAME)
        val path = voice.absolutePath
        val ok =
            if (writableRootPath == path) {
                writableRootOk
            } else {
                val probed = probeWritable(voice)
                writableRootPath = path
                writableRootOk = probed
                probed
            }
        return if (ok) voice else null
    }

    /** 当前 voice 是否真的落在「使用者资料夹」下；false 表示已回退到应用私有目录。 */
    fun isUsingUserDataFolder(): Boolean = externalVoiceRoot() != null

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
        appendLine("注意：本 voice 目录默认跟随「使用者资料夹 / 数据位置」，即你所选 rime 目录下的 voice/，")
        appendLine("备份或迁移方案时模型可以一起带走。若该目录无法被还原成可读写的真实路径，")
        appendLine("会自动回退到应用私有目录 Android/data/<包名>/files/rime/voice（旧版位置仍可扫描到模型）。")
        appendLine("两种位置都不参与资料夹与 WebDAV 同步（模型体积大，避免部署时被反复哈希、复制或误删）。")
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

    private fun attempt(dir: File, family: VoiceFamily): Attempt {
        val index = mutableMapOf<String, File>()
        collect(dir, index, 0)
        val paths = LinkedHashMap<String, String>()
        val missing = mutableListOf<String>()
        for (group in family.groups) {
            var found: File? = null
            for (alias in group) {
                val candidate = index[alias.lowercase()]
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
        return Attempt(dir, if (missing.isEmpty()) paths else null, missing)
    }

    private fun collect(dir: File, index: MutableMap<String, File>, depth: Int) {
        if (depth > 3) return
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (file.isDirectory) {
                if (file.name.startsWith(".") || file.name == "__MACOSX") continue
                collect(file, index, depth + 1)
            } else if (file.isFile && file.length() > 0) {
                val key = file.name.lowercase()
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
        // 通过「模型列表」安装的模型有精确目录，优先使用，避免多个变体的同名文件混用。
        val selected = LocalVoiceSettings.selectedVariant(context, familyId)
        if (selected.isNotBlank()) {
            val dir = File(File(root, family.id), selected)
            val result = attempt(dir, family)
            if (result.paths != null) {
                return VoiceModelStatus(root, ResolvedVoiceModel(dir, result.paths), emptyList(), dir)
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
