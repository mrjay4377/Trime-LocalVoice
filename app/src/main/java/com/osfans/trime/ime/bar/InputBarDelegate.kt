/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import android.os.Build
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.bar.ui.CandidateUi
import com.osfans.trime.ime.bar.ui.TabUi
import com.osfans.trime.ime.bar.ui.VoiceIndicatorUi
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.handwriting.HandwritingCandidateBridge
import com.osfans.trime.ime.handwriting.HandwritingWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class InputBarDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme
    private val windowManager: BoardWindowManager by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val candidate: CompactCandidateDelegate by instance()
    private val rime: RimeSession by instance()

    val themedHeight = theme.style.run { candidateViewHeight + commentHeight }

    private val prefs = AppPrefs.defaultInstance()

    private val hideQuickBar by prefs.keyboard.hideInputBar

    private val clipboardSuggestion by prefs.clipboard.clipboardSuggestion

    private val clipboardSuggestionTimeout by prefs.clipboard.clipboardSuggestionTimeout

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false

    @Keep
    private val onClipboardUpdateListener = ClipboardHelper.OnClipboardUpdateListener {
        if (!clipboardSuggestion) return@OnClipboardUpdateListener
        service.lifecycleScope.launch {
            if (it.text.isNullOrEmpty()) {
                isClipboardFresh = false
            } else {
                alwaysUi.clipboardUi.text.text = it.text.take(42)
                isClipboardFresh = true
                launchClipboardTimeoutJob()
            }
            evalAlwaysUiState()
        }
    }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardSuggestionTimeout * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalAlwaysUiState()
        }
    }

    private fun evalAlwaysUiState() {
        val newState =
            when {
                isClipboardFresh -> AlwaysUi.State.Clipboard
                isInlineSuggestionPresent -> AlwaysUi.State.InlineSuggestion
                else -> AlwaysUi.State.Toolbar
            }
        if (newState == alwaysUi.currentState) return
        alwaysUi.updateState(newState)
    }

    private val swipeDownHideKeyboardCallback: ((KeyBehavior) -> Unit) = { d ->
        if (d == KeyBehavior.SWIPE_DOWN) {
            service.requestHideSelf(0)
        }
    }

    /**
     * 悬浮工具栏的专用四向箭头拖动手柄；外层长按容器用它排除「按住即拖」区域。
     * 非悬浮或当前显示的是候选栏 / 剪贴栏时返回 null，长按拖动照常生效。
     */
    fun floatingDragHandle(): View? = alwaysUi.dragHandleView

    /** 卡片缩放时对四向把手做反向缩放，保持把手视觉尺寸恒定。 */
    fun setFloatingHandleInverseScale(scale: Float) = alwaysUi.setFloatingHandleInverseScale(scale)

    private val alwaysUi: AlwaysUi by lazy {
        AlwaysUi(context, scope) { action ->
            // A touch that started on the toolbar can finish after candidates replace it.
            // ViewAnimator keeps that touch target even when its child becomes GONE.
            if (barStateMachine.currentState != QuickBarStateMachine.State.Always) return@AlwaysUi
            if (action.isNotEmpty()) {
                commonKeyboardActionListener.listener.onAction(theme.resolveAction(action))
            } else {
                windowManager.attachWindow(SwitchOptionWindow(di))
            }
        }.apply {
            hideKeyboardButton.apply {
                setOnClickListener { service.requestHideSelf(0) }
                onSwipe = swipeDownHideKeyboardCallback
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val content = ClipboardHelper.lastBean?.text
                    content?.let { service.commitText(it) }
                    dismissClipboardSuggestion()
                }
                setOnLongClickListener {
                    ClipboardHelper.lastBean?.let {
                        AppUtils.launchClipEdit(context, it.id, ClipEditActivity.FROM_CLIPBOARD)
                    }
                    true
                }
            }
            clipboardUi.dismiss.setOnClickListener {
                dismissClipboardSuggestion()
            }
        }
    }

    private fun dismissClipboardSuggestion() {
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        isClipboardFresh = false
        evalAlwaysUiState()
    }

    private val candidateUi by lazy {
        CandidateUi(context, scope, candidate.view).apply {
            unrollButton.apply {
                onSwipe = swipeDownHideKeyboardCallback
            }
        }
    }

    private val tabUi by lazy {
        TabUi(context, scope)
    }

    /** 语音录音状态页：按住语音键时替换候选栏内容。 */
    private val voiceUi by lazy {
        VoiceIndicatorUi(context, scope)
    }

    /** ViewAnimator 的第 4 页（Always=0 / Candidate=1 / Tab=2 / Voice=3）。 */
    private val voicePageIndex = 3

    private var voiceRecording = false
    private var voiceRecognizing = false
    private var savedBarVisibility = View.VISIBLE

    private val barStateMachine =
        QuickBarStateMachine.new {
            switchUiByState(it)
        }

    val unrollButtonStateMachine =
        UnrollButtonStateMachine.new {
            when (it) {
                UnrollButtonStateMachine.State.ClickToAttachWindow -> {
                    setUnrollButtonToAttach()
                    setUnrollButtonEnabled(true)
                }

                UnrollButtonStateMachine.State.ClickToDetachWindow -> {
                    setUnrollButtonToDetach()
                    setUnrollButtonEnabled(true)
                    // 展开窗打开期间把候选栏锁在候选页：右上角的收起按钮在 candidateUi 里，
                    // 一旦 QuickBar 因为「候选为空」切回工具栏页，用户就点不到「再点返回」了。
                    barStateMachine.push(
                        QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
                        QuickBarStateMachine.BooleanKey.CandidateEmpty to false,
                    )
                }

                UnrollButtonStateMachine.State.Hidden -> {
                    setUnrollButtonEnabled(false)
                }
            }
        }

    private var unrollBaseWindow: BoardWindow? = null

    private fun setUnrollButtonToAttach() {
        candidateUi.unrollButton.setOnClickListener {
            val base = windowManager.topWindow
            unrollBaseWindow = base
            val unrolled = FlexboxUnrolledCandidateWindow(di)
            unrolled.baseWindow = base
            // 手写候选必须在 attach 之前拷快照：attachWindow 会先 detach 手写面板。
            if (HandwritingCandidateBridge.active) {
                unrolled.handwritingCandidates = HandwritingCandidateBridge.candidates
                // 挂起手写面板 detach 时的 clear()，让候选栏与收起按钮在整个展开期间都保持可用。
                HandwritingCandidateBridge.suspendClear()
            }
            windowManager.attachWindow(unrolled)
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_more_24)
    }

    private fun setUnrollButtonToDetach() {
        candidateUi.unrollButton.setOnClickListener {
            // 收起展开窗时回到「打开展开窗前的那个窗口」：手写时回到手写面板，否则回键盘。
            // 二者类型不同（手写面板是 BoardWindow 实例，键盘是 ResidentWindow.Key），需分别调用重载。
            val base = unrollBaseWindow
            if (base != null) {
                windowManager.attachWindow(base)
            } else {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_less_24)
    }

    private fun setUnrollButtonEnabled(enabled: Boolean) {
        candidateUi.unrollButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        // 手写面板挂载时，候选栏是否为空以桥接候选为准，否则 Rime 的空候选会把
        // 状态机切回工具栏页，主候选栏（含手写候选与提示码）就被顶掉。
        // 展开候选窗期间额外锁定为「非空」，避免宿主 App 的空候选广播把收起按钮顶掉。
        val unrollOpen =
            unrollButtonStateMachine.currentState ==
                UnrollButtonStateMachine.State.ClickToDetachWindow
        val empty =
            when {
                unrollOpen -> false
                HandwritingCandidateBridge.active -> HandwritingCandidateBridge.candidates.isEmpty()
                else -> data.candidates.isEmpty()
            }
        barStateMachine.push(
            QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
            QuickBarStateMachine.BooleanKey.CandidateEmpty to empty,
        )
    }

    private fun switchUiByState(state: QuickBarStateMachine.State) {
        // 录音或「识别中」期间都锁定在语音标注页，候选更新不能把它顶掉；
        // 否则松手进入识别中后，候选栏刷新会把「识别中…」提示页抢走。
        if (voiceRecording || voiceRecognizing) return
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != tabUi.root) {
            tabUi.setBackButtonOnClickListener { }
            tabUi.setTitle("")
            tabUi.removeExternal()
        }
        view.displayedChild = index
    }

    val view by lazy {
        ViewAnimator(context).apply {
            visibility =
                if (hideQuickBar) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            background =
                scope.decorDrawable(
                    "candidate_background",
                    "candidate_border_color",
                    dp(theme.style.candidateBorder),
                    dp(theme.style.candidateBorderRound),
                )
            add(alwaysUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(tabUi.root, lParams(matchParent, matchParent))
            add(voiceUi.root, lParams(matchParent, matchParent))

            evalAlwaysUiState()
            ClipboardHelper.addOnUpdateListener(onClipboardUpdateListener)
            syncToolbarOptionStates()
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit

                    override fun onViewDetachedFromWindow(v: View) {
                        removeOnAttachStateChangeListener(this)
                    }
                },
            )
        }
    }

    /**
     * 切换候选栏的「正在录音」标注页。由 CommonKeyboardActionListener 在按下/松手语音键时调用，
     * 状态机在录音期间被锁定，结束时按真实状态恢复候选栏。
     * 录音态与「识别中」态独立：松手进入识别中后页面仍可见，直到结果返回才收起。
     */
    fun setVoiceRecording(active: Boolean) {
        if (voiceRecording == active) return
        voiceRecording = active
        if (!voiceRecording) voiceUi.updateLevel(0f)
        updateVoicePage()
    }

    /** 识别中：保持语音页可见并常驻显示「识别中…」；结束后由控制器取消。 */
    fun setVoiceRecognizing(recognizing: Boolean) {
        if (voiceRecognizing == recognizing) return
        voiceRecognizing = recognizing
        if (recognizing && !voiceRecording) voiceUi.updatePartial("识别中…")
        updateVoicePage()
    }

    /** 录音态或识别中态任一为真时展示语音标注页；两者皆否时恢复候选栏。 */
    private fun updateVoicePage() {
        val show = voiceRecording || voiceRecognizing
        if (show) {
            if (view.displayedChild != voicePageIndex || view.visibility != View.VISIBLE) {
                savedBarVisibility = view.visibility
                view.visibility = View.VISIBLE
                view.displayedChild = voicePageIndex
            }
            voiceUi.setActive(voiceRecording || voiceRecognizing)
        } else {
            voiceUi.setActive(false)
            voiceUi.updatePartial("")
            switchUiByState(barStateMachine.currentState)
            view.visibility = savedBarVisibility
        }
    }

    /** 录音实时音量：驱动语音标注两侧的波形起伏。 */
    fun setVoiceLevel(level: Float) {
        if (voiceRecording) voiceUi.updateLevel(level)
    }

    /** 流式识别的中间结果：在语音标注里边说边显示，松手后由 setActive(false) 收起。 */
    fun setVoicePartial(text: String) {
        if (voiceRecording) voiceUi.updatePartial(text)
    }

    /** Restyles the bar after a scheme switch without rebuilding its views. */
    fun refreshColors() {
        view.background =
            scope.decorDrawable(
                "candidate_background",
                "candidate_border_color",
                context.dp(theme.style.candidateBorder),
                context.dp(theme.style.candidateBorderRound),
            )
        alwaysUi.refreshColors()
        candidateUi.refreshColors()
        voiceUi.refreshColors()
        // the compact candidate rows live inside candidateUi and re-bind via the delegate
        candidate.refreshColors()
        tabUi.refreshColors()
    }

    override fun onStartInput(info: EditorInfo) {
        evalAlwaysUiState()
    }

    override fun onWindowAttached(window: BoardWindow) {
        // 只有手写面板与其展开候选窗允许桥接保持挂载；切到别窗口时强制释放，
        // 防止展开窗被中途顶掉后手写候选残留、把普通键盘的 Rime 候选一直遮住。
        if (window !is HandwritingWindow && window !is FlexboxUnrolledCandidateWindow) {
            HandwritingCandidateBridge.forceClear()
        }
        if (window is BoardWindow.BarBoardWindow) {
            tabUi.setTitle(window.title)
            window.onCreateBarView()?.let { tabUi.addExternal(it, window.showTitle) }
            tabUi.setBackButtonOnClickListener {
                // 让菜单自己决定返回目标（从手写面板进来的「更多」菜单应回到手写面板）；
                // 未处理时维持默认：回普通键盘。
                if (!window.onBackPressed()) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
            barStateMachine.push(QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached)
        }
    }

    override fun onWindowDetached(window: BoardWindow) {
        barStateMachine.push(QuickBarStateMachine.TransitionEvent.WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(themedHeight))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            alwaysUi.inlineSuggestionsUi.setPinnedView(
                pinned?.let { inflateInlineContentView(it) },
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            alwaysUi.inlineSuggestionsUi.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalAlwaysUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? = suspendCancellableCoroutine { c ->
        // callback view might be null
        suggestion.inflate(context, suggestionSize, directExecutor) { v ->
            c.resume(v)
        }
    }

    /**
     * Seed the toolbar toggle buttons with the current value of their rime
     * options. Rime access stays here in the delegate: the buttons themselves
     * are pure views and only react to [updateButtonsStyle].
     */
    private fun syncToolbarOptionStates() {
        val options = alwaysUi.toggleOptions()
        if (options.isEmpty()) return
        rime.launchOnReady { api ->
            val states = options.associateWith { api.getRuntimeOption(it) }
            ContextCompat.getMainExecutor(context).execute {
                states.forEach { (option, enabled) ->
                    alwaysUi.updateButtonsStyle(option, enabled)
                }
            }
        }
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        alwaysUi.updateButtonsStyle(value.option, value.value)
    }
}
