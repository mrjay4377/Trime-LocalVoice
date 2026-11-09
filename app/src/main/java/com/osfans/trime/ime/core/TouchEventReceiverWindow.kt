/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import kotlin.math.roundToInt

class TouchEventReceiverWindow(
    private val contentView: View,
) {
    private val ctx = contentView.context

    private val window =
        PopupWindow(
            object : View(ctx) {
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean = contentView.dispatchTouchEvent(event)
            },
        ).apply {
            // disable animation
            animationStyle = 0
        }

    private var isWindowShowing = false

    private val cachedLocation = intArrayOf(0, 0)

    fun showAt(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        isWindowShowing = true
        if (window.isShowing) {
            window.update(x, y, w, h)
        } else {
            window.width = w
            window.height = h
            window.showAtLocation(contentView, Gravity.TOP or Gravity.START, x, y)
        }
    }

    fun isShowing(): Boolean = isWindowShowing

    fun show() {
        val (x, y) = cachedLocation.also { contentView.getLocationInWindow(it) }
        // 悬浮时预编辑栏会跟随卡片缩放：触摸窗口必须用缩放后的视觉尺寸，
        // 否则编码区右半边点不到、左半边多出一截透明拦截区。
        val width = (contentView.width * contentView.scaleX).roundToInt()
        val height = (contentView.height * contentView.scaleY).roundToInt()
        showAt(x, y, width, height)
    }

    fun dismiss() {
        if (isWindowShowing) {
            isWindowShowing = false
            window.dismiss()
        }
    }
}
