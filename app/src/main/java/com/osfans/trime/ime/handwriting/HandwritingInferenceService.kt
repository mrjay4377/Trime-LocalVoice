/*
 * 手写推理子进程服务（android:process=":hw_infer"）。
 *
 * 只做一件事：把 ORT native 推理关在独立进程里跑。IME 主进程（键盘）通过 AIDL 把笔迹
 * 发过来、拿回候选。这样即使 ORT 在设备 ARM 上 native abort，死的也只是这个子进程，
 * 键盘 IME 永远活着。
 *
 * 子进程里只有手写 ORT（libort1290.so），没有 sherpa 的 libonnxruntime.so，
 * 因此也顺带消除了「双 ORT 共存」的任何潜在符号冲突。
 */
package com.osfans.trime.ime.handwriting

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.osfans.trime.handwriting.HandwritingModels
import java.io.File
import com.osfans.trime.handwriting.InkStroke
import com.osfans.trime.handwriting.OnnxInkRecognizer
import com.osfans.trime.handwriting.StrokeSegmenter
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class HandwritingInferenceService : Service() {
    companion object {
        const val EXTRA_MODEL_DIR = "model_dir"
    }

    @Volatile private var segmenter: StrokeSegmenter? = null

    @Volatile private var loadError: String? = null

    /** 绑定时由主进程传入的模型目录绝对路径；为空则回退服务自身解析（兼容旧调用）。 */
    @Volatile private var modelDir: File? = null

    private val binder =
        object : IHandwritingInference.Stub() {
            override fun recognize(
                strokesJson: String,
                topK: Int,
            ): String {
                val seg = ensureSegmenter()
                if (seg == null) {
                    return JSONObject()
                        .put("ok", false)
                        .put("error", loadError ?: "手写模型未就绪")
                        .toString()
                }
                return try {
                    val strokes = parseStrokes(strokesJson)
                    if (strokes.isEmpty()) {
                        return JSONObject().put("ok", true).put("candidates", JSONArray()).toString()
                    }
                    val results = seg.recognize(strokes, topK)
                    val arr = JSONArray()
                    for (c in results) {
                        arr.put(JSONObject().put("text", c.text).put("score", c.score.toDouble()))
                    }
                    JSONObject().put("ok", true).put("candidates", arr).toString()
                } catch (t: Throwable) {
                    Timber.e(t, "handwriting: inference failed")
                    JSONObject()
                        .put("ok", false)
                        .put("error", t.message ?: "推理异常")
                        .toString()
                }
            }
        }

    @Synchronized
    private fun ensureSegmenter(): StrokeSegmenter? {
        val existing = segmenter
        if (existing != null) return existing
        return try {
            // 优先用主进程绑定时传入的目录；子进程 prefs 缓存可能与主进程不一致，
            // 通过 Intent 传绝对路径是跨进程最可靠的做法。
            val dir = modelDir
            val model = if (dir != null) HandwritingModels.modelFileIn(dir) else HandwritingModels.modelFile(this)
            val index = if (dir != null) HandwritingModels.charIndexFile(HandwritingModels.indexFileIn(dir)) else HandwritingModels.charIndex(this)
            // 字表为空时不要静默跑推理：ORT 会算完 7356 维 logits，但 topK 索引查不到字，
            // 结果永远是 0 候选、界面只显示「未识别到文字」。这里直接给出可读错误。
            if (index.isEmpty()) {
                loadError = "字表为空：char_index.json 解析失败或格式不支持（设置 → 手写输入 重新下载）"
                return null
            }
            val engine = OnnxInkRecognizer(model.absolutePath, index)
            if (!engine.isReady) {
                loadError = engine.initError?.message ?: "引擎初始化失败"
                runCatching { engine.close() }
                null
            } else {
                lastLoadedModelPath = model.absolutePath
                StrokeSegmenter(engine).also { segmenter = it }
            }
        } catch (t: Throwable) {
            loadError = t.message ?: "加载异常"
            Timber.e(t, "handwriting: service segmenter load failed")
            null
        }
    }

    /** {"strokes":[[[x,y],...],...]} → List<InkStroke>。坐标已是 View 内像素，归一化在引擎内做。 */
    private fun parseStrokes(json: String): List<InkStroke> {
        val root = JSONObject(json)
        val strokesArr = root.optJSONArray("strokes") ?: return emptyList()
        val out = ArrayList<InkStroke>(strokesArr.length())
        for (i in 0 until strokesArr.length()) {
            val ptsArr = strokesArr.optJSONArray(i) ?: continue
            val pts = ArrayList<Pair<Float, Float>>(ptsArr.length())
            for (j in 0 until ptsArr.length()) {
                val p = ptsArr.optJSONArray(j) ?: continue
                if (p.length() < 2) continue
                pts.add(p.getDouble(0).toFloat() to p.getDouble(1).toFloat())
            }
            if (pts.size >= 2) out.add(InkStroke(pts))
        }
        return out
    }

    override fun onBind(intent: Intent?): IBinder {
        val path = intent?.getStringExtra(EXTRA_MODEL_DIR).orEmpty()
        modelDir = path.takeIf { it.isNotEmpty() }?.let { File(it) }
        // 存储位置可能在服务复用期间改变：换目录就释放旧 segmenter，让下次识别按新目录重载。
        modelDir?.let { dir ->
            if (segmenter != null && HandwritingModels.modelFileIn(dir).absolutePath != lastLoadedModelPath) {
                runCatching { segmenter?.close() }
                segmenter = null
                loadError = null
            }
        }
        return binder
    }

    @Volatile private var lastLoadedModelPath: String? = null

    override fun onUnbind(intent: Intent?): Boolean {
        // 客户端解绑后释放 ORT，避免常驻占用内存；下次进入会重新加载。
        runCatching { segmenter?.close() }
        segmenter = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { segmenter?.close() }
        segmenter = null
        super.onDestroy()
    }
}
