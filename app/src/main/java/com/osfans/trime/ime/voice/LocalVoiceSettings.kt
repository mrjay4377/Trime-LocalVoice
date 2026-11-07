/*
 * 本地语音设置：独立 SharedPreferences，不触碰主题与 Rime 数据。
 * 配置结构参考 trime-mie 的 AsrSettings，仅保留离线部分。
 */
package com.osfans.trime.ime.voice

import android.content.Context

object LocalVoiceSettings {
    private const val PREFS_NAME = "local_voice"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 关闭后语音键回退为系统语音输入法。 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("enabled", value).apply()

    fun vendor(context: Context): String {
        val default = VoiceModels.FAMILIES.first().id
        val saved = prefs(context).getString("vendor", default) ?: default
        return if (VoiceModels.family(saved) == null) default else saved
    }

    fun setVendor(context: Context, value: String) {
        prefs(context).edit().putString("vendor", value).apply()
        notifyConfigChanged()
    }

    /** 选中的可下载模型变体；空串 = 自动扫描 voice 目录（兼容手动放置的模型）。 */
    fun selectedVariant(context: Context, family: String): String =
        prefs(context).getString("loc.$family.variant", "").orEmpty()

    fun setSelectedVariant(context: Context, family: String, value: String) {
        prefs(context).edit().putString("loc.$family.variant", value).apply()
        notifyConfigChanged()
    }

    /** 模型下载源：0 = 自动（官方 → 镜像），1 = 官方，2..4 = 镜像。 */
    fun downloadSource(context: Context): Int =
        prefs(context).getInt("download_source", 0).coerceIn(0, ModelCatalog.SOURCES.lastIndex)

    fun setDownloadSource(context: Context, value: Int) =
        prefs(context).edit().putInt("download_source", value.coerceIn(0, ModelCatalog.SOURCES.lastIndex)).apply()

    fun threads(context: Context, family: String): Int =
        prefs(context).getInt("loc.$family.threads", 2).coerceIn(1, 8)

    fun setThreads(context: Context, family: String, value: Int) {
        prefs(context).edit().putInt("loc.$family.threads", value.coerceIn(1, 8)).apply()
        notifyConfigChanged()
    }

    fun language(context: Context, family: String): String =
        prefs(context).getString("loc.$family.language", "").orEmpty()

    fun setLanguage(context: Context, family: String, value: String) {
        prefs(context).edit().putString("loc.$family.language", value).apply()
        notifyConfigChanged()
    }

    /** 逆文本归一化（ITN）：把「一二三」转成「123」等；仅对支持 ITN 的模型家族生效。 */
    fun isItn(context: Context, family: String): Boolean =
        prefs(context).getBoolean("loc.$family.itn", true)

    fun setItn(context: Context, family: String, value: Boolean) {
        prefs(context).edit().putBoolean("loc.$family.itn", value).apply()
        notifyConfigChanged()
    }

    /** 配置变更后卸载已缓存的识别器，让新模型 / 线程数 / ITN 立即生效。 */
    private fun notifyConfigChanged() {
        runCatching { SherpaOfflineManager.getInstance().unload() }
    }

    /**
     * -1 常驻内存；-2 跟随键盘（打开时预加载 / 关闭后延迟卸载）；0 即用即卸；>0 N 分钟后卸载。
     * 默认跟随键盘：既不用每次重新加载大模型，也不会在键盘收起后一直占着内存。
     */
    fun keepAliveMinutes(context: Context, family: String): Int =
        prefs(context).getInt("loc.$family.keep_alive", MODE_FOLLOW_KEYBOARD)

    fun setKeepAliveMinutes(context: Context, family: String, value: Int) {
        prefs(context).edit().putInt("loc.$family.keep_alive", value).apply()
        // 切换后立即释放已常驻的模型；unload() 会等正在进行的识别结束，不会打断本次会话。
        runCatching { SherpaOfflineManager.getInstance().unload() }
    }

    /** 把最近一次录音以 wav 保存到 voice 目录（可选，便于查看语音文件）。 */
    fun isSaveRecording(context: Context): Boolean =
        prefs(context).getBoolean("save_recording", false)

    fun setSaveRecording(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("save_recording", value).apply()

    /**
     * 语音结果上屏前的字形转换（全局，不随引擎家族变化）：
     * off / s2t / s2tw / s2hk / t2s / auto（跟随 Rime 简繁开关）。默认不转换。
     * 与识别引擎无关，切换时不需要卸载已常驻的模型。
     */
    fun outputConversion(context: Context): String {
        val saved = prefs(context).getString("output_conversion", "off") ?: "off"
        return if (saved in OUTPUT_CONVERSIONS) saved else "off"
    }

    fun setOutputConversion(context: Context, value: String) =
        prefs(context).edit()
            .putString("output_conversion", if (value in OUTPUT_CONVERSIONS) value else "off")
            .apply()

    private val OUTPUT_CONVERSIONS = setOf("off", "s2t", "s2tw", "s2hk", "t2s", "auto")

    /** 模型驻留策略中的「跟随键盘」档：-2 = 打开键盘预加载、关闭后延迟卸载。 */
    const val MODE_FOLLOW_KEYBOARD = -2

    /** 键盘关闭后延迟卸载的分钟数，默认 3 分钟。 */
    fun unloadDelayMinutes(context: Context): Int =
        prefs(context).getInt("unload_delay", 3).coerceIn(1, 60)

    fun setUnloadDelayMinutes(context: Context, value: Int) =
        prefs(context).edit().putInt("unload_delay", value.coerceIn(1, 60)).apply()

    /** 语音输入后离线翻译（Foxlet / Mozilla 模型），默认关闭。 */
    fun isTranslateEnabled(context: Context): Boolean =
        prefs(context).getBoolean("translate_enabled", false)

    fun setTranslateEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("translate_enabled", value).apply()

    fun translateDirection(context: Context): String {
        val saved = prefs(context).getString("translate_direction", "auto") ?: "auto"
        return if (saved in TRANSLATE_DIRECTIONS) saved else "auto"
    }

    fun setTranslateDirection(context: Context, value: String) =
        prefs(context).edit()
            .putString("translate_direction", if (value in TRANSLATE_DIRECTIONS) value else "auto")
            .apply()

    private val TRANSLATE_DIRECTIONS = setOf(
        "auto", "zh_en", "en_zh", "zh_ja", "zh_ko", "zh_fr", "zh_de", "zh_es", "zh_ru",
    )
}
