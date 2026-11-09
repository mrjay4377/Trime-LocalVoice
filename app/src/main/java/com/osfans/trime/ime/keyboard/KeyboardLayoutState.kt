/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 键盘的四种排布方式；单手与悬浮互斥，避免两套逻辑同时改动键盘宽度。 */
enum class KeyboardLayoutMode {
    NORMAL,
    ONE_HAND_LEFT,
    ONE_HAND_RIGHT,
    FLOATING,
    ;

    val isOneHand: Boolean get() = this == ONE_HAND_LEFT || this == ONE_HAND_RIGHT
}

/**
 * 单手 / 悬浮键盘的全局状态（对齐咩版交互）。
 *
 * 不放在 AppPrefs 里：这两项是键盘上的即时操作（宫格菜单、主题长按开关），
 * 不是设置页里的配置项；用独立的小型 SharedPreferences 持久化，
 * 并通过监听器通知 InputView 与工具栏立刻重排，无需重建整个输入视图。
 */
object KeyboardLayoutState {
    private const val PREFS = "keyboard_layout"
    private const val KEY_MODE = "mode"
    private const val KEY_FLOAT_X = "float_x"
    private const val KEY_FLOAT_Y = "float_y"
    private const val KEY_FLOAT_WIDTH = "float_width_percent"
    private const val KEY_FLOAT_HEIGHT = "float_height_percent"

    /** 悬浮键盘缩放范围，与咩版设置滑块一致。 */
    const val MIN_SIZE_PERCENT = 40
    const val MAX_SIZE_PERCENT = 100
    private const val DEFAULT_SIZE_PERCENT = 90

    private val _modeFlow = MutableStateFlow(KeyboardLayoutMode.NORMAL)
    val modeFlow = _modeFlow.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val layoutListeners = mutableSetOf<() -> Unit>()

    /** 四向箭头拖动手柄的触摸事件，由 InputView 提供实际的移动逻辑。 */
    var onFloatingDrag: ((MotionEvent) -> Boolean)? = null

    fun addLayoutListener(listener: () -> Unit) {
        layoutListeners += listener
    }

    fun removeLayoutListener(listener: () -> Unit) {
        layoutListeners -= listener
    }

    private fun dispatchLayoutChange() {
        layoutListeners.toList().forEach { runCatching { it() } }
    }

    /** 服务启动时读回上次的模式；重复调用是安全的。 */
    fun attach(context: Context) {
        _modeFlow.value =
            runCatching {
                val name = prefs(context).getString(KEY_MODE, KeyboardLayoutMode.NORMAL.name)
                if (name == null) KeyboardLayoutMode.NORMAL else KeyboardLayoutMode.valueOf(name)
            }.getOrDefault(KeyboardLayoutMode.NORMAL)
    }

    fun mode(): KeyboardLayoutMode = _modeFlow.value

    fun setMode(
        context: Context,
        mode: KeyboardLayoutMode,
    ) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        _modeFlow.value = mode
        dispatchLayoutChange()
    }

    /** 悬浮键盘的位移，单位 px；屏幕尺寸变化后由 InputView 自行夹取到可见范围。 */
    fun floatOffset(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_FLOAT_X, 0) to p.getInt(KEY_FLOAT_Y, 0)
    }

    fun setFloatOffset(
        context: Context,
        x: Int,
        y: Int,
    ) {
        prefs(context).edit().putInt(KEY_FLOAT_X, x).putInt(KEY_FLOAT_Y, y).apply()
    }

    /** 整块键盘的缩放档位（40–100），宽高共用一个值以保持等比、内容不变形。 */
    fun floatingSize(context: Context): Int =
        prefs(context).getInt(KEY_FLOAT_WIDTH, DEFAULT_SIZE_PERCENT)
            .coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)

    fun setFloatingSize(
        context: Context,
        percent: Int,
    ) {
        val value = percent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
        prefs(context).edit()
            .putInt(KEY_FLOAT_WIDTH, value)
            .putInt(KEY_FLOAT_HEIGHT, value)
            .apply()
        dispatchLayoutChange()
    }

    /** 双击缩放角条：还原到默认尺寸与位置。 */
    fun resetFloating(context: Context) {
        prefs(context).edit()
            .putInt(KEY_FLOAT_X, 0)
            .putInt(KEY_FLOAT_Y, 0)
            .putInt(KEY_FLOAT_WIDTH, DEFAULT_SIZE_PERCENT)
            .putInt(KEY_FLOAT_HEIGHT, DEFAULT_SIZE_PERCENT)
            .apply()
        dispatchLayoutChange()
    }
}
