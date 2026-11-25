/*
 * 离线手写识别引擎：StrokeTransformer（ochwpro，MIT）+ 纯 DP 叠写切分。
 *
 * 推理走 ONNX Runtime Java API（本模块 libs/onnxruntime-1.28.0-classes.jar）。
 * native core 复用 app 侧提供的官方 1.28.0 完整版 libonnxruntime.so（含全部算子，
 * C API VERS_1.28.0），本模块只带同版本官方 JNI 桥。
 * APK 内只有一份 ORT core，省去约 30MB。
 *
 * 叠写（同一区域连续写多个字）不训练第二个模型：枚举所有笔画分组，
 * 每组用单字模型打分，再用 DP 找置信度之和最高的切分路径，
 * 做法与 ximeiorg/ochwpro 的 stroke_segmenter.py 一致。
 */
package com.osfans.trime.handwriting

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** 一笔笔迹；坐标是 View 内的原始像素，归一化在推理前统一做。 */
data class InkStroke(val points: List<Pair<Float, Float>>)

data class InkCandidate(val text: String, val score: Float)

interface InkRecognizer {
    val isReady: Boolean

    /** 单字推理，返回按概率降序的 topK 候选。score 为 softmax 后的概率。 */
    fun recognize(strokes: List<InkStroke>, topK: Int): List<InkCandidate>

    fun close()
}

