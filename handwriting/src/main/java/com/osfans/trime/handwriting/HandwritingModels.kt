/*
 * 离线手写模型文件管理（独立模块，不依赖语音包）。
 *
 * 模型来自 Xime / CyIME 共用的 ochwpro（StrokeTransformer，MIT）：
 *   https://github.com/ximeiorg/ochwpro
 *   https://www.modelscope.cn/models/bikeand/ochwpro
 * 1.72M 参数、7356 个汉字类、CASIA-OLHWDB 训练，Top-1 90.46%。
 * 权重与字表都不进 Git 仓库（INT8 约 2.1 MB + 51 KB），首次使用时下载到
 * 应用私有外部目录 /handwriting/，之后完全离线推理。
 *
 * 模型用官方导出的 INT8 动态量化版（ochwpro-int8.onnx）：接口与 FP32 完全一致
 * （input[batch,200,5] / mask[batch,200] / logits[batch,7356]），体积从 7 MB 降到
 * 2.1 MB、推理更快；本地文件名沿用 ochwpro.onnx，旧 FP32 因大小不符会自动重新下载。
 *
 * 目录刻意不叫 voice：手写是独立功能，模型不与语音模型混放，也不需要
 * 「使用者资料夹 / 所有文件访问权限」才能用。旧版放在 voice/handwriting 的
 * 文件会在首次打开面板 / 下载时自动迁移过来。
 *
 * 注意：Xime 的 ochwpro 仓库是 MIT，没有 CC BY-NC-SA 限制；
 * 本实现刻意不使用 CyIME 的公式手写模型（MathWriting 训练数据为 NC 协议）。
 */
