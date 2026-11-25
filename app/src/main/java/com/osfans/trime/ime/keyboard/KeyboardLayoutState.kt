/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.view.MotionEvent
import com.osfans.trime.util.isLandscape
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
    // 横竖屏各自记忆悬浮缩放与位置（对齐大海版）：竖屏沿用上面的旧 key 以兼容存量为 0 的用户，
    // 横屏用带 _land 后缀的独立 key；首次进入横屏读旧值作为初值，之后独立保存，互不干扰。
    private const val KEY_FLOAT_X_LAND = "float_x_land"
    private const val KEY_FLOAT_Y_LAND = "float_y_land"
    private const val KEY_FLOAT_WIDTH_LAND = "float_width_percent_land"
    private const val KEY_FLOAT_HEIGHT_LAND = "float_height_percent_land"
    private const val KEY_ONE_HAND_WIDTH = "one_hand_width_percent"

    /** 悬浮键盘缩放范围，与咩版设置滑块一致。 */
    const val MIN_SIZE_PERCENT = 40
    const val MAX_SIZE_PERCENT = 100
    private const val DEFAULT_SIZE_PERCENT = 90

    /**
     * 单手键盘宽度档位：占**屏幕短边**的百分比，最终宽度还会被 InputView 用
     * 280–400dp 的物理上下限夹取，避免大屏上键盘过宽、小屏上按键过窄。
     * 与悬浮的 40–100 缩放档位完全独立，改这里不会影响已经定型的悬浮手感。
     */
    const val MIN_ONE_HAND_WIDTH_PERCENT = 60
    const val MAX_ONE_HAND_WIDTH_PERCENT = 100
    const val ONE_HAND_WIDTH_NARROW = 70
    const val ONE_HAND_WIDTH_STANDARD = 85
    const val ONE_HAND_WIDTH_WIDE = 100

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

    /** 横屏用带 _land 后缀的独立 key，竖屏用原始 key。 */
    private fun offsetKeys(context: Context) =
        if (context.resources.configuration.isLandscape()) {
            KEY_FLOAT_X_LAND to KEY_FLOAT_Y_LAND
        } else {
            KEY_FLOAT_X to KEY_FLOAT_Y
        }

    private fun sizeKeys(context: Context) =
        if (context.resources.configuration.isLandscape()) {
            KEY_FLOAT_WIDTH_LAND to KEY_FLOAT_HEIGHT_LAND
        } else {
            KEY_FLOAT_WIDTH to KEY_FLOAT_HEIGHT
        }

    /**
     * 悬浮键盘的位移，单位 px；屏幕尺寸变化后由 InputView 自行夹取到可见范围。
     * 横竖屏各自独立；横屏尚未单独保存过时回退竖屏值作为初值。
     */
    fun floatOffset(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        val (kx, ky) = offsetKeys(context)
        if (kx == KEY_FLOAT_X_LAND && !p.contains(kx) && !p.contains(ky)) {
            return p.getInt(KEY_FLOAT_X, 0) to p.getInt(KEY_FLOAT_Y, 0)
        }
        return p.getInt(kx, 0) to p.getInt(ky, 0)
    }

    fun setFloatOffset(
        context: Context,
        x: Int,
        y: Int,
    ) {
        val (kx, ky) = offsetKeys(context)
        prefs(context).edit().putInt(kx, x).putInt(ky, y).apply()
    }

    /**
     * 整块键盘的缩放档位（40–100），宽高共用一个值以保持等比、内容不变形。
     * 横竖屏各自独立；横屏尚未单独保存过时回退竖屏值作为初值。
     */
    fun floatingSize(context: Context): Int {
        val p = prefs(context)
        val (kw, _) = sizeKeys(context)
        val stored =
            if (kw == KEY_FLOAT_WIDTH_LAND && !p.contains(kw)) {
                p.getInt(KEY_FLOAT_WIDTH, DEFAULT_SIZE_PERCENT)
            } else {
                p.getInt(kw, DEFAULT_SIZE_PERCENT)
            }
        return stored.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
    }

    fun setFloatingSize(
        context: Context,
        percent: Int,
    ) {
        val value = percent.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
        val (kw, kh) = sizeKeys(context)
        prefs(context).edit()
            .putInt(kw, value)
            .putInt(kh, value)
            .apply()
        dispatchLayoutChange()
    }

    /** 单手宽度档位（宫格里切换），写入后让 InputView 立刻重排卡片宽度。 */
    fun oneHandWidthPercent(context: Context): Int =
        prefs(context).getInt(KEY_ONE_HAND_WIDTH, ONE_HAND_WIDTH_STANDARD)
            .coerceIn(MIN_ONE_HAND_WIDTH_PERCENT, MAX_ONE_HAND_WIDTH_PERCENT)

    fun setOneHandWidthPercent(
        context: Context,
        percent: Int,
    ) {
        val value = percent.coerceIn(MIN_ONE_HAND_WIDTH_PERCENT, MAX_ONE_HAND_WIDTH_PERCENT)
        if (value == oneHandWidthPercent(context)) return
        prefs(context).edit().putInt(KEY_ONE_HAND_WIDTH, value).apply()
        dispatchLayoutChange()
    }

    /** 双击缩放角条：把当前朝向还原到默认尺寸与位置（另一朝向不受影响）。 */
    fun resetFloating(context: Context) {
        val (kx, ky) = offsetKeys(context)
        val (kw, kh) = sizeKeys(context)
        prefs(context).edit()
            .putInt(kx, 0)
            .putInt(ky, 0)
            .putInt(kw, DEFAULT_SIZE_PERCENT)
            .putInt(kh, DEFAULT_SIZE_PERCENT)
            .apply()
        dispatchLayoutChange()
    }
}
