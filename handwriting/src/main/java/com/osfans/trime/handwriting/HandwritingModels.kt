/*
 * 离线手写模型文件管理（独立模块，不依赖语音包）。
 *
 * 模型来自 Xime / CyIME 共用的 ochwpro（StrokeTransformer，MIT）：
 *   https://github.com/ximeiorg/ochwpro
 *   https://www.modelscope.cn/models/bikeand/ochwpro
 * 1.72M 参数、7356 个汉字类、CASIA-OLHWDB 训练，Top-1 90.46%。
 * 权重与字表都不进 Git 仓库（约 6.7 MB + 51 KB），首次使用时下载到
 * 应用私有外部目录 /handwriting/，之后完全离线推理。
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

    private const val MODEL_SIZE = 7_001_270L
    private const val MODEL_SHA256 = "eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13"
    private const val INDEX_SIZE = 51_317L
    private const val INDEX_SHA256 = "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176"

    /** 主源国内可直连，备源是 CyIME 的 GitHub Release 原样转存。 */
    private val MODEL_URLS =
        listOf(
            "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/ochwpro.onnx",
            "https://github.com/Cyletix/CyIME/releases/download/models/ochwpro-v1.0-ochwpro.onnx",
        )
    private val INDEX_URLS =
        listOf(
            "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json",
            "https://github.com/Cyletix/CyIME/releases/download/models/ochwpro-v1.0-char_index.json",
        )

    /** 只读定位：应用私有外部目录，无需存储权限、native 可直接 fopen。 */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    fun modelFile(context: Context): File = File(dir(context), MODEL_FILE)

    fun indexFile(context: Context): File = File(dir(context), INDEX_FILE)

    /** 文件齐全且大小对得上就认为可用；不做哈希是为了避免每次打开面板读 7 MB。 */
    fun isReady(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() == MODEL_SIZE } &&
            indexFile(context).let { it.isFile && it.length() > 1_000 }

    /**
     * 旧版模型放在 voice/handwriting（依附语音模块）。这里用纯 Android API 枚举能可靠定位的
     * 历史目录，找到就复制到新目录，用户不必重新下载 6.7 MB。
     * 使用者资料夹路径由 app 的 UserDataManager 决定，本模块不反向依赖，未覆盖时可手动复制。
     */
    fun migrateLegacy(context: Context) {
        if (isReady(context)) return
        val target = dir(context)
        if (!target.isDirectory && !target.mkdirs()) return
        val candidates = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let { candidates += File(it, "voice/$DIR_NAME") }
        candidates += File(context.filesDir, "voice/$DIR_NAME")
        runCatching {
            candidates += File(Environment.getExternalStorageDirectory(), "rime/voice/$DIR_NAME")
        }
        for (root in candidates) {
            val model = File(root, MODEL_FILE)
            val index = File(root, INDEX_FILE)
            if (!model.isFile || !index.isFile) continue
            runCatching {
                model.copyTo(File(target, MODEL_FILE), overwrite = true)
                index.copyTo(File(target, INDEX_FILE), overwrite = true)
                Timber.i("handwriting: migrated models from %s", root.absolutePath)
            }.onFailure { Timber.w(it, "handwriting: legacy model migration failed") }
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

    fun charIndex(context: Context): Map<Int, String> {
        val f = indexFile(context)
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
