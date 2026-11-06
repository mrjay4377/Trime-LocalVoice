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
import com.osfans.trime.ime.voice.LocalVoiceSettings
import com.osfans.trime.ime.voice.ModelCatalog
import com.osfans.trime.ime.voice.ModelInstaller
import com.osfans.trime.ime.voice.ModelVariant
import com.osfans.trime.ime.voice.VoiceModels
import com.osfans.trime.ui.common.PaddingPreferenceFragment
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
    private var sourcePreference: Preference? = null
    private var itnPreference: SwitchPreferenceCompat? = null
    private var conversionPreference: ListPreference? = null
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
                        // 咩版的「下载并自动安装模型」主按钮：没装任何模型时本项就是它。
                        // 手动放置 / ZIP 导入的模型也算「已安装」，否则用户会找不到自己的模型。
                        val hasAny = ModelInstaller.installedModels(ctx).isNotEmpty() ||
                            ModelCatalog.VARIANTS.any { ModelInstaller.isInstalled(ctx, it) }
                        if (!hasAny) {
                            ModelCatalog.VARIANTS.minByOrNull { it.downloadMiB }
                                ?.let { confirmDownload(ctx, it) { refresh(ctx) } }
                        } else {
                            showModelList(ctx)
                        }
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
                    title = "输出字形"
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

    /** 统一分组样式：不占图标位，标题不与组内第一项重名。 */
    private fun category(ctx: Context, key: String, title: String) = PreferenceCategory(ctx).apply {
        this.key = key
        this.title = title
        isIconSpaceReserved = false
    }

    /**
     * 「识别引擎与模型」弹窗的分组数据：已安装置顶，其后按引擎家族分组，
     * 组内按体积升序；每个选项只显示短标题 + 一行大小/状态，不再让长文字互串。
     */
    private fun engineRows(ctx: Context): List<EngineRow> {
        val installed = getString(R.string.local_asr_model_installed)
        val notInstalled = getString(R.string.local_asr_model_not_installed)
        val rows = mutableListOf<EngineRow>()
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

    /** 自动项切换引擎；具体模型项要么设为当前，要么触发下载。 */
    private fun onEngineSelected(ctx: Context, value: String) {
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
        val current = "$family|${LocalVoiceSettings.selectedVariant(ctx, family)}"
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
                val ok = ModelInstaller.uninstall(ctx, variant)
                toast(
                    ctx,
                    if (ok) getString(R.string.local_asr_model_deleted) else "删除失败，请检查 voice 目录权限",
                )
                refresh(ctx)
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
                val freed = ModelInstaller.clearDownloadCache(ctx)
                toast(ctx, if (freed > 0L) "已清理下载缓存 ${formatMiB(freed)}" else "下载缓存为空")
                refresh(ctx)
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
        viewLifecycleOwner.lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { ModelInstaller.deleteModels(app, models) }
            toast(ctx, "已删除 ${models.size} 套模型，释放 ${formatMiB(freed)}")
            context?.let(::refresh)
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
        val keepAlive = LocalVoiceSettings.keepAliveMinutes(ctx, family).toString()
        keepAlivePreference?.apply {
            val v = if (keepAlive in KEEP_ALIVE_VALUES) keepAlive else "-1"
            value = v
            summary = "当前：${KEEP_ALIVE_LABELS[KEEP_ALIVE_VALUES.indexOf(v)]}"
        }

        val source = LocalVoiceSettings.downloadSource(ctx)
        sourcePreference?.summary = "当前：${ModelCatalog.SOURCES[source]}\n点击切换下载源或复制模型地址"

        // 没有模型时，这一项变成咩版那种显眼的「下载并自动安装」主操作。
        val present = ModelInstaller.installedModels(ctx)
        modelsPreference?.apply {
            if (present.isEmpty()) {
                val rec = ModelCatalog.VARIANTS.minByOrNull { it.downloadMiB }
                title = "下载并安装模型"
                summary = "推荐 ${rec?.displayName ?: "最小模型"} · 约 ${rec?.downloadMiB ?: 0} MiB · 点击一键安装"
            } else {
                title = getString(R.string.local_asr_models)
                summary = "已安装 ${present.size} 套 · 可下载 ${ModelCatalog.VARIANTS.size} 种 · 点击查看 / 下载 / 切换"
            }
        }
        deletePreference?.summary = if (present.isEmpty()) {
            "暂无已安装的模型 · 可清理下载缓存"
        } else {
            "已安装 ${present.size} 套 · 占用 ${formatMiB(present.sumOf { it.sizeBytes })} · 点击选择并删除"
        }
    }

    /** 设置项摘要直接显示当前引擎与模型，避免列表弹窗关闭后看不出选了什么。 */
    private fun buildEngineSummary(ctx: Context, family: String): String {
        val familyLabel = VoiceModels.family(family)?.label?.substringBefore("（") ?: family
        val key = LocalVoiceSettings.selectedVariant(ctx, family)
        val variant = ModelCatalog.VARIANTS.firstOrNull { it.familyId == family && it.key == key }
        val current = if (variant == null) {
            "$familyLabel · 自动选择"
        } else {
            "$familyLabel · ${variant.label}" +
                if (ModelInstaller.isInstalled(ctx, variant)) "（已安装）" else "（未安装）"
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
        const val KEY_SAVE_RECORDING = "lv_save_recording"
        const val KEY_USAGE = "lv_usage"
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
        /** 语音结果的字形转换；顺序与 ChineseConverter.MODE_* 一致。 */
        val CONVERSION_VALUES = arrayOf("off", "s2t", "s2tw", "t2s", "auto")
        val CONVERSION_LABELS = arrayOf(
            "不转换 · 原样输出",
            "简体 → 繁体（标准） · 语音→語音",
            "简体 → 繁体（台湾正体） · 里→裡",
            "繁体 → 简体 · 語音→语音",
            "跟随输入法简/繁开关",
        )
        val CONVERSION_SHORT = arrayOf(
            "不转换（原样输出）",
            "简体 → 繁体（标准）",
            "简体 → 繁体（台湾正体）",
            "繁体 → 简体",
            "跟随输入法简/繁开关",
        )
        val KEEP_ALIVE_VALUES = arrayOf("-1", "0", "5", "15", "30")
        val KEEP_ALIVE_LABELS = arrayOf(
            "常驻内存（最快）",
            "用完即卸载（最省内存）",
            "空闲 5 分钟后卸载",
            "空闲 15 分钟后卸载",
            "空闲 30 分钟后卸载",
        )
    }
}
