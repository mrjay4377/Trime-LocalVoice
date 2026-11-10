/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardLayoutMode
import com.osfans.trime.ime.keyboard.KeyboardLayoutState
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isWideScreenDevice
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.util.isLandscape
import com.osfans.trime.util.isNightMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.allInstances
import org.kodein.di.bindInstance
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    scope: ThemeScope,
) : BaseInputView(service, rime, scope),
    DIAware {
    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job
    private val layoutListener: () -> Unit = {
        applyKeyboardLayoutMode()
        service.applyFloatingWindowMode()
    }

    /** 单手卡片的物理宽度上下限与外侧安全边距（dp），避免按键被压得过窄或贴边误触。 */
    private companion object {
        const val ONE_HAND_MIN_WIDTH_DP = 280
        const val ONE_HAND_MAX_WIDTH_DP = 400
        const val ONE_HAND_SAFE_PADDING_DP = 8
    }

    /**
     * 单手宽度的参考基准：**屏幕短边**。旋屏只交换宽高，短边不变，
     * 因此同一档位在竖屏 / 横屏 / 折叠屏展开后得到的是同一个物理宽度。
     */
    private fun referenceWidthPx(): Int {
        val metrics = resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels)
    }

    /** 悬浮 / 单手模式下整块键盘被裁成圆角卡片，用它同时提供圆角与投影。 */
    private val floatingOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(
                view: View,
                outline: Outline,
            ) {
                outline.setRoundRect(0, 0, view.width, view.height, service.dp(12).toFloat())
            }
        }

    /** 主题里配置的「键盘背景」：可能是一张图片，也可能是一个纯色。 */
    private fun themedKeyboardBackground(): Drawable? = scope.drawable("keyboard_background")

    /**
     * 主题有没有**单独**配置键盘背景。
     *
     * ColorKey 的回退链是 keyboard_background → keyboard_back_color，多数主题并没有显式写
     * keyboard_background，解析出来其实就是 keyboard_back_color（默认主题里是纯白）。
     * 这种情况下不能拿它当「主题背景」，要用主题整体底色，否则悬浮后永远是一块白板。
     */
    private fun hasExplicitKeyboardBackground(): Boolean {
        val background = themedKeyboardBackground() ?: return false
        // 图片背景一定是主题单独配置的
        if (background !is GradientDrawable) return true
        val color = runCatching { scope.color("keyboard_background") }.getOrNull() ?: return true
        val fallback = runCatching { scope.color("keyboard_back_color") }.getOrNull() ?: return true
        return color != fallback
    }

    /**
     * 悬浮 / 单手卡片的实心底色（v51 形态 + 主题色调）。
     *
     * 窗口层（Service）负责把 IME 窗口清成透明并做触摸穿透，卡片之外仍能透出聊天内容；
     * 但**卡片本身必须是实心的**。底色按以下顺序取，全部强制不透明：
     * 1. 主题单独配置的键盘背景色；
     * 2. 主题整体背景 root_background / back_color（深色主题跟着变深）；
     * 3. 实在解析不到才用日夜中性色。
     */
    private fun themedSolidBackdrop(): Drawable {
        val explicit =
            if (hasExplicitKeyboardBackground()) {
                runCatching { scope.color("keyboard_background") }.getOrNull()
            } else {
                null
            }
        val tone =
            explicit
                ?: listOf("root_background", "back_color", "keyboard_back_color")
                    .firstNotNullOfOrNull { key -> runCatching { scope.color(key) }.getOrNull() }
        val argb =
            tone?.let { 0xFF000000.toInt() or (it and 0x00FFFFFF) }
                ?: if (resources.configuration.isNightMode()) {
                    0xFF1C1B1F.toInt()
                } else {
                    0xFFFFFBFE.toInt()
                }
        return ColorDrawable(argb)
    }

    /**
     * 卡片底色 = 主题色调；主题若配了背景图片，则由 [keyboardBackground] 铺在上层，
     * 图片透明处露出的仍是主题底色，不会透出后面的应用，也不会再叠一层白色键盘色。
     */
    private fun applyFloatingCardBackdrop() {
        keyboardView.background = themedSolidBackdrop()
        val background = themedKeyboardBackground()
        keyboardBackground.imageDrawable =
            if (background != null && background !is GradientDrawable) {
                background.mutate().apply { alpha = 255 }
            } else {
                null
            }
    }

    /**
     * 退出卡片模式时恢复普通键盘的背景层。
     *
     * 普通键盘原先只有 [keyboardBackground] 一层，而 `keyboard_background` 的回退链是
     * `keyboard_background → keyboard_back_color`（多数主题是白色），夜间切换配色时整块键盘
     * 会显白。这里给键盘区补一层与悬浮卡片同源的主题实心底色，图片背景仍铺在上层；
     * 悬浮 / 单手模式由窗口层保持透明，不受影响。
     */
    private fun restoreNormalKeyboardBackground() {
        keyboardView.background = themedSolidBackdrop()
        keyboardBackground.imageDrawable = themedKeyboardBackground()
    }

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f
    private var resizeAccumPercent = 100f

    /** 咩版式下角缩放条：两个白底粉边 L 形手柄，拖动等比缩放整块键盘。 */
    private val leftResizeHandle =
        FloatingResizeHandleView(
            context,
            fromLeft = true,
            onResize = { dx, dy -> resizeFloating(dx, dy, fromLeft = true) },
            onResizeEnd = ::endFloatingResize,
            onReset = ::resetFloatingKeyboard,
        )

    private val rightResizeHandle =
        FloatingResizeHandleView(
            context,
            fromLeft = false,
            onResize = { dx, dy -> resizeFloating(dx, dy, fromLeft = false) },
            onResizeEnd = ::endFloatingResize,
            onReset = ::resetFloatingKeyboard,
        )

    private fun resizeFloating(
        dx: Float,
        dy: Float,
        fromLeft: Boolean,
    ) {
        val signedX = if (fromLeft) -dx else dx
        // 等比缩放取主导轴，两个角条都保持整块键盘（含候选/工具栏）不变形
        val delta = if (abs(signedX) >= abs(dy)) signedX else dy
        val parentWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        resizeAccumPercent += delta / (parentWidth / 100f)
        val percent =
            resizeAccumPercent.roundToInt().coerceIn(
                KeyboardLayoutState.MIN_SIZE_PERCENT,
                KeyboardLayoutState.MAX_SIZE_PERCENT,
            )
        keyboardView.scaleX = percent / 100f
        keyboardView.scaleY = percent / 100f
        syncHandlePlacement()
        syncFloatingPreeditPosition()
    }

    private fun endFloatingResize() {
        KeyboardLayoutState.setFloatingSize(service, resizeAccumPercent.roundToInt())
        // 缩放改变了卡片与编码栏的视觉尺寸，收手后刷新一次可触摸区域。
        service.applyFloatingWindowMode()
    }

    private fun resetFloatingKeyboard() {
        KeyboardLayoutState.resetFloating(service)
        Toast.makeText(service, R.string.floating_keyboard_reset, Toast.LENGTH_SHORT).show()
    }

    /** 四向箭头拖动手柄的事件转发：DOWN 记基准、MOVE 跟手、UP 落位保存。 */
    private fun handleFloatingDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startFloatingDrag(event.rawX, event.rawY)
            MotionEvent.ACTION_MOVE -> updateFloatingDrag(event.rawX, event.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishFloatingDrag()
        }
        return true
    }

    /**
     * 悬浮卡片的实际位移逻辑。
     *
     * 拆成 start / move / end 三段：拖动基准是手指按下的位置，卡片整体缩放后用屏幕绝对
     * 坐标换算，不会随 scale 漂移；松手才保存位移并刷新可触摸区域。
     */
    private fun startFloatingDrag(
        rawX: Float,
        rawY: Float,
    ) {
        if (KeyboardLayoutState.mode() != KeyboardLayoutMode.FLOATING) return
        dragStartRawX = rawX
        dragStartRawY = rawY
        dragStartTranslationX = keyboardView.translationX
        dragStartTranslationY = keyboardView.translationY
    }

    private fun updateFloatingDrag(
        rawX: Float,
        rawY: Float,
    ) {
        if (KeyboardLayoutState.mode() != KeyboardLayoutMode.FLOATING) return
        val (x, y) = clampFloatingOffset(
            dragStartTranslationX + rawX - dragStartRawX,
            dragStartTranslationY + rawY - dragStartRawY,
        )
        keyboardView.translationX = x
        keyboardView.translationY = y
        syncHandlePlacement()
        syncFloatingPreeditPosition()
    }

    private fun finishFloatingDrag() {
        if (KeyboardLayoutState.mode() != KeyboardLayoutMode.FLOATING) return
        KeyboardLayoutState.setFloatOffset(
            service,
            keyboardView.translationX.toInt(),
            keyboardView.translationY.toInt(),
        )
        // 卡片落位后刷新 TOUCHABLE_INSETS_REGION，否则卡片之外仍按旧位置穿透。
        service.applyFloatingWindowMode()
    }

    override val di = DI {
        bindInstance<InputView> { this@InputView }
        bindInstance<ContextThemeWrapper> { themedContext }
        bindInstance<ThemeScope> { scope }
        bindInstance<Theme> { scope.theme }
        bindInstance<TrimeInputMethodService> { service }
        bindInstance<RimeSession> { rime }
        bindSingleton { InputBroadcaster() }
        bindSingleton { PopupDelegate(di) }
        bindSingleton { EnterKeyDisplayDelegate(di) }
        bindSingleton { PreeditDelegate(di) }
        bindSingleton { CommonKeyboardActionListener(di) }
        bindSingleton { BoardWindowManager(di) }
        bindSingleton { InputBarDelegate(di) }
        bindSingleton { CompactCandidateDelegate(di) }
        bindSingleton { KeyboardWindow(di) }
        bindSingleton { LiquidWindow(di) }
    }

    private val broadcaster: InputBroadcaster by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val preedit: PreeditDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()

    /**
     * 候选栏 / 工具栏整条包一层长按拖动容器：悬浮时按住 300ms 变蓝，之后拖动可移动卡片。
     * 最左的四向箭头把手由容器排除，仍保留自己的「按住即拖」，两种方式并存。
     */
    private val inputBarWrapper by lazy {
        FloatingDragContainer(context).apply {
            setContentView(inputBar.view)
            floatingProvider = { KeyboardLayoutState.mode() == KeyboardLayoutMode.FLOATING }
            dragHandleProvider = { inputBar.floatingDragHandle() }
            onDragStart = { x, y -> startFloatingDrag(x, y) }
            onDragMove = { x, y -> updateFloatingDrag(x, y) }
            onDragEnd = { finishFloatingDrag() }
        }
    }

    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.style.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.style.keyboardPaddingLand
    private val keyboardBottomPadding = theme.style.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.style.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    val keyboardView: View

    /** Restyles colors after a scheme switch without rebuilding the view tree. */
    fun refreshColors() {
        if (KeyboardLayoutState.mode() != KeyboardLayoutMode.NORMAL) {
            applyFloatingCardBackdrop()
        } else {
            restoreNormalKeyboardBackground()
        }
        popup.refreshColors()
        keyboardWindow.refreshColors()
        inputBar.refreshColors()
        preedit.refreshColors()
        windowManager.refreshColors()
    }

    init {
        // MUST call before any operation
        KeyboardLayoutState.attach(service)
        val receivers: List<InputBroadcastReceiver> by allInstances()
        receivers.forEach { broadcaster.addReceiver(it) }

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")

        keyboardView =
            constraintLayout {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                add(
                    inputBarWrapper,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBarWrapper)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBarWrapper)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBarWrapper)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
            }

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    windowManager.view.updateLayoutParams {
                        height = it
                    }
                }
            }

        updateKeyboardSize()

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )
        // 编码栏是 wrapContent：输入过程中出现 / 消失编码会改变自身尺寸与位置，
        // 每次布局变化都重新贴合卡片左上角（只改 translation，不会再触发 onLayout）。
        preedit.ui.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncFloatingPreeditPosition()
        }

        add(
            keyboardView,
            lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            },
        )

        // 缩放角条与键盘卡片是兄弟节点，用 translation 贴到卡片两个下角。
        val handleSize = dp(FloatingResizeHandleView.BOX_SIZE_DP)
        leftResizeHandle.contentDescription = context.getString(R.string.floating_keyboard_handle)
        rightResizeHandle.contentDescription = context.getString(R.string.floating_keyboard_handle)
        add(leftResizeHandle, lParams(handleSize, handleSize) { centerInParent() })
        add(rightResizeHandle, lParams(handleSize, handleSize) { centerInParent() })
        leftResizeHandle.elevation = dp(12).toFloat()
        rightResizeHandle.elevation = dp(12).toFloat()
        leftResizeHandle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncHandlePlacement() }
        rightResizeHandle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncHandlePlacement() }

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )
        popup.root.elevation = dp(24).toFloat()

        KeyboardLayoutState.onFloatingDrag = ::handleFloatingDrag
        KeyboardLayoutState.addLayoutListener(layoutListener)
        applyKeyboardLayoutMode()
        // 上次退出时可能停在悬浮模式，重建输入视图后要立刻同步窗口底色与可触摸区域。
        service.applyFloatingWindowMode()
    }

    /**
     * 按当前模式重排整块键盘（对齐咩版）：
     * 单手 = 整张卡片靠左/靠右沉底，不带缩放条也不允许拖动；
     * 悬浮 = 圆角卡片 + 整块等比缩放，缩放交给 scale，布局宽度不变，避免每帧重排按键。
     */
    private fun applyKeyboardLayoutMode() {
        val savedMode = KeyboardLayoutState.mode()
        val landscape = context.resources.configuration.isLandscape()
        val wideScreen = context.isWideScreenDevice()
        // 横屏 / 宽屏设备（平板、折叠屏内屏）本来就是双手场景，单手模式在这些状态下
        // 按普通键盘排布；存档不动，转回竖屏后仍是用户原来选的单手模式（对齐咩版）。
        val mode =
            if (savedMode.isOneHand && (landscape || wideScreen)) {
                KeyboardLayoutMode.NORMAL
            } else {
                savedMode
            }
        val parentWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        val floating = mode == KeyboardLayoutMode.FLOATING
        val card = floating || mode.isOneHand
        // 退出悬浮时清掉可能残留的高亮 / 半截拖动态，避免下次进入时带着上一次的状态。
        if (!floating) inputBarWrapper.resetDragState()

        val safePadding = dp(ONE_HAND_SAFE_PADDING_DP)
        keyboardView.updateLayoutParams<LayoutParams> {
            // 每次重排都先清掉单手专用边距，避免切回普通 / 悬浮时残留。
            leftMargin = 0
            rightMargin = 0
            when (mode) {
                KeyboardLayoutMode.NORMAL -> {
                    width = matchParent
                    horizontalBias = 0.5f
                    bottomMargin = 0
                }

                KeyboardLayoutMode.ONE_HAND_LEFT, KeyboardLayoutMode.ONE_HAND_RIGHT -> {
                    // 宽度档位以屏幕短边为基准，再用物理 dp 上下限夹住：旋屏、折叠屏、平板
                    // 得到的都是「一只拇指够得着」的实际宽度，不会跟着窗口宽度跳变。
                    val percent = KeyboardLayoutState.oneHandWidthPercent(service)
                    val minWidth = dp(ONE_HAND_MIN_WIDTH_DP)
                    val maxWidth =
                        minOf(
                            dp(ONE_HAND_MAX_WIDTH_DP),
                            (parentWidth - safePadding * 2).coerceAtLeast(minWidth),
                        )
                    width = (referenceWidthPx() * percent / 100f).roundToInt().coerceIn(minWidth, maxWidth)
                    horizontalBias = if (mode == KeyboardLayoutMode.ONE_HAND_LEFT) 0f else 1f
                    bottomMargin = 0
                    // 靠屏幕外侧留一点边距，避免最外侧键贴边误触 / 撞系统返回手势。
                    if (mode == KeyboardLayoutMode.ONE_HAND_LEFT) {
                        leftMargin = safePadding
                    } else {
                        rightMargin = safePadding
                    }
                }

                KeyboardLayoutMode.FLOATING -> {
                    width = matchParent
                    horizontalBias = 0.5f
                    bottomMargin = dp(16)
                }
            }
        }

        // 以左上角为缩放锚点，卡片不会在放大时越过窗口底边。
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
        if (floating) {
            val percent = KeyboardLayoutState.floatingSize(service)
            resizeAccumPercent = percent.toFloat()
            val scale = percent / 100f
            keyboardView.scaleX = scale
            keyboardView.scaleY = scale
        } else {
            resizeAccumPercent = 100f
            keyboardView.scaleX = 1f
            keyboardView.scaleY = 1f
        }

        if (card) {
            // 卡片本身铺主题实心底色 + 主题背景图；卡片之外保持透明（窗口层负责），
            // 这样键盘仍是 v51 / 咩版那种浮在聊天内容之上的圆角卡片。
            keyboardBackground.visibility = View.VISIBLE
            // 卡片高度是 wrapContent：子 View 用 MATCH_PARENT 会量不出高度，背景图整块消失，
            // 只剩下面那层纯色。必须用 0dp + 四边约束跟着卡片的实测尺寸铺满。
            keyboardBackground.updateLayoutParams<LayoutParams> {
                width = matchConstraints
                height = matchConstraints
            }
            applyFloatingCardBackdrop()
            keyboardView.isClickable = true
            keyboardView.elevation = dp(8).toFloat()
            keyboardView.outlineProvider = floatingOutlineProvider
            keyboardView.clipToOutline = true
        } else {
            keyboardBackground.visibility = View.VISIBLE
            keyboardBackground.updateLayoutParams<LayoutParams> {
                width = wrapContent
                height = wrapContent
            }
            restoreNormalKeyboardBackground()
            keyboardView.isClickable = false
            keyboardView.elevation = 0f
            keyboardView.clipToOutline = false
            keyboardView.outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        leftResizeHandle.visibility = if (floating) View.VISIBLE else View.GONE
        rightResizeHandle.visibility = if (floating) View.VISIBLE else View.GONE

        if (floating) {
            // 布局完成后再恢复位移与角条位置：此时 top/height 才是实测值，夹取范围才准确。
            keyboardView.post {
                if (KeyboardLayoutState.mode() != KeyboardLayoutMode.FLOATING) return@post
                val (savedX, savedY) = KeyboardLayoutState.floatOffset(service)
                val centeredX =
                    if (savedX == 0 && savedY == 0) {
                        (parentWidth - keyboardView.width * keyboardView.scaleX) / 2f -
                            keyboardView.left
                    } else {
                        savedX.toFloat()
                    }
                val (x, y) = clampFloatingOffset(centeredX, savedY.toFloat())
                keyboardView.translationX = x
                keyboardView.translationY = y
                syncHandlePlacement()
                syncFloatingPreeditPosition()
                // 卡片落位后再刷新一次可触摸区域，否则 insets 记的还是上一帧的位置。
                service.applyFloatingWindowMode()
            }
        } else {
            keyboardView.translationX = 0f
            keyboardView.translationY = 0f
            // 单手卡片靠边时把编码栏贴到卡片左上角；普通模式归零（函数内部按模式判断）。
            syncFloatingPreeditPosition()
        }
        keyboardView.requestLayout()
        if (card && !floating) {
            // 单手改的是布局宽度，keyboardView.left 要等重新 measure 之后才准确，
            // 布局完成后再贴一次编码栏，避免切模式那一帧仍停在旧位置。
            keyboardView.post { syncFloatingPreeditPosition() }
        }
    }

    /**
     * 悬浮 / 单手时把预编辑栏（编码串 / 软光标所在）贴到卡片左上角，并跟随拖动与缩放。
     *
     * 布局上它是 keyboardView 的兄弟节点（above + startOfParent），给 keyboardView 加的
     * translation / scale 不会带着它走；不做这一步，悬浮后编码栏仍留在「正常键盘」原位置。
     * 对齐咩版 syncFloatingPreeditPosition()：左上角为缩放锚点，水平跟随卡片、宽度按
     * 卡片 scaleX 缩放，高度保持原样以免编码文字被压得过小。
     */
    private fun syncFloatingPreeditPosition() {
        val root = preedit.ui.root
        val mode = KeyboardLayoutState.mode()
        // 单手卡片靠边后编码栏也要跟着卡片左上角；但单手只平移不缩放，
        // 按键网格是按新宽度重排的，编码文字保持原字号，不像悬浮那样一起缩小。
        val cardMode = mode == KeyboardLayoutMode.FLOATING || mode.isOneHand
        if (!cardMode) {
            root.translationX = 0f
            root.translationY = 0f
            root.scaleX = 1f
            root.translationZ = 0f
            return
        }
        if (keyboardView.width == 0) return
        val floating = mode == KeyboardLayoutMode.FLOATING
        root.pivotX = 0f
        root.pivotY = 0f
        root.scaleX = if (floating) keyboardView.scaleX else 1f
        root.translationX = keyboardView.left + keyboardView.translationX - root.left
        root.translationY = keyboardView.translationY
        // 悬浮卡片 8dp、按键弹层 24dp，编码区取中间档，避免被卡片圆角 / 阴影压住。
        root.translationZ = if (floating) dp(16).toFloat() else 0f
        preedit.updateTouchReceiverPosition()
    }

    /** 把两个缩放角条贴到卡片下角（视觉坐标含等比缩放与平移）。 */
    private fun syncHandlePlacement() {
        if (KeyboardLayoutState.mode() != KeyboardLayoutMode.FLOATING) return
        if (keyboardView.width == 0 || leftResizeHandle.width == 0) return
        val radius = dp(12) * keyboardView.scaleX
        leftResizeHandle.setCornerRadius(radius)
        rightResizeHandle.setCornerRadius(radius)
        val visualLeft = keyboardView.left + keyboardView.translationX
        val visualBottom = keyboardView.top + keyboardView.translationY +
            keyboardView.height * keyboardView.scaleY
        val visualRight = visualLeft + keyboardView.width * keyboardView.scaleX
        leftResizeHandle.translationX =
            visualLeft - leftResizeHandle.left - leftResizeHandle.width / 2f
        leftResizeHandle.translationY =
            visualBottom - leftResizeHandle.top - leftResizeHandle.height / 2f
        rightResizeHandle.translationX =
            visualRight - rightResizeHandle.left - rightResizeHandle.width / 2f
        rightResizeHandle.translationY =
            visualBottom - rightResizeHandle.top - rightResizeHandle.height / 2f
    }

    /** 把悬浮键盘的位移夹在 InputView 可见范围内，按缩放后的实际尺寸计算。 */
    private fun clampFloatingOffset(
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val parentWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        val parentHeight = if (height > 0) height else resources.displayMetrics.heightPixels
        val left = keyboardView.left.toFloat()
        val top = keyboardView.top.toFloat()
        val visualWidth = keyboardView.width * keyboardView.scaleX
        val visualHeight = keyboardView.height * keyboardView.scaleY
        val minX = -left
        val maxX = parentWidth - left - visualWidth
        val minY = -top + dp(8)
        val maxY = parentHeight - top - visualHeight
        val clampedX = if (minX <= maxX) x.coerceIn(minX, maxX) else 0f
        val clampedY = if (minY <= maxY) y.coerceIn(minY, maxY) else 0f
        return clampedX to clampedY
    }

    private val floatingBoundsLocation = intArrayOf(0, 0)

    /**
     * 悬浮卡片在窗口中的视觉矩形（含等比缩放与拖动位移）。
     * Service 用它把 TOUCHABLE_INSETS_REGION 限制在卡片范围内，
     * 卡片之外才能穿透给下面的应用 —— 这是「键盘浮在聊天内容中间」的关键。
     */
    fun getFloatingKeyboardBoundsInWindow(outBounds: Rect): Boolean {
        if (KeyboardLayoutState.mode() != KeyboardLayoutMode.FLOATING) return false
        if (!keyboardView.isShown || keyboardView.width == 0 || keyboardView.height == 0) return false
        keyboardView.getLocationInWindow(floatingBoundsLocation)
        val x = floatingBoundsLocation[0]
        val y = floatingBoundsLocation[1]
        val w = (keyboardView.width * keyboardView.scaleX).roundToInt()
        val h = (keyboardView.height * keyboardView.scaleY).roundToInt()
        outBounds.set(x, y, x + w, y + h)
        // 编码/候选浮层在卡片上方，也要能点，否则输入时预编辑区点击会穿透。
        val preeditRoot = preedit.ui.root
        // 编码栏槽位无论当前有没有文字都计入，避免输入过程中 region 高度反复变化把应用顶起。
        if (preeditRoot.width > 0 && preeditRoot.height > 0) {
            preeditRoot.getLocationInWindow(floatingBoundsLocation)
            // 编码栏跟随卡片缩放，可触摸区域要用视觉尺寸，别按未缩放的布局尺寸多圈一块。
            val visualWidth = (preeditRoot.width * preeditRoot.scaleX).roundToInt()
            val visualHeight = (preeditRoot.height * preeditRoot.scaleY).roundToInt()
            outBounds.union(
                floatingBoundsLocation[0],
                floatingBoundsLocation[1],
                floatingBoundsLocation[0] + visualWidth,
                floatingBoundsLocation[1] + visualHeight,
            )
        }
        return true
    }

    /** 两个下角缩放条露在卡片轮廓外，单独并入可触摸区域。 */
    fun addResizeHandlesToTouchableRegion(region: Region) {
        if (KeyboardLayoutState.mode() != KeyboardLayoutMode.FLOATING) return
        for (handle in listOf(leftResizeHandle, rightResizeHandle)) {
            if (!handle.isShown) continue
            handle.getLocationInWindow(floatingBoundsLocation)
            val l = floatingBoundsLocation[0]
            val t = floatingBoundsLocation[1]
            region.union(Rect(l, t, l + handle.width, t + handle.height))
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            keyboardView.post { applyKeyboardLayoutMode() }
        }
    }

    private fun updateKeyboardSize() {
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        val unset = LayoutParams.UNSET
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        inputBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }

            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
            }

            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }

            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    override fun onDetachedFromWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        KeyboardLayoutState.removeLayoutListener(layoutListener)
        popup.root.removeAllViews()
        broadcaster.clear()
        super.onDetachedFromWindow()
    }
}
