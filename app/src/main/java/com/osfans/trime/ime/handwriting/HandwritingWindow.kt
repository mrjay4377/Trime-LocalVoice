/*
 * 手写输入面板：挂在 BoardWindowManager 的键盘窗口容器里，点候选直接上屏。
 * 纯推理层已移到独立 Gradle 模块 :handwriting 与 :hw_infer 子进程，这里只做 UI 胶水与生命周期。
 *
 * 白屏修复：主题颜色解析在配色链缺失时会抛异常，协程未捕获异常会杀掉 IME 进程；
 * 这里统一改成「视图永远可见 + 异常可见化 + native 延后到首次落笔」。
 *
 * 布局按参考图：中间大书写区 + 右侧竖列（，。？！ 删除）+ 底部一行
 * （符号 / 123 / 空格 / ABC / 换行）；顶部细条保留「撤销一笔 / 清空 / 返回键盘」与错误提示。
 * 正常状态下状态条为空，不再显示「候选已显示在上方候选栏（点候选上屏）」这类提示。
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
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
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

    private lateinit var inkView: HandwritingInkView
    private lateinit var statusView: TextView
    private lateinit var bottomHost: FrameLayout
    private lateinit var bottomMain: LinearLayout

    private var btnBgColor = 0
    private var btnTextColor = 0
    private var keyBgColor = 0
    private var keyTextColor = 0

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

    /** 统一的方块按键：白底（普通键）或灰底（功能键），不用系统默认 Button 样式。 */
    private fun keyButton(
        label: String,
        action: () -> Unit,
        bg: Int,
        fg: Int,
        size: Float = 14f,
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
            setOnClickListener { action() }
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
            runCatching { themeScope.colors.rootBackground }
                .getOrNull()
                ?.let { 0xFF000000.toInt() or (it and 0x00FFFFFF) }
                ?: if (night) 0xFF202124.toInt() else 0xFFEFEFF2.toInt()
        val safeText =
            runCatching { themeScope.colors.candidateTextColor }
                .getOrNull()
                ?.let { readable(it, solidBg) }
                ?: contrastText(solidBg)
        btnBgColor = if (luminance(solidBg) > 0.5) 0xFFD5D9E2.toInt() else 0xFF3B404B.toInt()
        btnTextColor = contrastText(btnBgColor)
        keyBgColor = if (luminance(solidBg) > 0.5) 0xFFFDFDFD.toInt() else 0xFF2C3038.toInt()
        keyTextColor = contrastText(keyBgColor)

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
                        setColor(0xFFFAFAFA.toInt())
                        cornerRadius = 6 * d
                        setStroke((1 * d).toInt(), 0xFF9AA0A6.toInt())
                    }
                onStrokeFinished = { scheduleRecognize() }
            }

        val topBar =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(solidBg)
                addView(
                    statusView,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                listOf<Pair<String, () -> Unit>>(
                    "撤销一笔" to { inkView.undo() },
                    "清空" to { clearInk() },
                    "返回键盘" to { windowManager.attachWindow(KeyboardWindow) },
                ).forEach { (label, action) ->
                    addView(
                        keyButton(label, action, btnBgColor, btnTextColor, 11f),
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                (26 * d).toInt(),
                            ).apply { setMargins(gap, gap, gap, gap) },
                    )
                }
            }

        // 右侧竖列：，。？！ 四个标点 + 删除，按参考图等分高度。
        val rightColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(solidBg)
                listOf("，", "。", "？", "！", "删除").forEachIndexed { i, ch ->
                    val weight = if (i == 4) 1.5f else 1f
                    val bg = if (i == 4) btnBgColor else keyBgColor
                    val fg = if (i == 4) btnTextColor else keyTextColor
                    val action: () -> Unit =
                        if (i == 4) {
                            { deleteChar() }
                        } else {
                            { commitChar(ch) }
                        }
                    addView(
                        keyButton(ch, action, bg, fg, if (i == 4) 13f else 16f),
                        LinearLayout
                            .LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight)
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
        addBottomKey(bottomMain, "符号", { showCharStrip(SYMBOLS) }, 1f, btnBgColor, btnTextColor)
        addBottomKey(bottomMain, "123", { showCharStrip(NUMBERS) }, 1f, keyBgColor, keyTextColor)
        addBottomKey(bottomMain, "空格", { commitChar(" ") }, 1.5f, keyBgColor, keyTextColor)
        addBottomKey(bottomMain, "ABC", { showCharStrip(LETTERS) }, 1f, keyBgColor, keyTextColor)
        addBottomKey(bottomMain, "换行", { service.handleReturnKey() }, 1f, btnBgColor, btnTextColor)

        bottomHost = FrameLayout(context).apply { setBackgroundColor(solidBg) }
        showBottomMain()

        val middle =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(solidBg)
                addView(
                    inkView,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        .apply { setMargins(gap, 0, 0, gap) },
                )
                addView(
                    rightColumn,
                    LinearLayout.LayoutParams((54 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT),
                )
            }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(solidBg)
            addView(
                topBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                middle,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(
                bottomHost,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt()),
            )
        }
    }

    /** 符号 / 123 / ABC 换成可横向滚动的字符条，点字符直接上屏，点「返回」回到主行。 */
    private fun showCharStrip(chars: List<String>) {
        if (!::bottomHost.isInitialized) return
        val d = context.resources.displayMetrics.density
        val gap = (3 * d).toInt()
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                chars.forEach { ch ->
                    addView(
                        keyButton(ch, { commitChar(ch) }, keyBgColor, keyTextColor),
                        LinearLayout
                            .LayoutParams((40 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                            .apply { setMargins(gap, gap, gap, gap) },
                    )
                }
                addView(
                    keyButton("返回", { showBottomMain() }, btnBgColor, btnTextColor, 13f),
                    LinearLayout
                        .LayoutParams((56 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                        .apply { setMargins(gap, gap, gap, gap) },
                )
            }
        val scroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
        bottomHost.removeAllViews()
        bottomHost.addView(
            scroll,
            FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun showBottomMain() {
        if (!::bottomHost.isInitialized || !::bottomMain.isInitialized) return
        bottomHost.removeAllViews()
        bottomHost.addView(
            bottomMain,
            FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun commitChar(text: String) {
        runCatching { service.commitText(text) }
            .onFailure { t -> Timber.w(t, "handwriting: commit char failed") }
    }

    private fun deleteChar() {
        runCatching { service.currentInputConnection?.deleteSurroundingText(1, 0) }
            .onFailure { t -> Timber.w(t, "handwriting: delete char failed") }
    }

    private fun clearInk() {
        runCatching { inkView.clear() }
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
            }
            prepareModel()
            bindInference()
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
                    HandwritingModels.migrateLegacy(context)
                    HandwritingModels.isReady(context) to HandwritingModels.dir(context).absolutePath
                }.onFailure {
                    Timber.e(it, "handwriting: prepareModel failed")
                }.getOrDefault(false to "")
            withContext(Dispatchers.Main) {
                modelReady = state.first
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
        val result = runCatching { service.commitText(text) }
        if (result.isFailure) {
            Timber.e(result.exceptionOrNull(), "handwriting: commit failed")
            safeStatus("上屏失败：" + errorHint(result.exceptionOrNull()) + "；候选仍在，可再点一次")
            return
        }
        runCatching { inkView.clear() }
        // 联想词来自已部署字典的词组行，以上屏的最后一个字为首字；继续写下一笔会被识别结果覆盖。
        val words = HandwritingCodeLookup.associations(text.lastOrNull())
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
