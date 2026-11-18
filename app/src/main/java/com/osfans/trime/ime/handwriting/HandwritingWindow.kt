/*
 * 手写输入面板：挂在 BoardWindowManager 的键盘窗口容器里，点候选直接上屏。
 * 纯推理层已移到独立 Gradle 模块 :handwriting 与 :hw_infer 子进程，这里只做 UI 胶水与生命周期。
 *
 * 白屏修复：主题颜色解析在配色链缺失时会抛异常，协程未捕获异常会杀掉 IME 进程；
 * 这里统一改成「视图永远可见 + 异常可见化 + native 延后到首次落笔」。
 *
 * 布局：中间大书写区 + 右侧竖列（撤销 / 清空 / 删除）+ 底部一行
 * （符号 / 123 / 空格 / ABC / 换行）。中间行与底行共用 5.5 权重，右侧列占 1 份，
 * 与「换行」键同宽且右对齐，上下连成一条直线。顶部状态条已删除，面板直接从书写区开始。
 * 符号 / 123 / ABC 会临时展开字符面板，在书写区落笔会自动收起。
 * 退出手写面板改走「更多」菜单入口（手写输入项），面板内不再有「返回键盘」按钮。
 *
 * 候选只显示在键盘上方的主候选栏（InputBarDelegate / CompactCandidateDelegate 读
 * HandwritingCandidateBridge），面板内不再重复展示候选。
 * 单字上屏后会用已部署字典的词组行给出联想词，同样发布到主候选栏。
 */
package com.osfans.trime.ime.handwriting

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.handwriting.HandwritingInkView
import com.osfans.trime.handwriting.HandwritingModels
import com.osfans.trime.handwriting.HandwritingSettings
import com.osfans.trime.handwriting.InkCandidate
import com.osfans.trime.handwriting.InkStroke
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import timber.log.Timber
import kotlin.math.abs