class OnnxInkRecognizer(
    modelPath: String,
    private val charIndex: Map<Int, String>,
    private val stage: (String) -> Unit = {},
) : InkRecognizer {
    /**
     * ORT Java 层的静态初始化会 System.loadLibrary("onnxruntime4j_jni")，native 版本
     * 不匹配时抛 UnsatisfiedLinkError。原来这一行不在 try/catch 里，构造直接抛异常
     * 被外层吞掉，界面只显示一句「模型加载失败」，看不到真正原因。
     *
     * 先显式加载 app 侧提供的官方 ORT 1.28.0 完整 core（libonnxruntime.so，VERS_1.28.0），
     * 再让 JNI 桥按 DT_NEEDED 复用它。这样即使 APK 里漏了该文件，也会在这里抛出
     * 可见的 UnsatisfiedLinkError，而不是等到 native 层莫名崩溃。
     *
     * stage 回调把每一步落盘，native 崩溃时 Java 兜底无效，但下次打开面板能读到死在哪一步。
     */
    private val envResult: Result<OrtEnvironment> = runCatching {
        stage("加载libonnxruntime")
        System.loadLibrary("onnxruntime")
        stage("建OrtEnvironment")
        OrtEnvironment.getEnvironment().also { stage("OrtEnvironment完成") }
    }
    private val env: OrtEnvironment? =
        envResult.onFailure { Timber.w(it, "handwriting: ORT environment init failed") }
            .getOrNull()

    private val sessionResult: Result<OrtSession> =
        env?.let {
            runCatching {
                stage("createSession")
                it.createSession(modelPath, OrtSession.SessionOptions()).also {
                    stage("createSession完成")
                }
            }
        } ?: Result.failure(IllegalStateException("ORT environment unavailable"))
    private val session: OrtSession? =
        sessionResult.onFailure { Timber.w(it, "handwriting: create ORT session failed") }
            .getOrNull()

    /** 给 UI 显示真实失败原因（异常类型 + 信息），不再只给一句笼统提示。 */
    val initError: Throwable?
        get() = envResult.exceptionOrNull() ?: sessionResult.exceptionOrNull()

    private val inputName: String =
        session?.inputNames?.find { it == "input" } ?: session?.inputNames?.firstOrNull().orEmpty()
    private val maskName: String =
        session?.inputNames?.find { it == "mask" }
            ?: run {
                val names = session?.inputNames.orEmpty()
                if (names.size >= 2) names.elementAt(1) else ""
            }
    private val outputName: String =
        session?.outputNames?.find { it == "logits" } ?: session?.outputNames?.firstOrNull().orEmpty()

    /**
     * mask 输入的真实类型。模型声明为 BOOL，但不同导出（甚至量化版）可能是 INT64/INT32/FLOAT。
     * ORT 1.29.0 在部分 ARM 构建上，用「堆内 ByteBuffer + BOOL」建张量时 JNI 层会直接 abort
     * 杀掉 IME 进程（表现为跳回键盘）。这里按模型真实类型建张量，并统一走堆外 Direct Buffer
     * （JNI 最稳的路径），从源头消除这类设备端崩溃。
     */
    private val maskType: OnnxJavaType =
        runCatching {
            val info = session?.inputInfo?.get(maskName)
            (info?.info as? TensorInfo)?.type ?: OnnxJavaType.BOOL
        }.getOrDefault(OnnxJavaType.BOOL)

    /**
     * ochwpro.onnx 是固定长度 200 导出的（上游 ONNXModel._fixed_len = 200），T 维度不是动态的。
     * 喂错长度会在 ORT native 层直接 abort 杀掉 IME 进程（Android 上表现为跳回键盘），
     * 所以这里写死 200，不依赖动态读取。
     */
    private val seqLen: Int = SEQ_LEN

    override val isReady: Boolean =
        env != null && session != null && inputName.isNotEmpty()

    override fun recognize(strokes: List<InkStroke>, topK: Int): List<InkCandidate> {
        val s = session ?: return emptyList()
        val e = env ?: return emptyList()
        // 下采样到接近训练密度（CASIA ~8-24 点/字），再压成 (T,5) 序列。
        val sampled = downsample(strokes)
        val points = flatten(sampled)
        if (points.isEmpty()) return emptyList()
        val (data, n) = features(points)
        if (n <= 0) return emptyList()
        stage("特征完成")

        // input：float32 [1, T, 5]。用堆外 Direct Buffer（JNI 最稳路径，避免 ARM 上堆内 Buffer 的隐患）。
        stage("建input张量")
        val inputTensor =
            runCatching {
                val bb =
                    ByteBuffer
                        .allocateDirect(seqLen * 5 * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                bb.put(data, 0, seqLen * 5)
                bb.rewind()
                OnnxTensor.createTensor(e, bb, longArrayOf(1L, seqLen.toLong(), 5L))
            }.onFailure { Timber.w(it, "handwriting: create input tensor failed") }
                .getOrNull() ?: return emptyList()

        // mask：按模型真实类型建张量，同样走堆外 Direct Buffer。
        // 有效位置 1、补齐位置 0（与上游 collate/mask 一致）。
        stage("建mask张量")
        val maskTensor =
            runCatching { buildMaskTensor(e, n) }
                .onFailure { Timber.w(it, "handwriting: create mask tensor failed") }
                .getOrNull()

        val result =
            runCatching {
                stage("run开始")
                val feeds = HashMap<String, OnnxTensor>(2)
                feeds[inputName] = inputTensor
                if (maskName.isNotEmpty() && maskTensor != null) feeds[maskName] = maskTensor
                val r = s.run(feeds)
                stage("run完成")
                r
            }.onFailure { Timber.w(it, "handwriting: ORT run failed") }
                .getOrNull()
        inputTensor.close()
        maskTensor?.close()
        if (result == null) return emptyList()

        val out = mutableListOf<InkCandidate>()
        try {
            stage("取输出")
            val value = result.get(0) as? OnnxTensor
            val buffer = value?.floatBuffer
            if (buffer != null) {
                val remaining = buffer.remaining()
                val logits = FloatArray(remaining)
                buffer.get(logits)
                stage("softmax+topK")
                val probs = softmax(logits)
                for (idx in topIndices(probs, topK)) {
                    val ch = charIndex[idx] ?: continue
                    if (ch.isEmpty()) continue
                    out += InkCandidate(ch, probs[idx])
                }
            }
        } finally {
            result.close()
        }
        stage("recognize完成")
        return out
    }

    override fun close() {
        runCatching { session?.close() }
            .onFailure { Timber.w(it, "handwriting: close ORT session failed") }
    }

    /** 每点 5 维：x_norm, y_norm, dx, dy, pen_down（每笔末点 pen_down=0）。 */
    private fun flatten(strokes: List<InkStroke>): List<Triple<Float, Float, Float>> {
        val out = ArrayList<Triple<Float, Float, Float>>()
        for (stroke in strokes) {
            val last = stroke.points.lastIndex
            for ((i, p) in stroke.points.withIndex()) {
                out += Triple(p.first, p.second, if (i == last) 0f else 1f)
            }
        }
        return out
    }

    /**
     * 下采样到接近训练密度（CASIA ~8-24 点/字）。每笔均匀取点、保留首尾，
     * 避免触屏/手写笔密集采样导致模型输入分布偏移、识别率下降。
     */
    private fun downsample(strokes: List<InkStroke>): List<InkStroke> {
        if (strokes.isEmpty()) return strokes
        return strokes.map { stroke ->
            val pts = stroke.points
            val m = pts.size
            if (m <= MAX_POINTS_PER_STROKE) return@map stroke
            val step = (m - 1).toFloat() / (MAX_POINTS_PER_STROKE - 1)
            val seen = LinkedHashSet<Int>()
            for (i in 0 until MAX_POINTS_PER_STROKE) {
                val idx =
                    if (i == MAX_POINTS_PER_STROKE - 1) m - 1
                    else (i * step).roundToInt().coerceIn(0, m - 1)
                seen.add(idx)
            }
            InkStroke(seen.map { pts[it] })
        }
    }

    private fun features(points: List<Triple<Float, Float, Float>>): Pair<FloatArray, Int> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (p in points) {
            if (p.first < minX) minX = p.first
            if (p.first > maxX) maxX = p.first
            if (p.second < minY) minY = p.second
            if (p.second > maxY) maxY = p.second
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        val data = FloatArray(seqLen * 5)
        var prevX = 0f
        var prevY = 0f
        var first = true
        var n = 0
        for ((i, p) in points.withIndex()) {
            if (i >= seqLen) break
            val x = ((p.first - minX) / w).coerceIn(0f, 1f)
            val y = ((p.second - minY) / h).coerceIn(0f, 1f)
            val o = i * 5
            data[o] = x
            data[o + 1] = y
            data[o + 2] = if (first) 0f else x - prevX
            data[o + 3] = if (first) 0f else y - prevY
            data[o + 4] = p.third
            prevX = x
            prevY = y
            first = false
            n = i + 1
        }
        // 截断时，最后一个写入点的 pen_down 置 0（与上游一致：seq[-1,4]=0）
        if (points.size > seqLen && n > 0) data[(n - 1) * 5 + 4] = 0f
        return data to n
    }

    /**
     * 按模型 mask 输入的真实类型构造 mask 张量。统一用堆外 Direct Buffer，避免 ORT Java 在
     * ARM 上对「堆内 ByteBuffer + 某类型」JNI 处理的脆弱点（曾直接导致 native abort 杀进程）。
     * 类型按 session 探测：BOOL / INT64 / INT32 / FLOAT 都覆盖，兼容不同模型导出。
     */
    private fun buildMaskTensor(
        e: OrtEnvironment,
        n: Int,
    ): OnnxTensor {
        val shape = longArrayOf(1L, seqLen.toLong())
        return when (maskType) {
            OnnxJavaType.BOOL -> {
                val bb =
                    ByteBuffer
                        .allocateDirect(seqLen)
                        .order(ByteOrder.nativeOrder())
                for (i in 0 until seqLen) bb.put(if (i < n) 1.toByte() else 0.toByte())
                bb.rewind()
                OnnxTensor.createTensor(e, bb, shape, OnnxJavaType.BOOL)
            }
            OnnxJavaType.INT64 -> {
                val arr = LongArray(seqLen) { if (it < n) 1L else 0L }
                val lb = ByteBuffer.allocateDirect(seqLen * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
                lb.put(arr)
                lb.rewind()
                OnnxTensor.createTensor(e, lb, shape)
            }
            OnnxJavaType.INT32 -> {
                val arr = IntArray(seqLen) { if (it < n) 1 else 0 }
                val ib = ByteBuffer.allocateDirect(seqLen * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
                ib.put(arr)
                ib.rewind()
                OnnxTensor.createTensor(e, ib, shape)
            }
            else -> {
                val arr = FloatArray(seqLen) { if (it < n) 1f else 0f }
                val fb = ByteBuffer.allocateDirect(seqLen * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                fb.put(arr)
                fb.rewind()
                OnnxTensor.createTensor(e, fb, shape)
            }
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        val out = FloatArray(logits.size)
        var sum = 0f
        for (i in logits.indices) {
            val e = exp((logits[i] - max).coerceIn(-60f, 60f))
            out[i] = e
            sum += e
        }
        if (sum > 0f) for (i in out.indices) out[i] /= sum
        return out
    }

    /** 部分选择 topK，避免对 7356 个类做全排序。 */
    private fun topIndices(probs: FloatArray, k: Int): List<Int> {
        val n = k.coerceAtLeast(1).coerceAtMost(probs.size)
        val idx = IntArray(n) { -1 }
        val value = FloatArray(n) { -1f }
        for (i in probs.indices) {
            val p = probs[i]
            if (p <= value[n - 1]) continue
            var at = n - 1
            while (at > 0 && p > value[at - 1]) {
                value[at] = value[at - 1]
                idx[at] = idx[at - 1]
                at--
            }
            value[at] = p
            idx[at] = i
        }
        return idx.filter { it >= 0 }
    }

    companion object {
        /** ochwpro.onnx 固定导出长度，必须与模型一致，否则 native 层直接 abort 杀进程。 */
        private const val SEQ_LEN = 200

        /** 每笔下采样上限，贴近 CASIA 训练密度（每字 8-24 点）。 */
        private const val MAX_POINTS_PER_STROKE = 12
    }
}

/**
 * 叠写切分：枚举笔画分组 → 单字模型打分 → DP 求全局最优路径 → 回溯出候选文本。
 * 不额外训练模型，切分惩罚防止把一个左右结构的汉字拆成两个字。
 */
class StrokeSegmenter(private val recognizer: InkRecognizer) {
    fun recognize(strokes: List<InkStroke>, topK: Int = 8): List<InkCandidate> {
        if (!recognizer.isReady || strokes.isEmpty()) return emptyList()
        val n = strokes.size
        // segment[j][i] = strokes[j until i] 作为一个字时的最佳候选
        val segment = Array(n + 1) { arrayOfNulls<InkCandidate>(n + 1) }
        val alternatives = Array(n + 1) { arrayOfNulls<List<InkCandidate>>(n + 1) }
        for (j in 0 until n) {
            val end = minOf(n, j + MAX_STROKES_PER_SEGMENT)
            for (i in j + 1..end) {
                // 每段保留完整 topK 候选：以前硬编码 3，单字时只有「首选 + 2 个替代」
                // 共 3 个字进候选栏。这里按调用方的 topK 取满，候选栏才能一次出 8~10 个。
                val candidates = recognizer.recognize(strokes.subList(j, i), topK)
                val best = candidates.firstOrNull() ?: continue
                segment[j][i] = best
                alternatives[j][i] = candidates
            }
        }

        val dp = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        val picked = arrayOfNulls<InkCandidate>(n + 1)
        val ranges = mutableListOf<Pair<Int, Int>>()
        dp[0] = 0f
        for (i in 1..n) {
            val from = maxOf(0, i - MAX_STROKES_PER_SEGMENT)
            for (j in from until i) {
                val c = segment[j][i] ?: continue
                if (dp[j] == Float.NEGATIVE_INFINITY) continue
                val score = dp[j] + ln(c.score.coerceAtLeast(1e-6f)) + SPLIT_PENALTY
                if (score > dp[i]) {
                    dp[i] = score
                    prev[i] = j
                    picked[i] = c
                }
            }
        }
        if (dp[n] == Float.NEGATIVE_INFINITY) return emptyList()

        var at = n
        while (at > 0) {
            val from = prev[at]
            if (from < 0) return emptyList()
            ranges += from to at
            at = from
        }
        ranges.reverse()

        val results = LinkedHashMap<String, Float>()
        val primary = StringBuilder()
        for ((from, to) in ranges) {
            primary.append(picked[to]?.text.orEmpty())
        }
        if (primary.isEmpty()) return emptyList()
        results[primary.toString()] = dp[n]

        // 用每段的第 2/3 候选生成有限的替代文本，给候选栏多几个选择。
        for ((from, to) in ranges) {
            val alts = alternatives[from][to] ?: continue
            for (c in alts.drop(1)) {
                val sb = StringBuilder()
                for ((f2, t2) in ranges) {
                    sb.append(if (f2 == from) c.text else picked[t2]?.text.orEmpty())
                }
                val text = sb.toString()
                if (text.isNotEmpty() && text != primary.toString()) {
                    results.putIfAbsent(text, dp[n] - 0.5f)
                }
                if (results.size >= topK) break
            }
            if (results.size >= topK) break
        }
        return results.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { InkCandidate(it.key, it.value) }
    }

    companion object {
        /** 单字最多 12 笔，覆盖绝大多数汉字且限制组合爆炸。 */
        private const val MAX_STROKES_PER_SEGMENT = 12

        /** 每多切一个字扣分，与 ochwpro 的 stroke_segmenter.py 一致。 */
        private const val SPLIT_PENALTY = -0.3f
    }

    /** 释放底层 ORT 会话；供推理子进程解绑 / 销毁时调用，避免 ORT 常驻。 */
    fun close() {
        runCatching { recognizer.close() }
    }
}
