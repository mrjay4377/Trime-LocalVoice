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
    )

    fun family(id: String): VoiceFamily? = FAMILIES.firstOrNull { it.id == id }

    /** 所有可能的 voice 目录；第一个是主目录（用于创建与展示）。 */
    @Suppress("DEPRECATION")
    private fun candidateRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        // Rime 用户目录固定在应用私有外部目录；用户改「使用者资料夹」只影响 Rime 配置的
        // 同步来源，不会改变 voice 的位置（SAF 目录也无法作为 sherpa-onnx 的模型文件路径）。
        runCatching { roots += File(DataManager.userDataDir, DIR_NAME) }
        runCatching { roots += File(UserDataManager.userDataDir, DIR_NAME) }
        context.getExternalFilesDir(null)?.let { roots += File(it, DIR_NAME) }
        runCatching { roots += File(Environment.getExternalStorageDirectory(), "rime/$DIR_NAME") }
        roots += File(context.filesDir, DIR_NAME)
        return roots.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

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
        appendLine("注意：本 voice 目录固定在应用私有目录，不随「使用者资料夹 / 数据位置」改变，")
        appendLine("也不参与资料夹同步（模型体积大，避免部署时被反复哈希、复制或误删）。")
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
