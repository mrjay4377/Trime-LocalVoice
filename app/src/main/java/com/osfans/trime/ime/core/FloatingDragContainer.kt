/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.osfans.trime.R
import kotlin.math.hypot

/**
 * 悬浮键盘的「顶部拖动条」（搜狗式交互，v58 恢复）。
 *
 * 包住候选栏 / 工具栏整条：**按住 300ms 不动**即进入待拖动态，顶部叠一层半透明蓝色高亮并轻震动；
 * 之后手指移动超过 6dp 才开始拖动整块卡片。短按、候选栏横滑、按钮自身的长按动作都不受影响。
 *
 * 工具栏最左还有专用的「四向箭头」把手（按住即拖）。落在该把手上的整段手势由
 * [dragHandleProvider] 排除，本容器完全不接管，避免两种拖动方式互相抢事件。
 *
 * 重写 dispatchTouchEvent 而不是 onInterceptTouchEvent：容器必须自己声明消费 DOWN，
 * 否则后续 MOVE/UP 不会再派发过来，长按拖动收不到完整事件序列。
 */
class FloatingDragContainer(context: Context) : FrameLayout(context) {
    companion object {
        private const val LONG_PRESS_MS = 300L
        private const val CANCEL_SLOP_DP = 10f
        private const val DRAG_SLOP_DP = 6f
        private const val HIGHLIGHT_COLOR = 0x332196F3
    }

    /** 只有悬浮模式才启用长按拖动；普通 / 单手模式完全走原生事件分发。 */
    var floatingProvider: () -> Boolean = { false }

    /** 专用四向箭头把手：落在它上面的手势整段交给把手自己「按住即拖」。 */
    var dragHandleProvider: (() -> View?) = { null }

    /** 位移一律用屏幕绝对坐标，卡片整体缩放后也不会漂移。 */
    var onDragStart: ((Float, Float) -> Unit)? = null
    var onDragMove: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cancelSlop = density * CANCEL_SLOP_DP
    private val dragSlop = density * DRAG_SLOP_DP

    private val highlight =
        View(context).apply {
            setBackgroundColor(HIGHLIGHT_COLOR)
            isClickable = false
            isFocusable = false
            contentDescription = context.getString(R.string.floating_keyboard_drag_handle)
            visibility = GONE
        }

    private var content: View? = null
    private val hitRect = Rect()
    private var skipGesture = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastLocalX = 0f
    private var lastLocalY = 0f
    private var activateRawX = 0f
    private var activateRawY = 0f
    private var pending = false
    private var activated = false
    private var dragging = false

    /** 用屏幕坐标（与 MotionEvent.rawX/rawY 同系）判断落点是否在专用把手上。 */
    private fun isOnDragHandle(rawX: Float, rawY: Float): Boolean {
        val handle = dragHandleProvider() ?: return false
        if (handle.visibility != VISIBLE || !handle.isAttachedToWindow) return false
        return handle.getGlobalVisibleRect(hitRect) &&
            hitRect.contains(rawX.toInt(), rawY.toInt())
    }

    private val activateRunnable =
        Runnable {
            if (!pending || !floatingProvider()) return@Runnable
            pending = false
            activated = true
            // 以此刻手指位置作为拖动基准，长按期间的小位移不会让卡片跳一下。
            activateRawX = lastRawX
            activateRawY = lastRawY
            highlight.visibility = VISIBLE
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // 此刻故意不发 CANCEL：让按钮自己的长按动作仍有机会在 500ms 触发。
            // 真正开始移动时才取消子 View，避免拖完误触发按钮点击。
        }

    /** 内容在下、高亮在上；高亮层不接收触摸，只做「已进入拖动态」的视觉反馈。 */
    fun setContentView(content: View) {
        if (this.content === content) return
        removeAllViews()
        this.content = content
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            highlight,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun cancelPending() {
        pending = false
        removeCallbacks(activateRunnable)
    }

    private fun resetState() {
        cancelPending()
        activated = false
        dragging = false
        skipGesture = false
        highlight.visibility = GONE
    }

    /** 给仍持有 DOWN 的子 View 补一个 CANCEL，清掉 pressed 状态并阻止误触发点击 / 长按。 */
    private fun cancelChildren() {
        val now = SystemClock.uptimeMillis()
        val cancel =
            MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, lastLocalX, lastLocalY, 0)
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    /** 模式切换 / 视图重建时收尾，避免残留高亮或半截拖动态。 */
    fun resetDragState() {
        resetState()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!floatingProvider()) {
            if (pending || activated || dragging) resetState()
            return super.dispatchTouchEvent(ev)
        }

        lastRawX = ev.rawX
        lastRawY = ev.rawY
        lastLocalX = ev.x
        lastLocalY = ev.y

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 专用把手优先：整段手势走原生分发，由把手自己「按住即拖」。
                if (isOnDragHandle(ev.rawX, ev.rawY)) {
                    resetState()
                    skipGesture = true
                    return super.dispatchTouchEvent(ev)
                }
                if (ev.pointerCount != 1) {
                    resetState()
                    return super.dispatchTouchEvent(ev)
                }
                downRawX = ev.rawX
                downRawY = ev.rawY
                pending = true
                activated = false
                dragging = false
                postDelayed(activateRunnable, LONG_PRESS_MS)
                // 先把 DOWN 分发给子 View（按钮、候选词照常拿到按下事件）；
                // 无论子 View 是否消费，容器都声明消费 DOWN，后续 MOVE/UP 才会继续派发过来。
                super.dispatchTouchEvent(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (skipGesture) return super.dispatchTouchEvent(ev)
                if (dragging) {
                    onDragMove?.invoke(ev.rawX, ev.rawY)
                    return true
                }
                if (activated) {
                    if (hypot(ev.rawX - activateRawX, ev.rawY - activateRawY) > dragSlop) {
                        dragging = true
                        cancelChildren()
                        onDragStart?.invoke(activateRawX, activateRawY)
                        onDragMove?.invoke(ev.rawX, ev.rawY)
                    }
                    // 待拖动态里 MOVE 不再给子 View，避免候选栏跟着一起横滑。
                    return true
                }
                // 长按判定前就滑动：判定取消，手势完整交给候选栏 / 按钮。
                if (pending && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > cancelSlop) {
                    cancelPending()
                }
                return super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (skipGesture) {
                    skipGesture = false
                    return super.dispatchTouchEvent(ev)
                }
                if (dragging) {
                    onDragEnd?.invoke()
                    resetState()
                    return true
                }
                if (activated) {
                    // 长按后没移动就抬手：不算点击，也不算拖动。
                    cancelChildren()
                    resetState()
                    return true
                }
                cancelPending()
                return super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                resetState()
                return super.dispatchTouchEvent(ev)
            }

            else -> return super.dispatchTouchEvent(ev)
        }
    }

    override fun onDetachedFromWindow() {
        resetState()
        super.onDetachedFromWindow()
    }
}
