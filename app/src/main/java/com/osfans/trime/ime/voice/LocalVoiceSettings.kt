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

    /**
     * 模型存放位置：true = 优先放在使用者资料夹（需「所有文件访问权限」，native 才能 fopen 读取）；
     * false = 强制应用私有目录。默认开，满足条件时自动生效，未授权或未选资料夹时自动回退私有目录。
     */
    fun useUserDataDir(context: Context): Boolean = prefs(context).getBoolean("use_user_data_dir", true)

    fun setUseUserDataDir(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("use_user_data_dir", value).apply()

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
        val editor = prefs(context).edit().putString("loc.$family.variant", value)
        // 选中了下载安装的规格模型，就不再沿用手动选中的模型目录，避免两套设置互相打架。
        if (value.isNotBlank()) editor.putString("selected_model_dir", "")
        editor.apply()
        notifyConfigChanged()
    }

    /**
     * 手动放置 / ZIP 导入的模型：用户选中的模型**绝对目录**。
     *
     * 以前只存 `familyId + 目录名`，识别时再拼成 `voice/<family>/<目录名>`；
     * 用户直接把多个引擎的解压包丢进 voice 时，这些目录并不在 `<family>/` 下面，
     * 于是选中项解析失败、回退到自动扫描的第一套模型，表现为「只能识别一个引擎」。
     * 这里直接记住真实路径，VoiceModels.status() 优先按它解析。
     * 空串 = 由下载安装的规格模型 / 自动扫描决定。
     */
    fun selectedModelDir(context: Context): String =
        prefs(context).getString("selected_model_dir", "").orEmpty()

    fun setSelectedModelDir(context: Context, value: String) {
        prefs(context).edit().putString("selected_model_dir", value).apply()
        // 换模型必须让已常驻的 native 识别器失效，否则仍用旧引擎推理。
        notifyConfigChanged()
    }

    /**
     * 键盘「简/繁」键实际切换的 Rime option 名，以及该 option 为 true 时是否输出繁体。
     * 主题与方案的命名经常不一致（主题写 simplification，方案却用 zh_simp / zh_hant），
     * 只有记录键盘按键本身，语音的「跟随输入法简/繁开关」才能真正跟随用户看到的开关。
     */
    fun charsetOption(context: Context): String =
        prefs(context).getString("charset_option", "").orEmpty()

    fun setCharsetOption(context: Context, value: String) =
        prefs(context).edit().putString("charset_option", value).apply()

    fun charsetTraditionalWhenOn(context: Context): Boolean =
        prefs(context).getBoolean("charset_traditional_when_on", false)

    fun setCharsetTraditionalWhenOn(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("charset_traditional_when_on", value).apply()

    /**
     * 省电模式（默认开）：流式推理固定 1 线程、静音 10 秒自动结束录音、
     * 不再为「保存录音」之外的用途缓存整段 PCM。关掉后保持原来的满速行为。
     */
    fun isPowerSave(context: Context): Boolean = prefs(context).getBoolean("power_save", true)

    fun setPowerSave(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("power_save", value).apply()
        // 线程数变化会让识别器缓存指纹失效，必须重建，否则省电开关看起来没生效。
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
        // 先掐掉上一档遗留的定时卸载，否则它到点会把刚按新配置加载的模型误卸掉。
        runCatching { SherpaOfflineManager.getInstance().cancelScheduledUnload() }
        runCatching { SherpaOnlineManager.cancelScheduledUnload() }
        runCatching { SherpaOfflineManager.getInstance().unload() }
        runCatching { SherpaOnlineManager.unload() }
    }

    /**
     * -1 常驻内存；-2 跟随键盘（打开时预加载 / 关闭后延迟卸载）；0 即用即卸；>0 空闲 N 分钟后卸载。
     * 默认跟随键盘：既不用每次重新加载大模型，也不会在键盘收起后一直占着内存。
     * >0 的值可以来自设置页的「自定义空闲时间」，不再限于几个预设档。
     */
    fun keepAliveMinutes(context: Context, family: String): Int =
        prefs(context).getInt("loc.$family.keep_alive", MODE_FOLLOW_KEYBOARD)

    /** 自定义空闲卸载允许的最大分钟数（24 小时），防止手滑输入超大值导致永不卸载。 */
    const val MAX_CUSTOM_KEEP_ALIVE_MINUTES = 1440

    fun setKeepAliveMinutes(context: Context, family: String, value: Int) {
        val normalized = if (value > 0) value.coerceIn(1, MAX_CUSTOM_KEEP_ALIVE_MINUTES) else value
        prefs(context).edit().putInt("loc.$family.keep_alive", normalized).apply()
        // 切换后立即释放已常驻的模型；unload() 会等正在进行的识别结束，不会打断本次会话。
        // 同时取消上一档的定时卸载，避免旧任务到点误卸新加载的模型。
        runCatching { SherpaOfflineManager.getInstance().cancelScheduledUnload() }
        runCatching { SherpaOnlineManager.cancelScheduledUnload() }
        runCatching { SherpaOfflineManager.getInstance().unload() }
        runCatching { SherpaOnlineManager.unload() }
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
