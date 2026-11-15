/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled.window

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.CandidatesPagingSource
import com.osfans.trime.ime.candidates.unrolled.PagingCandidateViewAdapter
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateLayout
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.handwriting.HandwritingCandidateBridge
import com.osfans.trime.ime.handwriting.HandwritingWindow
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import kotlin.math.max

abstract class BaseUnrolledCandidateWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    InputBroadcastReceiver {
    protected val service: TrimeInputMethodService by instance()
    protected val rime: RimeSession by instance()
    protected val scope: ThemeScope by instance()
    private val inputView: InputView by instance()
    private val bar: InputBarDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val compactCandidate: CompactCandidateDelegate by instance()

    protected val theme: Theme
        get() = scope.theme

    /**
     * 手写模式专用：打开展开候选窗那一刻从手写桥接拷来的候选快照。
     * 因为 attachWindow 会先 detach 手写面板（清空桥接），展开窗不能等 onAttached 再去读桥接，
     * 必须由打开方在 attach 之前把快照传进来。
     */
    var handwritingCandidates: Array<com.osfans.trime.core.CandidateProto>? = null

    /** 打开展开窗前的基底窗口：手写时是手写面板，否则是键盘。收起展开窗时回到它。 */
    var baseWindow: BoardWindow? = null

    private lateinit var lifecycleCoroutineScope: LifecycleCoroutineScope
    private lateinit var candidateLayout: UnrolledCandidateLayout

    protected val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.style.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = scope.colors.candidateSeparatorColor
        }
    }

    override fun refreshColors() {
        if (!::candidateLayout.isInitialized) return
        // the decorations share this drawable, so re-coloring its paint repaints the dividers
        separatorDrawable.paint.color = scope.colors.candidateSeparatorColor
        candidateLayout.refreshColors()
        // visible rows re-apply their colors on rebind
        adapter.notifyDataSetChanged()
    }

    abstract fun onCreateCandidateLayout(): UnrolledCandidateLayout

    final override fun onCreateView(): View {
        candidateLayout =
            onCreateCandidateLayout().apply {
                recyclerView.apply {
                    // disable item cross-fade animation
                    itemAnimator = null
                }
            }
        return candidateLayout
    }

    abstract val adapter: PagingCandidateViewAdapter
    abstract val layoutManager: RecyclerView.LayoutManager

    private var offsetJob: Job? = null

    private val candidatesPager by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 48,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                CandidatesPagingSource(
                    rime,
                    total = compactCandidate.adapter.total,
                    offset = adapter.offset,
                )
            },
        )
    }

    private var candidatesSubmitJob: Job? = null

    override fun onAttached() {
        lifecycleCoroutineScope = candidateLayout.findViewTreeLifecycleOwner()!!.lifecycleScope
        bar.unrollButtonStateMachine.push(UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesAttached)
        val snap = handwritingCandidates
        if (snap != null) {
            // 手写候选是一次性小列表（≤ topK），直接整批喂给分页适配器。
            lifecycleCoroutineScope.launch {
                adapter.submitData(PagingData.from(snap.toList()))
            }
            return
        }
        offsetJob =
            lifecycleCoroutineScope.launch {
                compactCandidate.unrolledCandidateOffset.collect {
                    if (it <= 0) {
                        windowManager.attachWindow(KeyboardWindow)
                    } else {
                        candidateLayout.resetPosition()
                        adapter.refreshWith(
                            offset = it,
                            highlightedIndex = compactCandidate.adapter.highlightedIdx,
                        )
                    }
                }
            }
        candidatesSubmitJob =
            lifecycleCoroutineScope.launch {
                candidatesPager.flow.collectLatest {
                    adapter.submitData(it)
                }
            }
    }

    fun bindCandidateUiViewHolder(holder: CandidateViewHolder) {
        holder.itemView.run {
            setOnClickListener { _ ->
                val snap = handwritingCandidates
                if (snap != null) {
                    // 手写展开窗是「打开它时」从手写桥接拷来的快照；此时手写面板已 detach、picker 已清空，
                    // 直接用 service 上屏，再把展开窗收起回手写面板（baseWindow 即打开展开窗前的手写窗口）。
                    val text = snap.getOrNull(holder.idx)?.text
                    if (text != null) {
                        // 走手写面板的 commitFromCandidate：统一处理上屏、清空笔迹与联想词。
                        // 展开窗期间桥接被 suspendClear() 挂起，picker 仍然有效。
                        if (HandwritingCandidateBridge.active) {
                            HandwritingCandidateBridge.pick(text)
                        } else {
                            service.commitText(text)
                        }
                        windowManager.attachWindow(baseWindow ?: HandwritingWindow(di))
                    }
                } else {
                    rime.launchOnReady { it.selectCandidate(holder.idx, global = true) }
                }
            }
            setOnLongClickListener { view ->
                if (handwritingCandidates != null) {
                    false
                } else {
                    inputView.showCandidateActionMenu(holder.idx, holder.text, view, global = true)
                    true
                }
            }
        }
    }

    override fun onDetached() {
        // 展开窗关闭：解除手写桥接的清理挂起。随后 attachWindow 会执行基底窗口的 onAttached，
        // 它若发现桥接仍 active，就把这次当作「从展开窗返回」，保留原有候选。
        HandwritingCandidateBridge.resumeClear()
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesDetached,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (compactCandidate.adapter.total == adapter.offset),
        )
        offsetJob?.cancel()
        candidatesSubmitJob?.cancel()
    }
}
