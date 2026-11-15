/*
 * 手写候选 → 键盘上方主候选栏的桥接。
 *
 * 手写面板原本把识别结果画在自己面板内部的一条横向滚动条里；现在改为发布到这里，
 * 由 CompactCandidateDelegate / InputBarDelegate 在手写期间覆盖 Rime 的候选列表，
 * 从而复用主候选栏的主题样式、text + comment（提示码）排版与点击交互。
 *
 * 这里只存纯数据 + 一个上屏回调，不持有 Context / View / Service，避免跨窗口泄漏；
 * 手写窗口正常离开时 clear()。
 *
 * ∨ 展开候选窗例外：attachWindow 会先 detach 手写面板，若此时 clear()，主候选栏
 * 立刻变空、QuickBar 切回工具栏页，右上角的收起按钮就看不见了，也无法「再点一次返回」。
 * 打开前用 suspendClear() 挂起清理，展开窗 onDetached 时再 resumeClear()，
 * 这样候选与上屏回调在整段展开期间都保留，收起回到手写面板后可直接继续点选。
 */
package com.osfans.trime.ime.handwriting

import com.osfans.trime.core.CandidateProto

object HandwritingCandidateBridge {
    @Volatile
    private var items: Array<CandidateProto> = emptyArray()

    @Volatile
    private var picker: ((String) -> Unit)? = null

    /** 展开候选窗期间挂起 clear()：候选与 picker 保持可用。 */
    @Volatile
    private var suspended = false

    /** 手写面板是否已挂载：为 true 时主候选栏一律显示这里发布的候选，屏蔽 Rime 候选。 */
    val active: Boolean get() = picker != null

    val candidates: Array<CandidateProto> get() = items

    /** 挂载手写面板时注册上屏回调（走 commitFromCandidate，而不是 Rime 选字）。 */
    fun attach(onPick: (String) -> Unit) {
        picker = onPick
    }

    /** 识别完成 / 联想词刷新后发布候选；调用方随后需触发一次候选广播刷新主候选栏。 */
    fun publish(list: Array<CandidateProto>) {
        items = list
    }

    /** 用户在主候选栏或展开候选窗点了某个手写候选。 */
    fun pick(text: String) {
        picker?.invoke(text)
    }

    /** 打开展开候选窗前调用；只影响随后那次手写面板 onDetached 的 clear()。 */
    fun suspendClear() {
        suspended = true
    }

    /** 展开候选窗关闭时调用（无论回到手写面板还是键盘）。 */
    fun resumeClear() {
        suspended = false
    }

    /** 离开手写面板：恢复 Rime 候选，并释放窗口引用。 */
    fun clear() {
        if (suspended) return
        items = emptyArray()
        picker = null
    }

    /** 展开窗被别的窗口顶掉等异常路径：无条件释放，避免手写候选残留在普通键盘上。 */
    fun forceClear() {
        suspended = false
        items = emptyArray()
        picker = null
    }
}
