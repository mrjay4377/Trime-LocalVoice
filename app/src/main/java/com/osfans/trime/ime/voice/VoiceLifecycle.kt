/*
 * 模型驻留策略「跟随键盘」：键盘弹出时后台预加载识别器，键盘隐藏后延迟几分钟卸载。
 * 对齐说点啥 v4.6.0 里「空闲自动卸载 / onTrimMemory 主动释放」的内存生命周期做法，
 * 但把它绑定到输入法视图的显示状态，比固定定时器更贴合实际使用节奏。
 */
package com.osfans.trime.ime.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

object VoiceLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var preloadJob: Job? = null

    private fun followKeyboard(context: Context): Boolean =
        LocalVoiceSettings.keepAliveMinutes(context, LocalVoiceSettings.vendor(context)) ==
            LocalVoiceSettings.MODE_FOLLOW_KEYBOARD

    /** 键盘弹出：取消待卸载任务，后台把当前模型先加载好，首次按语音键不用等。 */
    fun onKeyboardShown(context: Context) {
        val ctx = context.applicationContext
        if (!followKeyboard(ctx) || !LocalVoiceSettings.isEnabled(ctx)) return
        val manager = SherpaOfflineManager.getInstance()
        manager.cancelScheduledUnload()
        SherpaOnlineManager.cancelScheduledUnload()
        // onStartInput 已提前触发过一次时不要重启加载（600MB 模型重启一次代价很高）。
        if (preloadJob?.isActive == true) return
        preloadJob = scope.launch {
            // 统一词库文件只有几 KB，先读到内存；模型加载与 Rime 词库导出排在后面。
            runCatching { VoiceHotwordBook.reload(ctx) }
            preloadInternal(ctx)
            refreshHotwords(ctx)
        }
    }

    /**
     * 设置页 / 安装完成 / 切换配置后的预热：这些入口会卸载旧识别器，若只等
     * onStartInputView，键盘本来就弹着就不会再触发，第一次按语音键仍是冷加载。
     * 与「跟随键盘」策略无关，只要模型已就绪就后台加载，已加载则直接返回。
     */
    fun warmUp(context: Context) {
        val ctx = context.applicationContext
        if (!LocalVoiceSettings.isEnabled(ctx)) return
        if (preloadJob?.isActive == true) return
        preloadJob = scope.launch {
            // 统一词库文件只有几 KB，先读到内存；模型加载与 Rime 词库导出排在后面。
            runCatching { VoiceHotwordBook.reload(ctx) }
            preloadInternal(ctx)
            refreshHotwords(ctx)
        }
    }

    /**
     * 按住语音键瞬间触发：与录音并行预热离线识别器，松手解码时通常已命中缓存。
     * 全档位生效（其它驻留档位的 onKeyboardShown 提前 return，从来没有预加载），
     * 但不改变任何卸载策略——不安排空闲卸载，卸载仍由识别结束 / 键盘隐藏决定。
     */
    fun ensureLoaded(context: Context, family: String, model: ResolvedVoiceModel) {
        val ctx = context.applicationContext
        // 流式家族保持「按住时按需加载」：键盘弹出或按键瞬间吃 590MB 容易把低端机压死。
        // 但仍要在后台把统一词库文件读进内存，否则「流式 + 非跟随键盘」档永远读不到热词。
        if (VoiceModels.isStreaming(family)) {
            scope.launch {
                runCatching { VoiceHotwordBook.reload(ctx) }
                // 流式档以前在这里提前 return，跳过了词库刷新，
                // 导致 Rime 用户词库热词与识别纠错规则永远是空的。
                refreshHotwords(ctx)
            }
            return
        }
        if (preloadJob?.isActive == true) return
        preloadJob = scope.launch {
            // 几 KB 的词库文件先读内存；Rime 词库导出较重，仍排在模型加载之后。
            runCatching { VoiceHotwordBook.reload(ctx) }
            // 预热优先：词库导出是磁盘 IO，放在加载之后，不拖慢本次「按住即预加载」。
            runCatching { loadOffline(ctx, family, model) }
                .onFailure { Timber.w(it, "voice model ensureLoaded failed") }
            // 其它驻留档位不会走 onKeyboardShown 的预热，词库快照在这里补刷（内部有 30 分钟节流）。
            refreshHotwords(ctx)
        }
    }

    /** 预加载与按住预热的唯一实现，避免两份 config / 指纹逻辑走偏。 */
    private suspend fun loadOffline(ctx: Context, family: String, model: ResolvedVoiceModel) {
        val manager = SherpaOfflineManager.getInstance()
        val (config, key) = OfflineSherpaEngine.buildConfig(ctx, family, model)
        // preload() 内部还有一次 isLoaded 短路；这里提前判一次，连 config 反射构造都省掉。
        if (manager.isLoaded(key)) return
        manager.preload(config, key)
    }

    private fun refreshHotwords(ctx: Context) {
        // 纠错词库在 IO 协程里读盘；上屏路径只读内存快照。
        runCatching { VoiceCorrections.reload(ctx) }
            .onFailure { Timber.w(it, "voice corrections reload failed") }
        // 「Rime 用户词库」功能已移除：不再每 30 分钟导出一次用户词典快照，
        // 也省掉了一次 native 调用与磁盘 IO。
    }

    private suspend fun preloadInternal(ctx: Context) {
        runCatching {
            val family = LocalVoiceSettings.vendor(ctx)
            // 流式引擎不在键盘弹出时单独预加载：统一为「按住语音键时按需加载」，
            // 加载期间 PCM 排队，模型就绪后回放再逐块出字，开头语音不会丢。
            if (VoiceModels.isStreaming(family)) return@runCatching
            val model = VoiceModels.status(ctx, family).resolved ?: return@runCatching
            loadOffline(ctx, family, model)
            // 预加载也算一次「使用」：从这里开始计空闲时间，避免只预热不识别时
            // 自定义（或 5/15/30 预设）档的模型一直留在内存里。识别结束会重新计时。
            val minutes = LocalVoiceSettings.keepAliveMinutes(ctx, family)
            if (minutes > 0) SherpaOfflineManager.getInstance().scheduleUnload(minutes.toLong() * 60_000L)
        }.onFailure { Timber.w(it, "voice model preload failed") }
    }

    /** 键盘隐藏：延迟卸载；期间再次唤起键盘会取消这次卸载。 */
    fun onKeyboardHidden(context: Context) {
        val ctx = context.applicationContext
        if (!followKeyboard(ctx)) return
        preloadJob?.cancel()
        preloadJob = null
        val delayMs = LocalVoiceSettings.unloadDelayMinutes(ctx).toLong() * 60_000L
        SherpaOfflineManager.getInstance().scheduleUnload(delayMs)
        SherpaOnlineManager.scheduleUnload(delayMs)
    }

    /** 输入法服务销毁：立即释放 ASR 与翻译模型，不留常驻内存。 */
    fun onDestroy() {
        preloadJob?.cancel()
        preloadJob = null
        SherpaOfflineManager.getInstance().unload()
        SherpaOnlineManager.unload()
        scope.launch { OfflineTranslator.shutdown() }
    }
}