class HandwritingWindow(di: DI) : BoardWindow.NoBarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val themeScope: ThemeScope by instance()
    private val broadcaster: InputBroadcaster by instance()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 删除键长按连删：复用主键盘设置里的长按超时与重复间隔，保持手感一致。 */
    private val longPressTimeout by AppPrefs.defaultInstance().keyboard.longPressTimeout
    private val keyRepeatInterval by AppPrefs.defaultInstance().keyboard.repeatInterval

    /** 任何协程异常都显示在状态栏，绝不让它冒泡杀掉 IME 进程造成白屏。 */
    private val exceptionHandler =
        CoroutineExceptionHandler { _, t ->
            Timber.e(t, "handwriting: uncaught coroutine error")
            mainHandler.post { safeStatus("手写出错：" + errorHint(t)) }
        }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    /**
     * 从 ∨ 展开候选窗收起时会重新 onAttached 同一个窗口实例；
     * 所以 onDetached 里挂起期间不能 cancel，且这里要能在必要时重建作用域。
     */
    private var scope = newScope()
    private var recognizeJob: Job? = null

    /** 手写推理跑在 :hw_infer 独立进程，子进程 native 崩了也不拖垮键盘 IME。 */
    @Volatile private var inferBinder: IHandwritingInference? = null
    private var bound = false
    private var binding = false

    private val conn =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                binding = false
                bound = true
                inferBinder = IHandwritingInference.Stub.asInterface(service)
                Timber.i("handwriting: inference service connected")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                inferBinder = null
                bound = false
                Timber.w("handwriting: inference service disconnected")
                mainHandler.post { safeStatus("手写推理进程已停止，可继续写或点「返回键盘」后重进恢复") }
            }
        }

    @Volatile private var modelReady = false

    /** 当前选中的提示码来源（HandwritingCodeSource.id，空串 = 关闭），与设置页共用。 */
    private var codeSourceId: String = HandwritingCodeLookup.OFF

    /**
     * 上一次主候选上屏的文本（CyIME 式「补完」的前缀基准）。
     * 联想词若以它为前缀，点上屏说明是补完该词（如「叶」→「叶子」），
     * 需「替换」已上屏的 prefix 为完整词，而非整词追加（否则变「叶叶子」）。
     * 写下一笔识别 / 退出面板时重置。
     */
    private var lastCommitted: String = ""

    private lateinit var inkView: HandwritingInkView
    private lateinit var statusView: TextView
    private lateinit var bottomHost: FrameLayout
    private lateinit var bottomMain: LinearLayout

    private var btnBgColor = 0
    private var btnTextColor = 0
    private var keyBgColor = 0
    private var keyTextColor = 0

    /** 符号 / 123 / ABC 的临时字符面板是否展开；展开时落笔会自动收起。 */
    private var charPanelOpen = false

    /** 底部容器收起时的高度（与「换行」所在主行一致）。 */
    private var bottomCollapsedPx = 0

    private fun safeStatus(text: String) {
        runCatching {
            if (::statusView.isInitialized) statusView.text = text
        }.onFailure { Timber.w(it, "handwriting: status update failed") }
    }

    /**
     * 即使主题颜色解析或控件组装抛异常，也返回一个带错误文字的不透明视图，
     * 不会让窗口容器空着露出一片白。
     */
    override fun onCreateView(): View {
        Timber.i("handwriting: onCreateView start")
        val result = runCatching { buildView() }
        result.onFailure { t ->
            Timber.e(t, "handwriting: onCreateView failed")
            statusView = errorTextView("手写面板初始化失败：" + errorHint(t))
        }
        return result.getOrElse {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                addView(statusView)
            }
        }
    }

    private fun errorTextView(message: String): TextView =
        TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        }

    /** 统一的方块按键：白底（普通键）或灰底（功能键），不用系统默认 Button 样式。
     *  repeatable = true 时支持长按连删（短按一次、按住超过超时后按固定间隔连续触发）。 */
    private fun keyButton(
        label: String,
        action: () -> Unit,
        bg: Int,
        fg: Int,
        size: Float = 14f,
        repeatable: Boolean = false,
    ): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = size
            setMinWidth(0)
            setMinimumWidth(0)
            setMinHeight(0)
            setMinimumHeight(0)
            setPadding(0, 0, 0, 0)
            setTextColor(fg)
            background =
                GradientDrawable().apply {
                    cornerRadius = 6f * context.resources.displayMetrics.density
                    setColor(bg)
                }
            if (repeatable) {
                attachRepeatOnLongPress(action)
            } else {
                setOnClickListener { action() }
            }
        }

    /**
     * 给按键挂上「短按一次 / 长按连发」的触摸逻辑：
     * - 按下后超过 longPressTimeout 仍未抬起 → 立刻触发一次，此后每 keyRepeatInterval 触发一次；
     * - 抬起或取消时停止所有连发；若整个按下过程没到长按阈值 → 按短按补触发一次。
     * 连发与短按互斥，用 longPressFired 去重，避免抬手瞬间多删一个。
     */
    private fun Button.attachRepeatOnLongPress(action: () -> Unit) {
        var longPressFired = false
        val repeatTick =
            object : Runnable {
                override fun run() {
                    action()
                    mainHandler.postDelayed(this, keyRepeatInterval.toLong())
                }
            }
        val longPressStart =
            object : Runnable {
                override fun run() {
                    longPressFired = true
                    isPressed = false
                    repeatTick.run() // 立即删一次，随后进入连发
                }
            }

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    isPressed = true
                    mainHandler.postDelayed(longPressStart, longPressTimeout.toLong())
                    true // 自己消费 DOWN，短按在 UP 里补触发
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressStart)
                    mainHandler.removeCallbacks(repeatTick)
                    isPressed = false
                    if (!longPressFired) action() // 短按：删一个
                    performClick() // 保留无障碍点击反馈
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressStart)
                    mainHandler.removeCallbacks(repeatTick)
                    isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    /** 底部按键：局部函数拿不到 apply{} 的隐式接收者，所以用成员方法显式传容器。 */
    private fun addBottomKey(
        container: LinearLayout,
        label: String,
        action: () -> Unit,
        weight: Float,
        bg: Int,
        fg: Int,
    ) {
        val gap = (3 * context.resources.displayMetrics.density).toInt()
        container.addView(
            keyButton(label, action, bg, fg),
            LinearLayout
                .LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                .apply { setMargins(gap, gap, gap, gap) },
        )
    }

    private fun buildView(): View {
        codeSourceId = HandwritingSettings.codeSource(context)
        val d = context.resources.displayMetrics.density
        val pad = (8 * d).toInt()
        val gap = (3 * d).toInt()
        val night =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val solidBg =
            themeColor { themeScope.colors.rootBackground }
                ?.let { 0xFF000000.toInt() or (it and 0x00FFFFFF) }
                ?: if (night) 0xFF202124.toInt() else 0xFFEFEFF2.toInt()
        val safeText =
            themeColor { themeScope.colors.candidateTextColor }
                ?.let { readable(it, solidBg) }
                ?: contrastText(solidBg)

        // 按键配色跟随主题：普通键取 keyBack/keyText，功能键（撤销/清空/删除/换行/符号）取
        // hilitedKeyBack/hilitedKeyText；取不到（主题该键为图片或未定义）才回退到按明度算的兜底色。
        // 文字色用 readable 校验对比度，避免个别主题的文字色与按键底色太接近看不清。
        val rawKeyBg = themeColor { themeScope.colors.keyBackColor }
        val rawKeyText = themeColor { themeScope.colors.keyTextColor }
        val rawBtnBg = themeColor { themeScope.colors.hilitedKeyBackColor }
        val rawBtnText = themeColor { themeScope.colors.hilitedKeyTextColor }
        keyBgColor =
            rawKeyBg?.let { 0xFF000000.toInt() or (it and 0x00FFFFFF) }
                ?: if (luminance(solidBg) > 0.5) 0xFFFDFDFD.toInt() else 0xFF2C3038.toInt()
        keyTextColor =
            rawKeyText?.let { readable(it, keyBgColor) } ?: contrastText(keyBgColor)
        btnBgColor =
            rawBtnBg?.let { 0xFF000000.toInt() or (it and 0x00FFFFFF) }
                ?: if (luminance(solidBg) > 0.5) 0xFFD5D9E2.toInt() else 0xFF3B404B.toInt()
        btnTextColor =
            rawBtnText?.let { readable(it, btnBgColor) } ?: contrastText(btnBgColor)

        // 书写区也跟随主题：底色取候选栏背景，边框取主题边框色；取不到回退到原来的中性色。
        val inkBg =
            themeColor { themeScope.colors.candidateBackground }
                ?.let { 0xFF000000.toInt() or (it and 0x00FFFFFF) }
                ?: 0xFFFAFAFA.toInt()
        val inkBorder = themeColor { themeScope.colors.borderColor } ?: 0xFF9AA0A6.toInt()

        // 顶部细条：正常状态留空，只在出错 / 模型缺失时显示文字。
        statusView =
            TextView(context).apply {
                text = ""
                textSize = 11f
                setSingleLine()
                setTextColor(safeText)
                setBackgroundColor(solidBg)
                setPadding(pad, 0, pad, 0)
            }

        inkView =
            HandwritingInkView(context).apply {
                background =
                    GradientDrawable().apply {
                        setColor(inkBg)
                        cornerRadius = 6 * d
                        setStroke((1 * d).toInt(), inkBorder)
                    }
                onStrokeFinished = { scheduleRecognize() }
                // 字符面板是临时状态：用户直接在书写区落笔就自动收起，不用去找「返回」。
                onWriteStart = { if (charPanelOpen) showBottomMain() }
            }
        // 笔迹色跟随书写区底色明度自适应：深底用浅笔、浅底用深笔。
        inkView.setInkColors(
            if (luminance(inkBg) > 0.5) 0xEB212121.toInt() else 0xEBF7F7F9.toInt(),
            if (luminance(inkBg) > 0.5) 0x28808080.toInt() else 0x28C0C4CC.toInt(),
        )

        // 右侧竖列：撤销 / 清空 / 删除，三个等分高度。整列占 1 份权重，与「换行」键同宽。
        val rightColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(solidBg)
                listOf<Pair<String, () -> Unit>>(
                    "撤销" to { inkView.undo() },
                    "清空" to { clearInk() },
                    "删除" to { deleteChar() },
                ).forEach { (label, action) ->
                    val isDelete = label == "删除"
                    addView(
                        keyButton(
                            label,
                            action,
                            if (isDelete) btnBgColor else keyBgColor,
                            if (isDelete) btnTextColor else keyTextColor,
                            13f,
                            repeatable = isDelete,
                        ),
                        LinearLayout
                            .LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                            .apply { setMargins(gap, gap, gap, gap) },
                    )
                }
            }

        // 底部一行：符号 / 123 / 空格 / ABC / 换行。
        bottomMain =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(solidBg)
            }
        addBottomKey(bottomMain, "符号", { showCharStrip(SYMBOLS, "符号") }, 1f, btnBgColor, btnTextColor)
        addBottomKey(bottomMain, "123", { showCharStrip(NUMBERS, "数字") }, 1f, keyBgColor, keyTextColor)
        addBottomKey(bottomMain, "空格", { commitChar(" ") }, 1.5f, keyBgColor, keyTextColor)
        addBottomKey(bottomMain, "ABC", { showCharStrip(LETTERS, "字母") }, 1f, keyBgColor, keyTextColor)
        addBottomKey(bottomMain, "换行", { service.handleReturnKey() }, 1f, btnBgColor, btnTextColor)

        bottomCollapsedPx = (48 * d).toInt()
        bottomHost = FrameLayout(context).apply { setBackgroundColor(solidBg) }
        showBottomMain()

        // 中间行和底行都按 5.5 份权重切分：书写区 4.5 + 右侧列 1，
        // 底行 1 + 1 + 1.5 + 1 + 1（换行）。因此右侧列与「换行」键同宽、右对齐。
        val middle =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(solidBg)
                addView(
                    inkView,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4.5f)
                        .apply { setMargins(gap, gap, 0, gap) },
                )
                addView(
                    rightColumn,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
            }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(solidBg)
            addView(
                middle,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(
                bottomHost,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bottomCollapsedPx),
            )
        }
    }

    /**
     * 符号 / 123 / ABC：把底部临时展开成两行字符面板，点字符直接上屏。
     * 「返回」固定在右侧不随横向滚动跑掉；在书写区落笔同样会自动收起（inkView.onWriteStart）。
     */
    private fun showCharStrip(
        chars: List<String>,
        title: String,
    ) {
        if (!::bottomHost.isInitialized) return
        val d = context.resources.displayMetrics.density
        val gap = (3 * d).toInt()
        val keyW = (42 * d).toInt()
        val rowH = (40 * d).toInt()
        val backW = (58 * d).toInt()
        val half = (chars.size + 1) / 2

        val grid =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                listOf(chars.take(half), chars.drop(half)).forEach { line ->
                    val row =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            line.forEach { ch ->
                                addView(
                                    keyButton(ch, { commitChar(ch) }, keyBgColor, keyTextColor, 15f),
                                    LinearLayout
                                        .LayoutParams(keyW, LinearLayout.LayoutParams.MATCH_PARENT)
                                        .apply { setMargins(gap, gap, gap, gap) },
                                )
                            }
                        }
                    addView(
                        row,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            rowH,
                        ),
                    )
                }
            }
        val scroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    grid,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                contentDescription = title
                addView(
                    scroll,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
                addView(
                    keyButton("返回", { showBottomMain() }, btnBgColor, btnTextColor, 13f),
                    LinearLayout
                        .LayoutParams(backW, LinearLayout.LayoutParams.MATCH_PARENT)
                        .apply { setMargins(gap, gap, gap, gap) },
                )
            }
        bottomHost.removeAllViews()
        bottomHost.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setBottomHeight(rowH * 2 + gap * 2)
        charPanelOpen = true
    }

    /** 回到主底行；字符面板是临时状态，收起后书写区恢复全高。 */
    private fun showBottomMain() {
        if (!::bottomHost.isInitialized || !::bottomMain.isInitialized) return
        bottomHost.removeAllViews()
        bottomHost.addView(
            bottomMain,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setBottomHeight(bottomCollapsedPx)
        charPanelOpen = false
    }

    /** 底部容器尚未挂到父布局时（buildView 阶段）layoutParams 为空，此时交给外层设定高度。 */
    private fun setBottomHeight(px: Int) {
        val lp = bottomHost.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.height == px) return
        lp.height = px
        bottomHost.layoutParams = lp
    }

    private fun commitChar(text: String) {
        // 符号/数字直接上屏会改变光标前内容：旧的补完前缀已失效，必须重置，
        // 否则随后点联想词会误删刚上屏的字符。
        lastCommitted = ""
        runCatching { service.commitText(text) }
            .onFailure { t -> Timber.w(t, "handwriting: commit char failed") }
    }

    private fun deleteChar() {
        // 删除键移除了光标前的字：补完前缀不再紧贴光标，重置以免点联想词时误删正文。
        lastCommitted = ""
        runCatching { service.currentInputConnection?.deleteSurroundingText(1, 0) }
            .onFailure { t -> Timber.w(t, "handwriting: delete char failed") }
    }

    private fun clearInk() {
        runCatching { inkView.clear() }
        lastCommitted = ""
        HandwritingCandidateBridge.publish(emptyArray())
        refreshMainBar()
        safeStatus("")
    }

    override fun onAttached() {
        Timber.i("handwriting: onAttached")
        runCatching {
            if (!::statusView.isInitialized) return
            if (!scope.isActive) scope = newScope()
            if (!HandwritingSettings.isEnabled(context)) {
                safeStatus("手写面板已在设置里关闭（设置 → 手写输入 可开启）")
                return
            }
            codeSourceId = HandwritingSettings.codeSource(context)
            // 从 ∨ 展开候选窗收起回来时，桥接被 suspendClear() 挂起，picker 仍然有效：
            // 此时保留原有候选，不重新清空，让用户能接着点或再次展开。
            val resumed = HandwritingCandidateBridge.active
            HandwritingCandidateBridge.resumeClear()
            HandwritingCandidateBridge.attach { text -> commitFromCandidate(text) }
            if (!resumed) {
                HandwritingCandidateBridge.publish(emptyArray())
            }
            safeStatus("")
            refreshMainBar()
            // 进入/回到手写面板时清掉上一轮笔迹，避免从展开候选窗收起后残留旧笔画。
            runCatching { inkView.clear() }
            showBottomMain()
            // 提示码字典与联想词索引都较大，后台预热进内存；主线程只做纯内存查表。
            scope.launch {
                HandwritingCodeLookup.warmUp(context, codeSourceId)
                HandwritingCodeLookup.warmUpPhrases(context)
                // 临时诊断：状态条显示码源解析结果，便于定位「设置好却没提示」。
                mainHandler.post {
                    safeStatus(HandwritingCodeLookup.diagnose(context, codeSourceId))
                }
            }
            // 绑定推理进程放到 prepareModel() 里，在存储路径 sync 完成之后执行：
            // bindInference() 会把 HandwritingModels.dir() 的当前值通过 Intent 传给 :hw_infer，
            // 若早于 sync()，子进程会拿到旧路径（如用户目录尚未写入）导致加载不到模型。
            prepareModel()
        }.onFailure { t ->
            Timber.e(t, "handwriting: onAttached failed")
            safeStatus("手写初始化失败：" + errorHint(t))
        }
    }

    override fun onDetached() {
        Timber.i("handwriting: onDetached")
        recognizeJob?.cancel()
        // 展开候选窗打开前会 suspendClear()；此时 clear() 是空操作，桥接仍 active。
        // 这种「临时让位」不能 cancel 协程作用域 / 解绑推理进程，否则收起回来手写直接失效。
        HandwritingCandidateBridge.clear()
        val suspended = HandwritingCandidateBridge.active
        if (!suspended) {
            // 真正离开手写面板：补完基准失效，重置 lastCommitted，避免残留前缀在后续键盘输入时误触发替换。
            // 展开候选窗让位（suspended）时候选栏与补完词仍保留，lastCommitted 必须一起保留，
            // 否则收起回来点联想词会因 prefix 丢失而直接追加，产生「叶叶子」叠词。
            lastCommitted = ""
            scope.cancel()
            unbindInference()
        }
        refreshMainBar()
    }

    /** 绑定 :hw_infer 推理子进程（幂等）。 */
    private fun bindInference() {
        if (bound || binding) return
        binding = true
        runCatching {
            val intent = Intent(context, HandwritingInferenceService::class.java)
            // 把当前解析出的模型目录绝对路径传给子进程，跨进程不用共享 prefs 缓存。
            intent.putExtra(
                HandwritingInferenceService.EXTRA_MODEL_DIR,
                HandwritingModels.dir(context).absolutePath,
            )
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onFailure {
            binding = false
            Timber.w(it, "handwriting: bind inference service failed")
        }
    }

    /** 解绑并释放子进程（会触发其 onUnbind → 释放 ORT）。 */
    private fun unbindInference() {
        if (bound) {
            runCatching { context.unbindService(conn) }
                .onFailure { Timber.w(it, "handwriting: unbind inference service failed") }
        }
        bound = false
        binding = false
        inferBinder = null
    }

    /** 打开面板只做纯文件检查 / 迁移，不碰 native，避免 ORT 初始化崩溃直接白屏。 */
    private fun prepareModel() {
        scope.launch {
            val state =
                runCatching {
                    // 先按当前存储模式/权限刷新模型落点（使用者资料夹 ↔ 私有目录），再迁移、再检查。
                    HandwritingStorageBridge.sync(context)
                    HandwritingModels.migrateLegacy(context)
                    HandwritingModels.isReady(context) to HandwritingModels.dir(context).absolutePath
                }.onFailure {
                    Timber.e(it, "handwriting: prepareModel failed")
                }.getOrDefault(false to "")
            withContext(Dispatchers.Main) {
                modelReady = state.first
                // sync() 已在上面把用户目录路径写入 prefs，现在绑定推理进程，
                // bindInference() 读到的 dir() 与子进程实际加载路径一致，避免首次进入加载失败。
                bindInference()
                // 正常状态不再往面板写提示文字；只在模型缺失时给出可操作的信息。
                if (!modelReady) {
                    safeStatus("未安装手写模型：设置 → 手写输入，点「手写模型」下载\n目录：${state.second}")
                }
            }
        }
    }

    /** 抬笔后防抖 320ms 再识别，推理通过 AIDL 交给 :hw_infer 子进程。 */
    private fun scheduleRecognize() {
        if (!modelReady) return
        recognizeJob?.cancel()
        recognizeJob =
            scope.launch {
                delay(320)
                val snapshot = withContext(Dispatchers.Main) { inkView.snapshot() }
                if (snapshot.isEmpty()) return@launch

                // 子进程可能还没连上（首次进入），或刚崩过被系统重建。给它一点时间连接。
                // 用 val 承接，避免可空 var 被协程 lambda 捕获后失去智能转换。
                val proxy: IHandwritingInference? =
                    if (inferBinder != null) {
                        inferBinder
                    } else {
                        withContext(Dispatchers.Main) { bindInference() }
                        var waited = 0
                        while (inferBinder == null && waited < 1500) {
                            delay(100)
                            waited += 100
                        }
                        inferBinder
                    }
                if (proxy == null) {
                    withContext(Dispatchers.Main) {
                        safeStatus("手写推理进程未就绪；继续写会自动重连，或点「返回键盘」后重进")
                    }
                    return@launch
                }

                val strokesJson = buildStrokesJson(snapshot)
                val response =
                    withContext(Dispatchers.IO) {
                        runCatching { proxy.recognize(strokesJson, 20) }
                            .onFailure { t -> Timber.w(t, "handwriting: IPC recognize failed") }
                            .getOrNull()
                    }
                withContext(Dispatchers.Main) {
                    if (response == null) {
                        inferBinder = null
                        bound = false
                        safeStatus("手写推理进程已崩溃（键盘未受影响）；继续写会自动重连，或点「返回键盘」后重进")
                    } else {
                        val (candidates, error) = parseCandidates(response)
                        if (error != null) {
                            safeStatus("手写识别失败：$error")
                        } else {
                            showCandidates(candidates)
                        }
                    }
                }
            }
    }

    /** 把笔迹快照序列化成 {"strokes":[[[x,y],...],...]}，供跨进程传输。 */
    private fun buildStrokesJson(strokes: List<InkStroke>): String {
        val arr = org.json.JSONArray()
        for (s in strokes) {
            val pts = org.json.JSONArray()
            for (p in s.points) {
                pts.put(org.json.JSONArray().put(p.first.toDouble()).put(p.second.toDouble()))
            }
            arr.put(pts)
        }
        return org.json.JSONObject().put("strokes", arr).toString()
    }

    /** 解析子进程返回的候选 JSON：返回 (候选列表, 错误信息)。 */
    private fun parseCandidates(json: String): Pair<List<InkCandidate>, String?> =
        try {
            val obj = org.json.JSONObject(json)
            if (!obj.optBoolean("ok", false)) {
                Pair<List<InkCandidate>, String?>(
                    emptyList(),
                    obj.optString("error", "未知错误").ifEmpty { "未知错误" },
                )
            } else {
                val arr = obj.optJSONArray("candidates")
                val list = ArrayList<InkCandidate>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val text = c.optString("text", "")
                        if (text.isEmpty()) continue
                        list += InkCandidate(text, c.optDouble("score", 0.0).toFloat())
                    }
                }
                Pair<List<InkCandidate>, String?>(list, null)
            }
        } catch (t: Throwable) {
            Pair<List<InkCandidate>, String?>(emptyList(), "候选解析失败：" + (t.message ?: ""))
        }

    /** 识别完成只发布候选到主候选栏，状态条保持空白（按需求去掉面板提示）。 */
    private fun showCandidates(list: List<InkCandidate>) {
        // 新一笔识别结果覆盖候选栏：上一段补完基准失效，重置 lastCommitted，
        // 避免把旧 prefix 误当成本次候选的前缀做替换。
        lastCommitted = ""
        publishToMainBar(list)
        safeStatus("")
    }

    /** 把识别结果发布到键盘上方的主候选栏：text 为候选字，comment 为所选方案的编码。 */
    private fun publishToMainBar(list: List<InkCandidate>) {
        val arr =
            list
                .map { c ->
                    CandidateProto(
                        c.text,
                        HandwritingCodeLookup.comment(context, c.text, codeSourceId),
                        "",
                    )
                }.toTypedArray()
        HandwritingCandidateBridge.publish(arr)
        refreshMainBar()
    }

    /** 触发一次候选广播：手写模式下两个 delegate 会读桥接候选，非手写模式交还 Rime。 */
    private fun refreshMainBar() {
        runCatching {
            val arr = HandwritingCandidateBridge.candidates
            broadcaster.onCandidateListUpdate(Candidates.Bulk(arr.size, 0, arr))
        }.onFailure { t -> Timber.w(t, "handwriting: refresh main candidate bar failed") }
    }

    /** 点候选（主候选栏或展开候选窗）：上屏、清笔迹，随后显示联想词。 */
    private fun commitFromCandidate(text: String) {
        // CyIME 式「补完」：上一次的已上屏文本是 prefix。
        // 若本次候选以它开头（如 lastCommitted="叶"、text="叶子"），说明是补完该词：
        // 需把编辑器里刚上的 prefix 替换成完整词（先删 prefix 再 commit 整词），
        // 绝不能整词追加——否则 prefix + 整词 会变成「叶叶子」。
        val prefix = lastCommitted
        val isCompletion = text.startsWith(prefix) && prefix.isNotEmpty()
        val result =
            runCatching {
                if (isCompletion) {
                    val ic = service.currentInputConnection
                    ic?.deleteSurroundingText(prefix.length, 0)
                    service.commitText(text)
                } else {
                    service.commitText(text)
                }
                lastCommitted = text
            }
        if (result.isFailure) {
            Timber.e(result.exceptionOrNull(), "handwriting: commit failed")
            safeStatus("上屏失败：" + errorHint(result.exceptionOrNull()) + "；候选仍在，可再点一次")
            return
        }
        runCatching { inkView.clear() }
        // 联想词以「本词末字」为首字继续给下一段联想；保留补完词（点上屏走上面的替换分支），
        // 只滤掉「==text」自重复项。
        val committedLast = text.lastOrNull()
        val rawWords = HandwritingCodeLookup.associations(committedLast)
        val words = rawWords.filter { it != text }
        Timber.tag("HwAssoc").w(
            "assoc commit=%s prefix=%s completion=%b anchor=%s raw=%d kept=%d head=%s",
            text, prefix, isCompletion, committedLast, rawWords.size, words.size, words.take(8),
        )
        val arr = words.map { CandidateProto(it, "", "") }.toTypedArray()
        HandwritingCandidateBridge.publish(arr)
        refreshMainBar()
        safeStatus("")
    }

    /** 把 native / 模型初始化的真实异常显示出来，不再只给一句「请检查目录」。 */
    private fun errorHint(t: Throwable?): String {
        if (t == null) return "原因未知"
        val message = t.message?.take(140).orEmpty().ifEmpty { "无详细信息" }
        return t.javaClass.simpleName + ": " + message
    }

    /** 安全读取主题色：主题里该键解析为图片或未定义时读取会抛异常，返回 null 让调用方兜底。 */
    private fun themeColor(block: () -> Int): Int? = runCatching { block() }.getOrNull()

    private fun luminance(color: Int): Double {
        val r = (color shr 16 and 0xFF) / 255.0
        val g = (color shr 8 and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastText(bg: Int): Int =
        if (luminance(bg) > 0.5) 0xFF111318.toInt() else 0xFFF7F7F9.toInt()

    private fun readable(text: Int, bg: Int): Int =
        if (abs(luminance(text) - luminance(bg)) > 0.32) text else contrastText(bg)

    companion object {
        private val SYMBOLS =
            listOf(
                "，", "。", "？", "！", "、", "；", "：", "“", "”", "‘", "’",
                "（", "）", "【", "】", "《", "》", "…", "—", "·", "～",
                "￥", "%", "&", "+", "-", "*", "/", "=", "@", "#", ".",
            )
        private val NUMBERS =
            ('0'..'9').map { it.toString() } +
                listOf(".", ",", "-", "+", "/", ":", "%", "(", ")", "=", "#")
        private val LETTERS = ('A'..'Z').map { it.toString() }
    }
}