package com.osfans.trime.handwriting

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object HandwritingModels {
    const val DIR_NAME = "handwriting"
    const val MODEL_FILE = "ochwpro.onnx"
    const val INDEX_FILE = "char_index.json"

    private const val MODEL_SIZE = 2_231_032L
    private const val MODEL_SHA256 = "3902aaae38df8c17d02d9fd5dad5f0045a4e6cda26d1812244fd135974bccf15"
    private const val INDEX_SIZE = 51_317L
    private const val INDEX_SHA256 = "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176"

    /**
     * INT8 只有 modelscope 官方有（CyIME Release 只转存了 FP32，故不作备源）。
     * modelscope 国内可直连；带 SHA-256 校验，下载到的是 ochwpro-int8.onnx 内容，
     * 保存成本地文件名 MODEL_FILE（ochwpro.onnx），推理接口完全一致。
     */
    private val MODEL_URLS =
        listOf(
            "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/ochwpro-int8.onnx",
        )
    private val INDEX_URLS =
        listOf(
            "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json",
            "https://github.com/Cyletix/CyIME/releases/download/models/ochwpro-v1.0-char_index.json",
        )

    /**
     * 模型目录：开启「跟随使用者资料夹」且 app 层写入了可用路径时，用
     * <使用者资料夹/rime>/handwriting（native 按真实路径 fopen）；否则回退应用私有目录。
     * 每次都重新判定，切换存储位置/授权后无需重启即可生效。
     */
    fun dir(context: Context): File {
        if (HandwritingSettings.useUserDataDir(context)) {
            val root = HandwritingSettings.userDirPath(context)
            if (root.isNotEmpty()) {
                val candidate = File(File(root), DIR_NAME)
                // 目录存在或父目录可写才采用；否则回退私有目录，避免 native fopen 失败。
                if (candidate.isDirectory || (candidate.parentFile?.canWrite() == true)) return candidate
            }
        }
        return privateDir(context)
    }

    /** 应用私有外部目录（无需存储权限），也是使用者资料夹不可用时的回退。 */
    private fun privateDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    fun modelFile(context: Context): File = File(dir(context), MODEL_FILE)

    fun indexFile(context: Context): File = File(dir(context), INDEX_FILE)

    /** 显式目录版本：:hw_infer 子进程用绑定时传入的绝对路径，避免跨进程 prefs 缓存不一致。 */
    fun modelFileIn(dir: File): File = File(dir, MODEL_FILE)

    fun indexFileIn(dir: File): File = File(dir, INDEX_FILE)

    /** 文件齐全且大小对得上就认为可用；不做哈希是为了避免每次打开面板读 7 MB。 */
    fun isReady(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() == MODEL_SIZE } &&
            indexFile(context).let { it.isFile && it.length() > 1_000 }

    /**
     * 旧版模型放在 voice/handwriting（依附语音模块）。这里用纯 Android API 枚举能可靠定位的
     * 历史目录，找到就复制到新目录，用户不必重新下载模型。
     * 使用者资料夹路径由 app 的 UserDataManager 决定，本模块不反向依赖，未覆盖时可手动复制。
     */
    fun migrateLegacy(context: Context) {
        if (isReady(context)) return
        val target = dir(context)
        if (!target.isDirectory && !target.mkdirs()) return
        val candidates = mutableListOf<File>()
        // 旧版依附语音的目录（历史迁移）。
        context.getExternalFilesDir(null)?.let { candidates += File(it, "voice/$DIR_NAME") }
        candidates += File(context.filesDir, "voice/$DIR_NAME")
        runCatching {
            candidates += File(Environment.getExternalStorageDirectory(), "rime/voice/$DIR_NAME")
        }
        // 私有目录 ↔ 使用者资料夹之间互迁：切换「模型存放位置」后不用重新下载模型。
        candidates += privateDir(context)
        val userRoot = HandwritingSettings.userDirPath(context)
        if (userRoot.isNotEmpty()) candidates += File(File(userRoot), DIR_NAME)
        for (root in candidates) {
            if (root.absolutePath == target.absolutePath) continue
            val model = File(root, MODEL_FILE)
            val index = File(root, INDEX_FILE)
            if (!model.isFile || !index.isFile) continue
            runCatching {
                model.copyTo(File(target, MODEL_FILE), overwrite = true)
                index.copyTo(File(target, INDEX_FILE), overwrite = true)
                Timber.i("handwriting: migrated models from %s", root.absolutePath)
            }.onFailure { Timber.w(it, "handwriting: model migration failed") }
            return
        }
    }

    /** IO 线程调用：下载 + SHA-256 校验 + 原子替换。已就绪时直接返回 true。 */
    fun download(context: Context): Boolean {
        migrateLegacy(context)
        if (isReady(context)) return true
        val writable = dir(context)
        if (!writable.isDirectory && !writable.mkdirs()) return false
        val modelOk =
            downloadOne(MODEL_URLS, File(writable, MODEL_FILE), MODEL_SIZE, MODEL_SHA256)
        val indexOk =
            downloadOne(INDEX_URLS, File(writable, INDEX_FILE), INDEX_SIZE, INDEX_SHA256)
        Timber.i("handwriting model download: model=%s index=%s", modelOk, indexOk)
        return modelOk && indexOk
    }

    private fun downloadOne(
        urls: List<String>,
        dest: File,
        expectedSize: Long,
        expectedSha: String,
    ): Boolean {
        if (dest.isFile && verify(dest, expectedSize, expectedSha)) return true
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        for (url in urls) {
            val ok =
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 90_000
                    conn.instanceFollowRedirects = true
                    try {
                        conn.connect()
                        if (conn.responseCode !in 200..299) return@runCatching false
                        conn.inputStream.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (!verify(tmp, expectedSize, expectedSha)) return@runCatching false
                    if (dest.exists() && !dest.delete()) return@runCatching false
                    tmp.renameTo(dest)
                }.onFailure { Timber.w(it, "handwriting: download %s failed", url) }
                    .getOrDefault(false)
            if (ok) return true
            tmp.delete()
        }
        return false
    }

    private fun verify(file: File, expectedSize: Long, expectedSha: String): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .equals(expectedSha, ignoreCase = true)
    }

    /**
     * 字表数组容器键。主源 modelscope `bikeand/ochwpro` 的实际格式是
     * `{"chars": ["?", "A", …, "跤"]}`（7356 项，与 logits 维度一致，SHA 见 INDEX_SHA256）。
     *
     * 旧实现只认纯数组 / `{"0":"一"}` / `{"char2idx":{…}}`：遇到 `{"chars": …}` 时
     * 唯一遍历到的 key 是 `"chars"`，`toIntOrNull()` 为 null 就被跳过，整张表解析成 0 条。
     * 后果是 ORT 推理全程成功、softmax+topK 也正常，但 `charIndex[idx]` 全部落空，
     * 候选恒为 0 —— 界面一直显示「未识别到文字」。这里补齐数组容器键。
     */
    private val INDEX_ARRAY_KEYS =
        listOf("chars", "char_list", "characters", "vocab", "labels", "idx2char", "id2char")

    /** 索引→字符 的对象容器键。 */
    private val INDEX_OBJECT_KEYS = listOf("idx2char", "id2char", "idx_to_char", "char_map")

    fun charIndex(context: Context): Map<Int, String> = charIndexFile(indexFile(context))

    /** 显式字表文件版本（配合 [indexFileIn]）。 */
    fun charIndexFile(f: File): Map<Int, String> {
        if (!f.isFile) return emptyMap()
        val raw = runCatching { f.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching { parseIndex(raw) }
            .onFailure { Timber.w(it, "handwriting: char_index parse failed") }
            .getOrDefault(emptyMap())
    }

    private fun parseIndex(raw: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        when {
            // ① 纯数组：["一","丁",…]
            raw.startsWith("[") -> {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) out[i] = arr.getString(i)
            }
            raw.startsWith("{") -> {
                val obj = JSONObject(raw)
                // ② 数组容器：{"chars":[…]} —— 主源真实格式，下标即类别索引。
                val arrKey = INDEX_ARRAY_KEYS.firstOrNull { obj.optJSONArray(it) != null }
                if (arrKey != null) {
                    val arr = obj.getJSONArray(arrKey)
                    for (i in 0 until arr.length()) out[i] = arr.getString(i)
                    return out
                }
                // ③ 索引→字符对象：{"idx2char":{"0":"一",…}}
                val direct = INDEX_OBJECT_KEYS.firstOrNull { obj.optJSONObject(it) != null }
                if (direct != null) {
                    val inner = obj.getJSONObject(direct)
                    val keys = inner.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val idx = k.toIntOrNull() ?: continue
                        out[idx] = inner.optString(k, "")
                    }
                    if (out.isNotEmpty()) return out
                }
                // ④ 字符→索引对象：{"char2idx":{"一":0,…}}
                val reverse = obj.optJSONObject("char2idx") ?: obj.optJSONObject("char_to_idx")
                if (reverse != null) {
                    val keys = reverse.keys()
                    while (keys.hasNext()) {
                        val ch = keys.next()
                        out[reverse.getInt(ch)] = ch
                    }
                } else {
                    // ⑤ 顶层 {"0":"一",…}
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val idx = k.toIntOrNull() ?: continue
                        val ch = obj.optString(k, "")
                        if (ch.isNotEmpty()) out[idx] = ch
                    }
                }
            }
        }
        return out
    }
}
