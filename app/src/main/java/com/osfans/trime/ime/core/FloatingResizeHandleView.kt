/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 缩放角条的外观与手势参考「同文输入法咩版」trime-mie / CatTrime（GPL-3.0）。
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * 悬浮卡片两个下角的「白底蓝边」L 形缩放条，沿卡片圆角外沿绘制。
 *
 * 触摸盒比可见线条大得多，方便拇指按住；拖动按 raw 坐标计算增量，
 * 避免手柄随键盘一起移动时局部坐标漂移。双击还原默认尺寸与位置。
 */
@SuppressLint("ViewConstructor")
class FloatingResizeHandleView(
    context: Context,
    private val fromLeft: Boolean,
    private val onResize: (dx: Float, dy: Float) -> Unit,
    private val onResizeEnd: () -> Unit,
    private val onReset: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val outerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(5f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            // 蓝色描边（Material Blue 500），内层仍是白线，保证深浅主题下都清晰。
            color = 0xFF2196F3.toInt()
        }
    private val innerPaint =
        Paint(outerPaint).apply {
            strokeWidth = dp(3.5f)
            color = Color.WHITE
        }

    private val contour = Path()
    private val arm = dp(7f)
    private var cornerRadius = dp(12f)

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var started = false
    private var lastTapTime = 0L

    fun setCornerRadius(radius: Float) {
        val next = radius.coerceAtLeast(0f)
        if (cornerRadius == next) return
        cornerRadius = next
        rebuildContour()
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildContour()
    }

    private fun rebuildContour() {
        val cx = width / 2f
        val cy = height / 2f
        val r = cornerRadius
        val offset = outerPaint.strokeWidth / 2f
        contour.reset()
        if (fromLeft) {
            contour.moveTo(cx - offset, cy - r - arm)
            contour.lineTo(cx - offset, cy - r)
            contour.arcTo(
                RectF(cx - offset, cy - 2 * r - offset, cx + 2 * r + offset, cy + offset),
                180f,
                -90f,
            )
            contour.lineTo(cx + r + arm, cy + offset)
        } else {
            contour.moveTo(cx + offset, cy - r - arm)
            contour.lineTo(cx + offset, cy - r)
            contour.arcTo(
                RectF(cx - 2 * r - offset, cy - 2 * r - offset, cx + offset, cy + offset),
                0f,
                90f,
            )
            contour.lineTo(cx - r - arm, cy + offset)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(contour, outerPaint)
        canvas.drawPath(contour, innerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                started = false
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!started && hypot(dx, dy) < dp(2f)) return true
                if (!started) {
                    started = true
                    // 重新以判定点为基准，避免起步位移一次性计入尺寸
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
                onResize(event.rawX - lastRawX, event.rawY - lastRawY)
                lastRawX = event.rawX
                lastRawY = event.rawY
            }

            MotionEvent.ACTION_UP -> {
                if (started) {
                    onResizeEnd()
                    started = false
                } else {
                    val now = event.eventTime
                    if (now - lastTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                        onReset()
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                    }
                    performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (started) onResizeEnd()
                started = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        /** 触摸盒边长：角线长度再加余量，保证拇指好按。 */
        const val BOX_SIZE_DP = 56
    }
}
