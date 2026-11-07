/*
 * 本地语音识别设置：只保留本地语音。
 * 模型可一键下载自动安装、手动下载后用本地 ZIP 导入，也兼容直接放到 voice 目录。
 * 识别全程在设备本地完成。
 *
 * 注意：AndroidX Preference 弹出列表对话框时按 key 查找目标 Preference，
 * 所有 Preference 必须设置唯一 key，否则会拿到错误的 Preference 强转崩溃。
 */
package com.osfans.trime.ui.main.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.ime.voice.AudioRecorder
import com.osfans.trime.ime.voice.InstalledModel
import com.osfans.trime.ime.voice.LocalVoicePackage
import com.osfans.trime.ime.voice.LocalVoiceSettings
import com.osfans.trime.ime.voice.ModelCatalog
import com.osfans.trime.ime.voice.ModelInstaller
import com.osfans.trime.ime.voice.ModelVariant
import com.osfans.trime.ime.voice.OfflineTranslator
import com.osfans.trime.ime.voice.VoiceLifecycle
import com.osfans.trime.ime.voice.VoiceModels
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import io.github.yinvoker.foxlet.LanguagePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LocalAsrSettingsFragment : PaddingPreferenceFragment() {

    /** 模型选择弹窗的一行：分组标题不可点，选项带主/副两行文字。 */
    private sealed interface EngineRow {
        data class Header(val title: String) : EngineRow

        data class Option(val value: String, val title: String, val subtitle: String) : EngineRow
    }

    private val main = Handler(Looper.getMainLooper())
    private var modelStatusPreference: Preference? = null
    private var permissionPreference: Preference? = null
    private var vendorPreference: Preference? = null
    private var threadsPreference: ListPreference? = null
    private var languagePreference: ListPreference? = null
    private var keepAlivePreference: ListPreference? = null
    private var powerSavePreference: SwitchPreferenceCompat? = null
    private var sourcePreference: Preference? = null
    private var itnPreference: SwitchPreferenceCompat? = null
    private var conversionPreference: ListPreference? = null
    private var translatePreference: SwitchPreferenceCompat? = null
    private var translateDirectionPreference: ListPreference? = null
    private var translatePacksPreference: Preference? = null
    private var unloadDelayPreference: ListPreference? = null
    private var modelsPreference: Preference? = null
    private var deletePreference: Preference? = null
    private var installJob: Job? = null
    private var progressDialog: AlertDialog? = null

    /** 选择本地 ZIP 模型包；SAF Uri 由 ModelInstaller 流式读取，不需要存储权限。 */
    private val openZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = context ?: return@registerForActivityResult
            startImport(ctx, uri, queryDisplayName(ctx, uri))
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        // 每次打开设置页都刷新 voice/installed.txt，方便在文件管理器里核对模型到底装在哪。
        ModelInstaller.writeInstalledIndex(ctx)

        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            // 参照咩版：先给状态，再分组配置；每项摘要只留当前值，长说明放进点击后的弹窗。
            addPreference(category(ctx, KEY_CATEGORY_STATUS, "当前状态"))

            addPreference(
                Preference(ctx).apply {
                    key = KEY_STATUS
                    title = getString(R.string.local_asr_model_status)
                    isIconSpaceReserved = false
                    isSelectable = true
                    modelStatusPreference = this
                    setOnPreferenceClickListener {
                        val status = runCatching { VoiceModels.status(ctx, LocalVoiceSettings.vendor(ctx)) }
                            .getOrNull()
                        if (status?.ready == true) {
                            showModelDetail(ctx)
                        } else {
                            // 还没有可用模型时，直接给出一键下载入口，避免只显示路径却无从下手。
                            val recommended = ModelCatalog.VARIANTS.minByOrNull { it.downloadMiB }
                            if (recommended != null) {
                                confirmDownload(ctx, recommended) { refresh(ctx) }
                            } else {
                                showModelList(ctx)
                            }
                        }
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_PERMISSION
                    title = getString(R.string.local_asr_permission)
                    isIconSpaceReserved = false
                    isSelectable = true
                    permissionPreference = this
                    setOnPreferenceClickListener {
                        if (!AudioRecorder.hasPermission(ctx)) {
                            @Suppress("DEPRECATION")
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                        }
                        true
                    }
                },
            )

            addPreference(category(ctx, KEY_CATEGORY_BASIC, "基本设置"))

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_ENABLED
                    title = getString(R.string.local_asr_enabled)
                    summary = getString(R.string.local_asr_enabled_summary)
                    isIconSpaceReserved = false
                    isChecked = LocalVoiceSettings.isEnabled(ctx)
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setEnabled(ctx, newValue as Boolean)
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_VENDOR
                    title = getString(R.string.local_asr_vendor)
                    isIconSpaceReserved = false
                    vendorPreference = this
                    setOnPreferenceClickListener {
                        showEnginePicker(ctx)
                        true
                    }
                },
            )

            addPreference(category(ctx, KEY_CATEGORY_MODELS, "模型"))

            addPreference(
                Preference(ctx).apply {
                    key = KEY_MODELS
                    isIconSpaceReserved = false
                    modelsPreference = this
                    setOnPreferenceClickListener {
                        // 无论装没装模型都先给「本地语音包 + 可下载模型」选择列表，
                        // 不再在空目录时直接替用户下载某一个规格。
                        showModelManager(ctx)
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_IMPORT_ZIP
                    title = "从本地导入模型（ZIP）"
                    summary = "选择已下载的模型包，自动安装并设为当前"
                    isIconSpaceReserved = false
                    isSelectable = true
                    setOnPreferenceClickListener {
                        // 部分文件管理器给 zip 的 MIME 是 octet-stream，用 */* 保证能选到。
                        openZipLauncher.launch(arrayOf("*/*"))
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_DELETE_MODELS
                    title = "删除已安装模型"
                    isIconSpaceReserved = false
                    isSelectable = true
                    deletePreference = this
                    setOnPreferenceClickListener {
                        showDeleteModels(ctx)
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_SOURCE
                    title = getString(R.string.local_asr_source)
                    isIconSpaceReserved = false
                    isSelectable = true
                    sourcePreference = this
                    setOnPreferenceClickListener {
                        showSourceDialog(ctx)
                        true
                    }
                },
            )

            addPreference(category(ctx, KEY_CATEGORY_RECOG, "识别选项"))

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_THREADS
                    title = getString(R.string.local_asr_threads)
                    entries = THREAD_VALUES
                    entryValues = THREAD_VALUES
                    isIconSpaceReserved = false
                    threadsPreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setThreads(
                            ctx,
                            LocalVoiceSettings.vendor(ctx),
                            (newValue as String).toInt(),
                        )
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_LANGUAGE
                    title = "识别语言"
                    entries = LANGUAGE_LABELS
                    entryValues = LANGUAGE_VALUES
                    isIconSpaceReserved = false
                    languagePreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setLanguage(
                            ctx,
                            LocalVoiceSettings.vendor(ctx),
                            newValue as String,
                        )
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_ITN
                    title = getString(R.string.local_asr_itn)
                    summary = getString(R.string.local_asr_itn_summary)
                    isIconSpaceReserved = false
                    itnPreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setItn(ctx, LocalVoiceSettings.vendor(ctx), newValue as Boolean)
                        true
                    }
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_OUTPUT_CONVERSION
                    title = "简繁转换"
                    entries = CONVERSION_LABELS
                    entryValues = CONVERSION_VALUES
                    isIconSpaceReserved = false
                    conversionPreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setOutputConversion(ctx, newValue as String)
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_TRANSLATE
                    title = "语音输入后翻译（离线）"
                    summary = "识别结果先本地翻译再上屏 · 首次需下载语言包"
                    isIconSpaceReserved = false
                    isChecked = LocalVoiceSettings.isTranslateEnabled(ctx)
                    translatePreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setTranslateEnabled(ctx, newValue as Boolean)
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_TRANSLATE_DIRECTION
                    title = "翻译方向"
                    entries = OfflineTranslator.DIRECTION_LABELS
                    entryValues = OfflineTranslator.DIRECTION_VALUES
                    isIconSpaceReserved = false
                    translateDirectionPreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setTranslateDirection(ctx, newValue as String)
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_TRANSLATE_PACKS
                    title = "离线翻译语言包"
                    isIconSpaceReserved = false
                    translatePacksPreference = this
                    setOnPreferenceClickListener {
                        showTranslationPacks(ctx)
                        true
                    }
                },
            )

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_POWER_SAVE
                    title = "省电模式"
                    summary = "流式单核推理 · 静音 10 秒自动结束录音 · 不缓存整段 PCM"
                    isIconSpaceReserved = false
                    isChecked = LocalVoiceSettings.isPowerSave(ctx)
                    powerSavePreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setPowerSave(ctx, newValue as Boolean)
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_KEEP_ALIVE
                    title = "模型驻留内存"
                    entries = KEEP_ALIVE_LABELS
                    entryValues = KEEP_ALIVE_VALUES
                    isIconSpaceReserved = false
                    keepAlivePreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setKeepAliveMinutes(
                            ctx,
                            LocalVoiceSettings.vendor(ctx),
                            (newValue as String).toInt(),
                        )
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_UNLOAD_DELAY
                    title = "关闭键盘后延迟卸载"
                    entries = UNLOAD_DELAY_LABELS
                    entryValues = UNLOAD_DELAY_VALUES
                    isIconSpaceReserved = false
                    unloadDelayPreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setUnloadDelayMinutes(ctx, (newValue as String).toInt())
                        refresh(ctx)
                        true
                    }
                },
            )

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SAVE_RECORDING
                    title = getString(R.string.local_asr_save_recording)
                    summary = getString(R.string.local_asr_save_recording_summary)
                    isIconSpaceReserved = false
                    isChecked = LocalVoiceSettings.isSaveRecording(ctx)
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setSaveRecording(ctx, newValue as Boolean)
                        true
                    }
                },
            )

            // 咩版把用法说明放在页尾小字：这里列表只留一行，完整说明点开看。
            addPreference(
                Preference(ctx).apply {
                    key = KEY_USAGE
                    title = getString(R.string.local_asr_usage)
                    summary = "长按空格说话，松手自动上屏 · 点击看完整说明"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.local_asr_usage)
                            .setMessage(R.string.local_asr_usage_summary)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton("开源项目") { _, _ ->
                                // 直接打开「说点啥」仓库，方便查看原项目与许可。
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(BIBI_REPO_URL),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                            .show()
                        true
                    }
                },
            )
        }
        refresh(ctx)
    }

    override fun onResume() {
        super.onResume()
        context?.let(::refresh)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) context?.let(::refresh)
    }

    /** 离线翻译语言包：查看当前方向所需语向的就绪状态，可下载 / 删除。 */
    private fun showTranslationPacks(ctx: Context) {
        if (!OfflineTranslator.isAvailable()) {
            AlertDialog.Builder(ctx)
                .setTitle("离线翻译语言包")
                .setMessage("当前设备不支持离线翻译：需要 Android 9 及以上、64 位 arm64 设备。")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val direction = LocalVoiceSettings.translateDirection(ctx)
        val pairs = OfflineTranslator.pairsFor(direction)
        val label = OfflineTranslator.chain(direction, true).label
        lifecycleScope.launch(Dispatchers.IO) {
            val states = pairs.map { it to (OfflineTranslator.installed(ctx, it) != null) }
            withContext(Dispatchers.Main) {
                val body =
                    buildString {
                        append("当前方向：").append(label).appendLine()
                        states.forEach { (pair, ready) ->
                            append(if (ready) "✓ " else "○ ")
                            append(pair.source).append(" → ").append(pair.target)
                            append(if (ready) "  已就绪" else "  未下载（约 50 MiB）")
                            appendLine()
                        }
                        appendLine()
                        append("模型来自 Mozilla Firefox Translations（MPL-2.0）。首次需联网下载，之后翻译全程离线；翻译未就绪时自动按原文上屏。")
                    }
                AlertDialog.Builder(ctx)
                    .setTitle("离线翻译语言包")
                    .setMessage(body)
                    .setPositiveButton("下载 / 检查") { _, _ -> downloadTranslationPacks(ctx, pairs) }
                    .setNeutralButton("删除语言包") { _, _ -> deleteTranslationPacks(ctx, pairs) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun downloadTranslationPacks(
        ctx: Context,
        pairs: List<LanguagePair>,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dialog =
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(ctx)
                        .setTitle("正在准备离线语言包")
                        .setMessage("下载中，请保持网络连接…\n每套约 50 MiB，首次可能需要几分钟。")
                        .setCancelable(false)
                        .show()
                }
            var ready = 0
            for (pair in pairs) {
                if (OfflineTranslator.prepare(ctx, pair) != null) ready++
            }
            withContext(Dispatchers.Main) {
                runCatching { dialog.dismiss() }
                Toast.makeText(ctx, "语言包就绪 $ready / ${pairs.size} 套", Toast.LENGTH_SHORT).show()
                refresh(ctx)
            }
        }
    }

    private fun deleteTranslationPacks(
        ctx: Context,
        pairs: List<LanguagePair>,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            for (pair in pairs) OfflineTranslator.delete(ctx, pair)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "已删除所选方向的语言包", Toast.LENGTH_SHORT).show()
                refresh(ctx)
            }
        }
    }

    /** 统一分组样式：不占图标位，标题不与组内第一项重名。 */
    private fun category(ctx: Context, key: String, title: String) = PreferenceCategory(ctx).apply {
        this.key = key
        this.title = title
        isIconSpaceReserved = false
    }

    /** voice 目录里真实存在的模型（下载安装 / ZIP 导入 / 手动放置）。 */
    private fun localPackages(ctx: Context): List<LocalVoicePackage> =
        runCatching { ModelInstaller.localPackages(ctx) }.getOrDefault(emptyList())

    private fun localValue(pkg: LocalVoicePackage): String =
        "local|${pkg.familyId}|${pkg.key}"

    /** 只把「非规格目录」的本地包单列，避免与下面「已安装」的规格模型重复。 */
    private fun manualPackages(ctx: Context): List<LocalVoicePackage> {
        val catalogIds = ModelCatalog.VARIANTS.map { it.id }.toHashSet()
        return localPackages(ctx).filterNot { "${it.familyId}/${it.key}" in catalogIds }
    }

    /**
     * 「识别引擎与模型」弹窗的分组数据：本地语音包置顶，其后是已下载规格，
     * 再按引擎家族分组列出可下载模型；每项只显示短标题 + 一行大小/状态。
     */
    private fun engineRows(ctx: Context): List<EngineRow> {
        val installed = getString(R.string.local_asr_model_installed)
        val notInstalled = getString(R.string.local_asr_model_not_installed)
        val rows = mutableListOf<EngineRow>()
        val manual = manualPackages(ctx)
        if (manual.isNotEmpty()) {
            rows += EngineRow.Header("本地语音包（手动放置 / ZIP 导入）· 点击设为当前")
            manual.forEach { pkg ->
                rows += EngineRow.Option(
                    localValue(pkg),
                    pkg.label,
                    "${formatMiB(pkg.sizeBytes)} · ${displayPath(ctx, pkg.dir.absolutePath)}",
                )
            }
        }
        val ready = ModelCatalog.VARIANTS
            .filter { ModelInstaller.isInstalled(ctx, it) }
            .sortedWith(compareBy({ it.familyLabel }, { it.downloadMiB }))
        if (ready.isNotEmpty()) {
            rows += EngineRow.Header("已安装 · 点击设为当前")
            ready.forEach { v ->
                rows += EngineRow.Option(
                    "${v.familyId}|${v.key}",
                    "${v.familyLabel} · ${v.label}",
                    "${v.downloadMiB} MiB · $installed",
                )
            }
        }
        VoiceModels.FAMILIES.forEach { family ->
            rows += EngineRow.Header(family.label.substringBefore("（"))
            rows += EngineRow.Option("${family.id}|", "自动选择", "使用本引擎下已放置的模型")
            ModelCatalog.variantsOf(family.id)
                .filterNot { ModelInstaller.isInstalled(ctx, it) }
                .sortedBy { it.downloadMiB }
                .forEach { v ->
                    rows += EngineRow.Option(
                        "${v.familyId}|${v.key}",
                        v.label,
                        "${v.downloadMiB} MiB · $notInstalled · 点击下载",
                    )
                }
        }
        return rows
    }

    /** 自动项切换引擎；具体模型项要么设为当前，要么触发下载；local| 指向手动放置的目录。 */
    private fun onEngineSelected(ctx: Context, value: String) {
        if (value.startsWith("local|")) {
            val parts = value.split('|', limit = 3)
            val familyId = parts.getOrElse(1) { "" }
            val key = parts.getOrElse(2) { "" }
            if (familyId.isNotBlank()) {
                LocalVoiceSettings.setVendor(ctx, familyId)
                LocalVoiceSettings.setSelectedVariant(ctx, familyId, key)
            }
            refresh(ctx)
            return
        }
        val familyId = value.substringBefore('|')
        val variantKey = value.substringAfter('|')
        if (variantKey.isEmpty()) {
            LocalVoiceSettings.setVendor(ctx, familyId)
            LocalVoiceSettings.setSelectedVariant(ctx, familyId, "")
            refresh(ctx)
            return
        }
        val variant = ModelCatalog.byId("$familyId/$variantKey")
        if (variant == null) {
            refresh(ctx)
            return
        }
        if (ModelInstaller.isInstalled(ctx, variant)) {
            LocalVoiceSettings.setVendor(ctx, familyId)
            LocalVoiceSettings.setSelectedVariant(ctx, familyId, variantKey)
            refresh(ctx)
        } else {
            confirmDownload(ctx, variant) { refresh(ctx) }
        }
    }

    /** 自定义单选弹窗：分组标题不可点，选项两行文字 + 单选标记。 */
    private fun showEnginePicker(ctx: Context) {
        val rows = engineRows(ctx)
        val family = LocalVoiceSettings.vendor(ctx)
        val key = LocalVoiceSettings.selectedVariant(ctx, family)
        val current = when {
            key.isEmpty() && localPackages(ctx).any { it.key.isEmpty() } -> "local|$family|"
            key.isNotEmpty() && ModelCatalog.byId("$family/$key") == null -> "local|$family|$key"
            else -> "$family|$key"
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.local_asr_vendor))
            .setAdapter(engineAdapter(ctx, rows, current, showRadio = true)) { dialog, which ->
                val row = rows.getOrNull(which) as? EngineRow.Option ?: return@setAdapter
                dialog.dismiss()
                onEngineSelected(ctx, row.value)
            }
            .setNeutralButton("删除模型") { _, _ -> showDeleteModels(ctx) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 「模型下载与管理」：始终给完整选择列表，先列本地语音包再列可下载规格。
     * 空目录时也列出全部规格，不再默默替用户下载体积最小的那一个。
     */
    private fun showModelManager(ctx: Context) {
        val packages = localPackages(ctx)
        val variants = ModelCatalog.VARIANTS.sortedWith(
            compareByDescending<ModelVariant> { ModelInstaller.isInstalled(ctx, it) }
                .thenBy { it.familyLabel }
                .thenBy { it.downloadMiB },
        )
        val rows = mutableListOf<EngineRow>()
        if (packages.isNotEmpty()) {
            rows += EngineRow.Header("本地语音包（已安装 ${packages.size} 套）· 点击管理")
            packages.forEach { pkg ->
                rows += EngineRow.Option(
                    localValue(pkg),
                    pkg.label,
                    "${formatMiB(pkg.sizeBytes)} · ${displayPath(ctx, pkg.dir.absolutePath)}",
                )
            }
        }
        rows += EngineRow.Header("可下载模型（${variants.size} 种）· 点击下载")
        var lastFamily = ""
        variants.forEach { v ->
            if (v.familyLabel != lastFamily) {
                rows += EngineRow.Header(v.familyLabel)
                lastFamily = v.familyLabel
            }
            val state = if (ModelInstaller.isInstalled(ctx, v)) {
                "已安装 · 点击设为当前"
            } else {
                "${v.downloadMiB} MiB · 点击下载"
            }
            rows += EngineRow.Option("catalog|${v.id}", v.label, state)
        }
        AlertDialog.Builder(ctx)
            .setTitle("模型下载与管理")
            .setAdapter(engineAdapter(ctx, rows, current = "", showRadio = false)) { dialog, which ->
                val row = rows.getOrNull(which) as? EngineRow.Option ?: return@setAdapter
                dialog.dismiss()
                when {
                    row.value.startsWith("local|") -> {
                        val pkg = packages.firstOrNull { localValue(it) == row.value }
                        if (pkg != null) showLocalPackageActions(ctx, pkg)
                    }
                    row.value.startsWith("catalog|") -> {
                        val variant = ModelCatalog.byId(row.value.removePrefix("catalog|"))
                        if (variant != null) {
                            if (ModelInstaller.isInstalled(ctx, variant)) {
                                LocalVoiceSettings.setVendor(ctx, variant.familyId)
                                LocalVoiceSettings.setSelectedVariant(ctx, variant.familyId, variant.key)
                                refresh(ctx)
                            } else {
                                confirmDownload(ctx, variant) { refresh(ctx) }
                            }
                        }
                    }
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("从本地导入 ZIP") { _, _ -> openZipLauncher.launch(arrayOf("*/*")) }
            .setNegativeButton("删除管理") { _, _ -> showDeleteModels(ctx) }
            .show()
    }

    /** 本地语音包的操作：设为当前 / 复制路径 / 删除。 */
    private fun showLocalPackageActions(ctx: Context, pkg: LocalVoicePackage) {
        val actions = arrayOf("设为当前引擎", "复制目录路径", "删除此模型")
        AlertDialog.Builder(ctx)
            .setTitle(pkg.label)
            .setMessage(
                "目录：${displayPath(ctx, pkg.dir.absolutePath)}\n" +
                    "占用：${formatMiB(pkg.sizeBytes)}",
            ).setItems(actions) { _, which ->
                when (which) {
                    0 -> onEngineSelected(ctx, localValue(pkg))
                    1 -> copyPath(ctx, pkg.dir.absolutePath)
                    else -> confirmDelete(
                        ctx,
                        listOf(
                            InstalledModel(
                                dir = pkg.dir,
                                label = pkg.label,
                                sizeBytes = pkg.sizeBytes,
                                flatRoot = pkg.flatRoot,
                            ),
                        ),
                    )
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 「模型列表」弹窗：同样两行文字，按已安装 → 引擎家族 → 体积排序。 */
    private fun showModelList(ctx: Context) {
        val variants = ModelCatalog.VARIANTS.sortedWith(
            compareByDescending<ModelVariant> { ModelInstaller.isInstalled(ctx, it) }
                .thenBy { it.familyLabel }
                .thenBy { it.downloadMiB },
        )
        val installed = getString(R.string.local_asr_model_installed)
        val notInstalled = getString(R.string.local_asr_model_not_installed)
        val rows = variants.map { v ->
            val state = if (ModelInstaller.isInstalled(ctx, v)) installed else notInstalled
            EngineRow.Option(v.id, "${v.familyLabel} · ${v.label}", "${v.downloadMiB} MiB · $state")
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.local_asr_models)
            .setAdapter(engineAdapter(ctx, rows, current = "", showRadio = false)) { dialog, which ->
                val variant = variants[which]
                dialog.dismiss()
                if (ModelInstaller.isInstalled(ctx, variant)) {
                    showInstalledActions(ctx, variant)
                } else {
                    confirmDownload(ctx, variant) { refresh(ctx) }
                }
            }
            .setNeutralButton("删除模型") { _, _ -> showDeleteModels(ctx) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun engineAdapter(
        ctx: Context,
        rows: List<EngineRow>,
        current: String,
        showRadio: Boolean,
    ): BaseAdapter = object : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): EngineRow = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int =
            if (rows[position] is EngineRow.Header) 0 else 1
        override fun isEnabled(position: Int): Boolean = rows[position] is EngineRow.Option
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is EngineRow.Header -> headerView(ctx, row.title)
                is EngineRow.Option -> optionView(
                    ctx, row.title, row.subtitle, showRadio && row.value == current, showRadio,
                )
            }
    }

    /** 咩版把当前选中项用主题强调色标出，而不是只靠单选圈。 */
    private fun accentColor(ctx: Context): Int {
        val value = TypedValue()
        return if (ctx.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) {
            value.data
        } else {
            0xFF7C4DFF.toInt()
        }
    }

    private fun headerView(ctx: Context, title: String): View {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = title
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.7f
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (6 * d).toInt())
        }
    }

    private fun optionView(
        ctx: Context,
        title: String,
        subtitle: String,
        checked: Boolean,
        showRadio: Boolean,
    ): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * d).toInt()
            setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt())
        }
        if (showRadio) {
            row.addView(
                RadioButton(ctx).apply {
                    isChecked = checked
                    isClickable = false
                    isFocusable = false
                    setPadding(0, 0, (8 * d).toInt(), 0)
                },
            )
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(ctx).apply {
                text = title
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, if (checked) Typeface.BOLD else Typeface.NORMAL)
                if (checked) setTextColor(accentColor(ctx))
            },
        )
        texts.addView(
            TextView(ctx).apply {
                text = subtitle
                textSize = 13f
                alpha = 0.65f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun showInstalledActions(ctx: Context, variant: ModelVariant) {
        val path = ModelInstaller.installedDir(ctx, variant).absolutePath
        val actions = arrayOf(
            getString(R.string.local_asr_model_set_current),
            getString(R.string.local_asr_copy_path),
            getString(R.string.local_asr_model_delete),
        )
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.local_asr_model_actions, variant.displayName))
            .setMessage(getString(R.string.local_asr_model_dir, displayPath(ctx, path)))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        LocalVoiceSettings.setVendor(ctx, variant.familyId)
                        LocalVoiceSettings.setSelectedVariant(ctx, variant.familyId, variant.key)
                        refresh(ctx)
                    }
                    1 -> copyPath(ctx, path)
                    else -> confirmDeleteVariant(ctx, variant)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 单个规格模型的删除确认，避免误点就删掉几百 MB。 */
    private fun confirmDeleteVariant(ctx: Context, variant: ModelVariant) {
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.local_asr_model_delete))
            .setMessage(
                "确定删除 ${variant.displayName} 的模型文件吗？\n" +
                    "目录：${displayPath(ctx, ModelInstaller.installedDir(ctx, variant).absolutePath)}\n" +
                    "删除后需重新下载才能使用，此操作不可恢复。",
            ).setPositiveButton("删除") { _, _ ->
                // uninstall 会等待 native 识别器释放并递归删除几百 MB 文件，
                // 绝不能放在主线程，否则 ANR / native 崩溃直接把 APP 关掉。
                runDeletion(ctx, "已删除 ${variant.displayName}") {
                    ModelInstaller.uninstall(ctx.applicationContext, variant)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 统一管理入口：列出 voice 目录里所有真实存在的模型（含 ZIP 导入、手动放置、
     * 安装失败残留），支持多选删除；另外可单独清理未完成的下载缓存。
     */
    private fun showDeleteModels(ctx: Context) {
        val models = ModelInstaller.installedModels(ctx)
        val cacheBytes = ModelInstaller.downloadCacheSize(ctx)
        if (models.isEmpty() && cacheBytes <= 0L) {
            toast(ctx, "没有可删除的模型或下载缓存")
            return
        }
        val labels = models.map { "${it.label} · ${formatMiB(it.sizeBytes)}" }.toTypedArray()
        val checked = BooleanArray(models.size)
        AlertDialog.Builder(ctx)
            .setTitle("删除已安装模型")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("删除所选") { _, _ ->
                val selected = models.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    toast(ctx, "未勾选任何模型")
                } else {
                    confirmDelete(ctx, selected)
                }
            }
            .setNeutralButton("清理缓存（${formatMiB(cacheBytes)}）") { _, _ ->
                val app = ctx.applicationContext
                runDeletion(
                    ctx,
                    if (cacheBytes > 0L) "已清理下载缓存 ${formatMiB(cacheBytes)}" else "下载缓存为空",
                ) { ModelInstaller.clearDownloadCache(app) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(ctx: Context, models: List<InstalledModel>) {
        val total = models.sumOf { it.sizeBytes }
        val message = buildString {
            appendLine("将永久删除以下模型（不可恢复）：")
            models.forEach { appendLine("· ${it.label} · ${formatMiB(it.sizeBytes)}") }
            appendLine()
            append("共 ${models.size} 套，约 ${formatMiB(total)}。删除后需重新下载或导入。")
        }
        AlertDialog.Builder(ctx)
            .setTitle("确认删除模型")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> performDelete(ctx, models) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performDelete(ctx: Context, models: List<InstalledModel>) {
        val app = ctx.applicationContext
        runDeletion(ctx, "已删除 ${models.size} 套模型，释放 ${formatMiB(models.sumOf { it.sizeBytes })}") {
            ModelInstaller.deleteModels(app, models)
        }
    }

    /**
     * 所有删除 / 清理统一走这里：进度对话框 + IO 线程 + 捕获全部异常。
     * 之前删除动作直接在主线程 free native 识别器并递归删几百 MB，异常或 ANR
     * 都会让进程直接退出；现在即使失败也只给一条中文提示。
     */
    private fun runDeletion(
        ctx: Context,
        successMessage: String,
        block: suspend () -> Unit,
    ) {
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("正在删除模型")
            .setMessage("正在释放识别器并删除模型文件，请稍候…")
            .setCancelable(false)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                toast(ctx, successMessage)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                toast(ctx, "删除失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { dialog.dismiss() }
                if (isAdded) context?.let(::refresh)
            }
        }
    }

    private fun formatMiB(bytes: Long): String = "%.1f MiB".format(bytes / 1048576.0)

    /** 切换下载源；手动下载 / 复制地址收进本弹窗，避免顶层再挂一个重复入口。 */
    private fun showSourceDialog(ctx: Context) {
        val original = LocalVoiceSettings.downloadSource(ctx)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.local_asr_source))
            .setSingleChoiceItems(ModelCatalog.SOURCES.toTypedArray(), original) { _, which ->
                LocalVoiceSettings.setDownloadSource(ctx, which)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> refresh(ctx) }
            .setNeutralButton("模型地址") { _, _ ->
                refresh(ctx)
                showManualDownload(ctx)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                LocalVoiceSettings.setDownloadSource(ctx, original)
                refresh(ctx)
            }
            .show()
    }

    /** 手动下载：先选模型，再展示官方地址与当前源 / 备用镜像地址，可一键复制。 */
    private fun showManualDownload(ctx: Context) {
        val variants = ModelCatalog.VARIANTS
        AlertDialog.Builder(ctx)
            .setTitle("手动下载模型")
            .setItems(variants.map { "${it.displayName} · ${it.downloadMiB} MiB" }.toTypedArray()) { _, which ->
                showDownloadUrls(ctx, variants[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDownloadUrls(ctx: Context, variant: ModelVariant) {
        val source = LocalVoiceSettings.downloadSource(ctx)
        val urls = ModelCatalog.candidateUrls(variant, source)
        val official = ModelCatalog.officialUrl(variant)
        val message = buildString {
            appendLine(variant.displayName)
            appendLine("文件名：${variant.zipName}")
            appendLine("大小：约 ${variant.downloadMiB} MiB")
            appendLine("当前下载源：${ModelCatalog.SOURCES[source]}")
            appendLine()
            urls.forEachIndexed { index, url ->
                appendLine(if (index == 0) "当前源：" else "备用源 $index：")
                appendLine(url)
                appendLine()
            }
            appendLine("下载好 ZIP 后，回到本页用「从本地导入模型（ZIP）」安装即可。")
        }
        AlertDialog.Builder(ctx)
            .setTitle("模型下载地址")
            .setMessage(message)
            .setPositiveButton("复制当前源地址") { _, _ -> copyUrl(ctx, urls.first()) }
            .setNeutralButton("复制官方地址") { _, _ -> copyUrl(ctx, official) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDownload(ctx: Context, variant: ModelVariant, onFinished: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(variant.displayName)
            .setMessage(
                getString(
                    R.string.local_asr_download_confirm,
                    variant.displayName,
                    variant.downloadMiB,
                ),
            )
            .setPositiveButton(android.R.string.ok) { _, _ -> startInstall(ctx, variant, onFinished) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onFinished() }
            .show()
    }

    private fun startInstall(ctx: Context, variant: ModelVariant, onFinished: () -> Unit) =
        runInstall(ctx, ModelInstaller.PHASE_DOWNLOAD, onFinished) { active, onStage ->
            ModelInstaller.install(
                context = ctx.applicationContext,
                variant = variant,
                sourceIndex = LocalVoiceSettings.downloadSource(ctx),
                isActive = active,
                onStage = onStage,
            )
        }

    private fun startImport(ctx: Context, uri: Uri, displayName: String) =
        runInstall(ctx, ModelInstaller.PHASE_IMPORT) { active, onStage ->
            ModelInstaller.installFromUri(
                context = ctx.applicationContext,
                source = uri,
                displayName = displayName,
                isActive = active,
                onStage = onStage,
            )
        }

    /** 下载安装与本地 ZIP 导入共用的进度对话框、结果提示与错误处理。 */
    private fun runInstall(
        ctx: Context,
        initialPhase: String,
        onFinished: () -> Unit = {},
        installBlock: suspend (
            active: () -> Boolean,
            onStage: suspend (String, Int, String) -> Unit,
        ) -> File,
    ) {
        val density = ctx.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val detail = TextView(ctx).apply {
            setPadding(0, pad / 2, 0, 0)
            text = getString(R.string.local_asr_progress, initialPhase, 0, "")
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(progress)
            addView(detail)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.local_asr_install_title)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> installJob?.cancel() }
            .show()
        progressDialog = dialog

        installJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val active: () -> Boolean = { isActive }
                val dir = installBlock(active) { phase, percent, note ->
                    withContext(Dispatchers.Main) {
                        val pct = percent.coerceIn(0, 100)
                        progress.progress = pct
                        detail.text = getString(R.string.local_asr_progress, phase, pct, note)
                    }
                }
                withContext(Dispatchers.Main) {
                    val path = dir.absolutePath
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.local_asr_install_done)
                        .setMessage(
                            getString(R.string.local_asr_install_done_msg, dir.name, displayPath(ctx, path)),
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.local_asr_copy_path) { _, _ -> copyPath(ctx, path) }
                        .show()
                }
            } catch (_: CancellationException) {
                main.post { toast(ctx, getString(R.string.local_asr_install_cancelled)) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    val root = VoiceModels.primaryRoot(ctx).absolutePath
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.local_asr_install_failed)
                        .setMessage(
                            getString(
                                R.string.local_asr_install_failed_msg,
                                t.message ?: t.javaClass.simpleName,
                                displayPath(ctx, root),
                            ),
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.local_asr_copy_path) { _, _ -> copyPath(ctx, root) }
                        .show()
                }
            } finally {
                main.post {
                    runCatching { dialog.dismiss() }
                    if (progressDialog === dialog) progressDialog = null
                    if (isAdded) {
                        refresh(ctx)
                        onFinished()
                    }
                }
            }
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "model.zip"

    private fun toast(ctx: Context, message: String) {
        runCatching { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 界面上只显示短路径，避免 Android/data/…/files/rime/voice/… 一整串把设置项撑成好几行；
     * 复制到剪贴板的仍是完整绝对路径。
     */
    private fun displayPath(ctx: Context, path: String): String {
        val root = VoiceModels.primaryRoot(ctx).absolutePath
        return when {
            path == root -> "…/rime/voice"
            path.startsWith("$root/") -> "voice/" + path.removePrefix("$root/")
            else -> {
                val parts = path.split('/').filter { it.isNotEmpty() }
                if (parts.size <= 3) path else "…/" + parts.takeLast(3).joinToString("/")
            }
        }
    }

    /** 应用私有外部目录在部分文件管理器里不可见，允许直接复制绝对路径到 adb / 文件管理器。 */
    private fun copyPath(ctx: Context, path: String) {
        runCatching {
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("voice model path", path))
            toast(ctx, getString(R.string.local_asr_path_copied, displayPath(ctx, path)))
        }.onFailure { toast(ctx, path) }
    }

    private fun copyUrl(ctx: Context, url: String) {
        runCatching {
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("model download url", url))
            toast(ctx, "已复制下载地址：$url")
        }.onFailure { toast(ctx, url) }
    }

    private fun refresh(ctx: Context) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        permissionPreference?.summary = if (granted) {
            getString(R.string.local_asr_permission_granted)
        } else {
            getString(R.string.local_asr_permission_denied)
        }
        modelStatusPreference?.summary = buildModelSummary(ctx)

        val family = LocalVoiceSettings.vendor(ctx)
        vendorPreference?.summary = buildEngineSummary(ctx, family)

        // 咩版式：每项直接显示「当前：值」，说明不再塞进摘要。
        val threads = LocalVoiceSettings.threads(ctx, family).toString()
        threadsPreference?.apply {
            value = if (threads in THREAD_VALUES) threads else "2"
            summary = "当前：$value 线程 · ${getString(R.string.local_asr_threads_summary)}"
        }
        // 只有这两个家族的 sherpa-onnx 配置类带 language 字段；其余隐藏，避免给出无效选项。
        val supportsLanguage = family in LANGUAGE_FAMILIES
        languagePreference?.apply {
            isVisible = supportsLanguage
            if (supportsLanguage) {
                val current = LocalVoiceSettings.language(ctx, family)
                val v = if (current in LANGUAGE_VALUES) current else ""
                value = v
                summary = "当前：${LANGUAGE_LABELS[LANGUAGE_VALUES.indexOf(v)]}"
            }
        }
        // ITN 只在引擎真正读取该字段的家族里显示。
        itnPreference?.isVisible = family in ITN_FAMILIES
        itnPreference?.isChecked = LocalVoiceSettings.isItn(ctx, family)
        val conversion = LocalVoiceSettings.outputConversion(ctx)
        conversionPreference?.apply {
            value = conversion
            val label = CONVERSION_SHORT[CONVERSION_VALUES.indexOf(conversion).coerceAtLeast(0)]
            summary = "当前：$label\n仅影响语音上屏文字，打字不受影响"
        }
        val translateOn = LocalVoiceSettings.isTranslateEnabled(ctx)
        translatePreference?.isChecked = translateOn
        translateDirectionPreference?.apply {
            isVisible = translateOn
            val d = LocalVoiceSettings.translateDirection(ctx)
            value = d
            summary = "当前：${OfflineTranslator.DIRECTION_LABELS[OfflineTranslator.DIRECTION_VALUES.indexOf(d).coerceAtLeast(0)]}"
        }
        translatePacksPreference?.apply {
            isVisible = translateOn
            summary =
                if (!OfflineTranslator.isAvailable()) {
                    "当前设备不支持（需 Android 9+ / arm64）"
                } else {
                    val n = OfflineTranslator.pairsFor(LocalVoiceSettings.translateDirection(ctx)).size
                    "当前方向需要 $n 套语言包 · 点击下载 / 删除 / 查看状态"
                }
        }
        powerSavePreference?.isChecked = LocalVoiceSettings.isPowerSave(ctx)
        val keepAlive = LocalVoiceSettings.keepAliveMinutes(ctx, family).toString()
        keepAlivePreference?.apply {
            val v = if (keepAlive in KEEP_ALIVE_VALUES) keepAlive else "-1"
            value = v
            summary = "当前：${KEEP_ALIVE_LABELS[KEEP_ALIVE_VALUES.indexOf(v)]}"
        }
        unloadDelayPreference?.apply {
            isVisible = keepAlive == LocalVoiceSettings.MODE_FOLLOW_KEYBOARD.toString()
            val v = LocalVoiceSettings.unloadDelayMinutes(ctx).toString()
            value = v
            summary = "当前：键盘关闭 $v 分钟后卸载"
        }

        val source = LocalVoiceSettings.downloadSource(ctx)
        sourcePreference?.summary = "当前：${ModelCatalog.SOURCES[source]}\n点击切换下载源或复制模型地址"

        val present = ModelInstaller.installedModels(ctx)
        modelsPreference?.apply {
            title = "下载并安装模型 / 本地语音包"
            summary = if (present.isEmpty()) {
                "本地暂无语音包 · 可下载 ${ModelCatalog.VARIANTS.size} 种 · 点击打开选择列表"
            } else {
                "本地语音包 ${present.size} 套（占用 ${formatMiB(present.sumOf { it.sizeBytes })}）· " +
                    "可下载 ${ModelCatalog.VARIANTS.size} 种 · 点击查看 / 下载 / 切换"
            }
        }
        deletePreference?.summary = if (present.isEmpty()) {
            "暂无已安装的模型 · 可清理下载缓存"
        } else {
            "已安装 ${present.size} 套 · 占用 ${formatMiB(present.sumOf { it.sizeBytes })} · 点击选择并删除"
        }
        // 切换引擎 / 线程数 / 删除模型都会卸载识别器；从设置页返回时键盘常常已经弹着，
        // 不会再触发 onStartInputView，这里主动补一次后台预热，避免首次按语音键冷加载。
        VoiceLifecycle.warmUp(ctx.applicationContext)
    }

    /** 设置项摘要直接显示当前引擎与模型，避免列表弹窗关闭后看不出选了什么。 */
    private fun buildEngineSummary(ctx: Context, family: String): String {
        val familyLabel = VoiceModels.family(family)?.label?.substringBefore("（") ?: family
        val key = LocalVoiceSettings.selectedVariant(ctx, family)
        val variant = ModelCatalog.VARIANTS.firstOrNull { it.familyId == family && it.key == key }
        val current = when {
            variant != null -> "$familyLabel · ${variant.label}" +
                if (ModelInstaller.isInstalled(ctx, variant)) "（已安装）" else "（未安装）"
            key.isNotBlank() -> "$familyLabel · 本地语音包 $key"
            else -> "$familyLabel · 自动选择"
        }
        return "当前：$current\n点击选择引擎 / 具体模型"
    }

    /** 列表里只留两行状态；完整路径与缺件说明放到详情弹窗。 */
    private fun buildModelSummary(ctx: Context): String {
        val familyId = LocalVoiceSettings.vendor(ctx)
        val family = VoiceModels.family(familyId) ?: VoiceModels.FAMILIES.first()
        val status = runCatching { VoiceModels.status(ctx, familyId) }.getOrNull()
            ?: return getString(R.string.local_asr_model_unknown)
        val shortFamily = family.label.substringBefore("（")
        return if (status.ready) {
            val dir = status.resolved?.dir?.let { displayPath(ctx, it.absolutePath) }
                ?: getString(R.string.local_asr_model_ready)
            "已就绪 · $shortFamily\n$dir · 点击查看详情"
        } else {
            "未检测到完整模型 · $shortFamily\n点击一键下载推荐模型"
        }
    }

    /** 咩版式说明分层：完整模型信息（目录、缺件、官方包名）只在点击后展示。 */
    private fun showModelDetail(ctx: Context) {
        val familyId = LocalVoiceSettings.vendor(ctx)
        val family = VoiceModels.family(familyId) ?: VoiceModels.FAMILIES.first()
        val status = runCatching { VoiceModels.status(ctx, familyId) }.getOrNull()
        val root = status?.root?.absolutePath ?: VoiceModels.primaryRoot(ctx).absolutePath
        val message = buildString {
            append(getString(R.string.local_asr_model_root, displayPath(ctx, root)))
            append("\nvoice 固定在本应用私有目录，不随「使用者资料夹」变化\n")
            val selected = LocalVoiceSettings.selectedVariant(ctx, familyId)
            if (selected.isNotBlank()) append("当前模型：$familyId/$selected\n")
            if (status?.ready == true) {
                append(getString(R.string.local_asr_model_ready))
                status.resolved?.dir?.let { append("\n检测到模型：${displayPath(ctx, it.absolutePath)}") }
            } else {
                append(getString(R.string.local_asr_model_missing))
                append("\n需要文件：${family.groups.joinToString("、") { it.first() }}")
                if (status?.missing?.isNotEmpty() == true) {
                    append("\n尚缺：${status.missing.joinToString("、")}")
                }
                append("\n官方模型包：${family.packageName}")
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.local_asr_model_status)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.local_asr_copy_path) { _, _ -> copyPath(ctx, root) }
            .show()
    }

    private companion object {
        const val REQUEST_MIC = 1001
        const val KEY_ENABLED = "lv_enabled"
        const val KEY_VENDOR = "lv_vendor"
        const val KEY_PERMISSION = "lv_permission"
        const val KEY_STATUS = "lv_status"
        const val KEY_CATEGORY_STATUS = "lv_category_status"
        const val KEY_CATEGORY_BASIC = "lv_category_basic"
        const val KEY_CATEGORY_MODELS = "lv_category_models"
        const val KEY_MODELS = "lv_models"
        const val KEY_IMPORT_ZIP = "lv_import_zip"
        const val KEY_DELETE_MODELS = "lv_delete_models"
        const val KEY_SOURCE = "lv_source"
        const val KEY_CATEGORY_RECOG = "lv_category_recog"
        const val KEY_THREADS = "lv_threads"
        const val KEY_LANGUAGE = "lv_language"
        const val KEY_ITN = "lv_itn"
        const val KEY_OUTPUT_CONVERSION = "lv_output_conversion"
        const val KEY_KEEP_ALIVE = "lv_keep_alive"
        const val KEY_POWER_SAVE = "lv_power_save"
        const val KEY_UNLOAD_DELAY = "lv_unload_delay"
        const val KEY_TRANSLATE = "lv_translate"
        const val KEY_TRANSLATE_DIRECTION = "lv_translate_direction"
        const val KEY_TRANSLATE_PACKS = "lv_translate_packs"
        const val KEY_SAVE_RECORDING = "lv_save_recording"
        const val KEY_USAGE = "lv_usage"
        const val BIBI_REPO_URL = "https://github.com/BryceWG/BiBi-Keyboard"
        val THREAD_VALUES = arrayOf("1", "2", "3", "4", "6", "8")
        /** 空串 = 自动识别（与 LocalVoiceSettings.language 默认值、引擎 ifBlank{"auto"} 一致）。 */
        val LANGUAGE_VALUES = arrayOf("", "zh", "en", "ja", "ko", "yue")
        val LANGUAGE_LABELS = arrayOf(
            "自动识别（推荐）",
            "中文",
            "英文",
            "日文",
            "韩文",
            "粤语",
        )
        /** AAR 中确实带 language 字段的离线家族。 */
        val LANGUAGE_FAMILIES = setOf("sensevoice", "funasr_nano")
        /** 引擎里真正读取 ITN 的家族；其余家族隐藏该开关，避免给出无效选项。 */
        val ITN_FAMILIES = LANGUAGE_FAMILIES
        /** 简繁转换档位：顺序固定为 正常 / 标准 / 台湾 / 香港 / 繁转简 / 跟随。 */
        val CONVERSION_VALUES = arrayOf("off", "s2t", "s2tw", "s2hk", "t2s", "auto")
        val CONVERSION_LABELS = arrayOf(
            "正常输出",
            "简体 → 繁体（标准）",
            "简体 → 繁体（台湾）",
            "简体 → 繁体（香港）",
            "繁体 → 简体",
            "跟随输入法简/繁开关",
        )
        val CONVERSION_SHORT = CONVERSION_LABELS
        val KEEP_ALIVE_VALUES = arrayOf("-1", "-2", "0", "5", "15", "30")
        val KEEP_ALIVE_LABELS = arrayOf(
            "常驻内存（最快）",
            "跟随键盘：打开时预加载 / 关闭后延迟卸载",
            "用完即卸载（最省内存）",
            "空闲 5 分钟后卸载",
            "空闲 15 分钟后卸载",
            "空闲 30 分钟后卸载",
        )
        val UNLOAD_DELAY_VALUES = arrayOf("1", "3", "5", "10", "30")
        val UNLOAD_DELAY_LABELS = arrayOf(
            "1 分钟",
            "3 分钟（推荐）",
            "5 分钟",
            "10 分钟",
            "30 分钟",
        )
    }
}
