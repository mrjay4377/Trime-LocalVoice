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
        preloadJob?.cancel()
        val manager = SherpaOfflineManager.getInstance()
        manager.cancelScheduledUnload()
        SherpaOnlineManager.cancelScheduledUnload()
        preloadJob = scope.launch {
            runCatching {
                val family = LocalVoiceSettings.vendor(ctx)
                val model = VoiceModels.status(ctx, family).resolved ?: return@launch
                if (VoiceModels.isStreaming(family)) {
                    val (config, key) = StreamingSherpaEngine.buildConfig(ctx, model)
                    SherpaOnlineManager.preload(config, key)
                } else {
                    val (config, key) = OfflineSherpaEngine.buildConfig(ctx, family, model)
                    manager.preload(config, key)
                }
            }.onFailure { Timber.w(it, "voice model preload failed") }
        }
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
