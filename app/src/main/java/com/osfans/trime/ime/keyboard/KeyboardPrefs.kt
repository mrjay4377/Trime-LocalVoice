/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.isLandscape

object KeyboardPrefs {
    private val prefs = AppPrefs.defaultInstance()

    private const val WIDE_SCREEN_WIDTH_DP = 600

    fun Context.isLandscapeMode(): Boolean = when (prefs.keyboard.landscapeMode.getValue()) {
        AppPrefs.Keyboard.LandscapeMode.WIDE -> resources.configuration.isLandscape() || isWideScreen()
        AppPrefs.Keyboard.LandscapeMode.LANDSCAPE -> resources.configuration.isLandscape()
        AppPrefs.Keyboard.LandscapeMode.ALWAYS -> true
        else -> false
    }

    private fun Context.isWideScreen(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels / metrics.density > WIDE_SCREEN_WIDTH_DP
    }

    /**
     * 设备是否为宽屏（平板 / 折叠屏内屏）。与上面的 isWideScreen() 不同，这里用
     * smallestScreenWidthDp：它不随旋屏变化，才能稳定判断「这台设备是否本来就该双手用」。
     * 单手模式据此在宽屏与横屏上自动回到普通键盘。
     */
    fun Context.isWideScreenDevice(): Boolean =
        resources.configuration.smallestScreenWidthDp >= WIDE_SCREEN_WIDTH_DP
}
