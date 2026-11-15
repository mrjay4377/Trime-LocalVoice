/*
 * 手写笔迹采集 View（传统 View + Canvas，不依赖 Compose）。
 * 只负责把触摸轨迹攒成一笔笔 InkStroke，识别交给 HandwritingEngine。
 */
package com.osfans.trime.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
class HandwritingInkView(context: Context) : View(context) {
    /** 每次抬笔回调；面板在这里安排一次防抖识别。 */
    var onStrokeFinished: (() -> Unit)? = null

    private val strokes = mutableListOf<InkStroke>()
    private val finishedPaths = mutableListOf<Path>()
    private var currentPoints = mutableListOf<Pair<Float, Float>>()
    private var currentPath = Path()

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val inkPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(235, 33, 33, 33)
        }
    private val guidePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = Color.argb(40, 128, 128, 128)
        }

    /** 主线程调用：拿到当前笔迹的不可变快照。 */
    fun snapshot(): List<InkStroke> = strokes.toList()

    fun clear() {
        strokes.clear()
        finishedPaths.clear()
        currentPoints = mutableListOf()
        currentPath = Path()
        invalidate()
    }

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.lastIndex)
        if (finishedPaths.isNotEmpty()) finishedPaths.removeAt(finishedPaths.lastIndex)
        invalidate()
        onStrokeFinished?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 米字格：只作视觉参照，不参与识别。
        canvas.drawLine(w / 2f, 0f, w / 2f, h, guidePaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, guidePaint)
        canvas.drawLine(0f, 0f, w, h, guidePaint)
        canvas.drawLine(w, 0f, 0f, h, guidePaint)
        for (p in finishedPaths) canvas.drawPath(p, inkPaint)
        canvas.drawPath(currentPath, inkPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPoints = mutableListOf()
                currentPath = Path()
                addPoint(event.x, event.y, start = true)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(i), event.getHistoricalY(i), start = false)
                }
                addPoint(event.x, event.y, start = false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishStroke()
            else -> return super.onTouchEvent(event)
        }
        invalidate()
        return true
    }

    private fun addPoint(x: Float, y: Float, start: Boolean) {
        // 采样过密会让 512 长度的输入序列被前几笔吃光，按 2dp 稀疏化。
        val last = currentPoints.lastOrNull()
        if (last != null) {
            val dx = x - last.first
            val dy = y - last.second
            if (dx * dx + dy * dy < dp(2f) * dp(2f)) return
        }
        currentPoints += x to y
        if (start) currentPath.moveTo(x, y) else currentPath.lineTo(x, y)
    }

    private fun finishStroke() {
        if (currentPoints.size >= 2) {
            strokes += InkStroke(currentPoints.toList())
            finishedPaths += Path(currentPath)
        }
        currentPoints = mutableListOf()
        currentPath = Path()
        onStrokeFinished?.invoke()
    }
}
