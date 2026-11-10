/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.ViewAnimator
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.keyboard.KeyboardLayoutMode
import com.osfans.trime.ime.keyboard.KeyboardLayoutState
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.ToolBar
import kotlin.math.hypot
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class AlwaysUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val onButtonClick: ((String) -> Unit)? = null,
) : Ui {
    private val theme: Theme get() = scope.theme

    enum class State {
        Toolbar,
        Clipboard,
        InlineSuggestion,
    }

    var currentState = State.Toolbar
        private set

    private fun toolButton(
        buttonConfig: ToolBar.Button?,
        @DrawableRes icon: Int = 0,
    ): ToolButton = if (buttonConfig != null) {
        ToolButton(ctx, buttonConfig, scope).apply {
            setOnClickListener { onButtonClick?.invoke(buttonConfig.action) }
            val longPressAction = buttonConfig.longPressAction
            if (longPressAction.isNotEmpty()) {
                setOnLongClickListener {
                    onButtonClick?.invoke(longPressAction)
                    true
                }
            }
        }
    } else {
        ToolButton(ctx, icon, scope).apply {
            setOnClickListener { onButtonClick?.invoke("") }
        }
    }

    val buttonsUi = ButtonsBarUi(ctx, scope, onButtonClick)

    val clipboardUi = ClipboardSuggestionUi(ctx, scope)

    val inlineSuggestionsUi = InlineSuggestionsUi(ctx)

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, scope)
    private val rightMostButton =
        ViewAnimator(ctx).apply {
            add(hideKeyboardButton, lParams(matchParent, matchParent))
            buttonsUi.firstButton?.let { add(it, lParams(matchParent, matchParent)) }
        }

    /** 悬浮键盘专用拖动手柄：中心圆点 + 四向箭头，按住即可移动整块卡片。 */
    private val dragButton =
        ToolButton(ctx, R.drawable.ic_baseline_drag_move_24, scope).apply {
            contentDescription = ctx.getString(R.string.floating_keyboard_drag_handle)
        }

    /**
     * 工具栏「更多」按钮之后的模式状态图标：悬浮 / 单手时显示对应图标，普通模式隐藏；
     * 点击直接关闭当前布局模式（悬浮 / 单手），回到普通全宽键盘。
     */
    private val modeButton =
        ToolButton(ctx, R.drawable.ic_baseline_floating_keyboard_24, scope).apply {
            visibility = View.GONE
            // setMode 会派发给 InputView 重排键盘、并让本工具栏隐藏图标，存档（位置 / 单手宽度）保留。
            setOnClickListener { KeyboardLayoutState.setMode(ctx, KeyboardLayoutMode.NORMAL) }
        }

    /** 供外层长按容器做命中排除：只有悬浮且把手可见时才把它交给对方。 */
    val dragHandleView: View?
        get() = dragButton.takeIf { isFloating() && it.visibility == View.VISIBLE }

    // 拖动手柄的按下基准与「是否已开始拖动」，用来避免点按时卡片轻微漂移。
    private var dragHandleDownRawX = 0f
    private var dragHandleDownRawY = 0f
    private var dragHandleDragging = false

    private val leftMostButton = toolButton(
        theme.toolBar.primaryButton,
        R.drawable.ic_baseline_more_horiz_24,
    )

    private val animator =
        ViewAnimator(ctx).apply {
            add(buttonsUi.root, lParams(matchParent, matchParent))
            add(clipboardUi.root, lParams(matchParent, matchParent))
            add(inlineSuggestionsUi.root, lParams(matchParent, matchParent))
        }

    override val root: ConstraintLayout = constraintLayout {
        val (leftWidth, leftHeight) = buttonsUi.getButtonSize(theme.toolBar.primaryButton)
        val (rightWidth, rightHeight) = buttonsUi.getButtonSize(theme.toolBar.buttons.firstOrNull())

        add(
            dragButton,
            lParams(leftWidth, leftHeight) {
                startOfParent()
                centerVertically()
            },
        )
        add(
            leftMostButton,
            lParams(leftWidth, leftHeight) {
                // 普通 / 单手模式下 dragButton 为 GONE，该约束回退到父布局左边；
                // 普通模式 modeButton 也为 GONE，leftMostButton 与 animator 最终贴父布局左边，外观不变。
                after(dragButton)
                centerVertically()
            },
        )
        add(
            modeButton,
            lParams(leftWidth, leftHeight) {
                after(leftMostButton)
                centerVertically()
            },
        )
        add(
            rightMostButton,
            lParams(rightWidth, rightHeight) {
                endOfParent()
                centerVertically()
            },
        )
        add(
            animator,
            lParams(matchConstraints, matchParent) {
                after(modeButton)
                before(rightMostButton)
                endOfParent()
                centerVertically()
            },
        )
    }.apply {
        updateRightMostButton(State.Toolbar)
    }

    private fun isFloating(): Boolean =
        KeyboardLayoutState.mode() == KeyboardLayoutMode.FLOATING

    /**
     * 工具栏始终完整展开；同步两件与布局模式相关的事：
     * 1) 悬浮模式显示最左的四向箭头拖动手柄；
     * 2) 悬浮 / 单手模式在「更多」按钮之后显示模式状态图标，点击关闭当前模式，普通模式隐藏
     *    （GONE 后 animator 的约束回退到 leftMostButton 右侧，工具栏外观完全恢复）。
     */
    private fun syncLayoutMode() {
        val floating = isFloating()
        dragButton.visibility = if (floating) View.VISIBLE else View.GONE
        when (KeyboardLayoutState.mode()) {
            KeyboardLayoutMode.FLOATING -> {
                modeButton.setIcon(R.drawable.ic_baseline_floating_keyboard_24)
                modeButton.contentDescription = ctx.getString(R.string.floating_keyboard)
                modeButton.visibility = View.VISIBLE
            }
            KeyboardLayoutMode.ONE_HAND_LEFT, KeyboardLayoutMode.ONE_HAND_RIGHT -> {
                modeButton.setIcon(R.drawable.ic_baseline_one_hand_mode_24)
                modeButton.contentDescription = ctx.getString(R.string.one_hand_keyboard_mode)
                modeButton.visibility = View.VISIBLE
            }
            KeyboardLayoutMode.NORMAL -> {
                modeButton.visibility = View.GONE
                modeButton.contentDescription = null
            }
        }
    }

    private val layoutListener: () -> Unit = { syncLayoutMode() }

    init {
        // 只有最左的四向箭头把手负责拖动：其余工具按钮的点击 / 长按、
        // 候选栏横滑都走原生分发，没有整条长按判定、变色反馈或收起态空白区。
        dragButton.setOnTouchListener { _, event ->
            if (!isFloating()) {
                false
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragHandleDownRawX = event.rawX
                        dragHandleDownRawY = event.rawY
                        dragHandleDragging = false
                        KeyboardLayoutState.onFloatingDrag?.invoke(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val moved =
                            hypot(
                                event.rawX - dragHandleDownRawX,
                                event.rawY - dragHandleDownRawY,
                            )
                        // 4dp 抖动阈值：阈值内不动卡片，避免手抖导致键盘漂移。
                        if (dragHandleDragging || moved >= ctx.dp(4).toFloat()) {
                            dragHandleDragging = true
                            KeyboardLayoutState.onFloatingDrag?.invoke(event)
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragHandleDragging) KeyboardLayoutState.onFloatingDrag?.invoke(event)
                    }
                }
                true
            }
        }
        KeyboardLayoutState.addLayoutListener(layoutListener)
        // InputView 重建时旧视图会反注册，避免监听器越积越多、也避免误清新视图的监听器。
        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    KeyboardLayoutState.removeLayoutListener(layoutListener)
                    root.removeOnAttachStateChangeListener(this)
                }
            },
        )
        syncLayoutMode()
    }

    fun updateButtonsStyle(option: String, enabled: Boolean) {
        leftMostButton.updateStyle(option, enabled)
        buttonsUi.firstButton?.updateStyle(option, enabled)
        buttonsUi.updateStyle(option, enabled)
    }

    fun toggleOptions(): Set<String> = buildSet {
        leftMostButton.option?.let { add(it) }
        buttonsUi.firstButton?.option?.let { add(it) }
        buttonsUi.root.children.forEach { (it as ToolButton).option?.let { add(it) } }
    }

    fun updateState(state: State) {
        Timber.d("Switch always ui to $state")
        animator.displayedChild = state.ordinal
        currentState = state
        updateRightMostButton(state)
        updateLeftMostButton(state)
    }

    /** Restyles the toolbar area after a scheme switch. */
    fun refreshColors() {
        leftMostButton.refreshColors()
        dragButton.refreshColors()
        modeButton.refreshColors()
        hideKeyboardButton.refreshColors()
        buttonsUi.refreshColors()
        clipboardUi.refreshColors()
    }

    private fun updateRightMostButton(state: State) {
        val hasFirstButton = buttonsUi.firstButton != null
        val showFirst = hasFirstButton && (theme.toolBar.buttons.isNotEmpty() || state != State.Toolbar)
        rightMostButton.displayedChild = if (showFirst) 1 else 0
    }

    private fun updateLeftMostButton(state: State) {
        val buttonConfig =
            if (state == State.Toolbar) {
                theme.toolBar.primaryButton
            } else {
                theme.toolBar.buttons.firstOrNull()
            }

        val (buttonWidth, buttonHeight) = buttonsUi.getButtonSize(buttonConfig)
        leftMostButton.layoutParams = leftMostButton.layoutParams.apply {
            width = buttonWidth
            height = buttonHeight
        }
    }
}
