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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
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

    private data class EngineItem(val value: String, val label: String)

    private val main = Handler(Looper.getMainLooper())
    private var modelStatusPreference: Preference? = null
    private var permissionPreference: Preference? = null
    private var vendorPreference: ListPreference? = null
    private var threadsPreference: ListPreference? = null
    private var keepAlivePreference: ListPreference? = null
    private var sourcePreference: Preference? = null
    private var itnPreference: SwitchPreferenceCompat? = null
    private var modelsPreference: Preference? = null
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
            // 模型完整性检测放在第一位，打开设置就能看到当前引擎有没有可用模型。
            addPreference(
                Preference(ctx).apply {
                    key = KEY_STATUS
                    title = getString(R.string.local_asr_model_status)
                    isSelectable = true
                    modelStatusPreference = this
                    setOnPreferenceClickListener {
                        copyPath(ctx, VoiceModels.primaryRoot(ctx).absolutePath)
                        true
                    }
                },
            )

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_ENABLED
                    title = getString(R.string.local_asr_enabled)
                    summary = getString(R.string.local_asr_enabled_summary)
                    isChecked = LocalVoiceSettings.isEnabled(ctx)
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setEnabled(ctx, newValue as Boolean)
                        true
                    }
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_VENDOR
                    title = getString(R.string.local_asr_vendor)
                    summary = getString(R.string.local_asr_vendor_summary)
                    vendorPreference = this
                    setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance())
                    setOnPreferenceChangeListener { _, newValue -> onEngineSelected(ctx, newValue as String) }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_PERMISSION
                    title = getString(R.string.local_asr_permission)
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

            addPreference(
                PreferenceCategory(ctx).apply {
                    key = KEY_CATEGORY_MODELS
                    title = getString(R.string.local_asr_models)
                    isIconSpaceReserved = false
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_MODELS
                    title = getString(R.string.local_asr_models)
                    summary = getString(R.string.local_asr_models_summary)
                    modelsPreference = this
                    setOnPreferenceClickListener {
                        showModelList(ctx)
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_IMPORT_ZIP
                    title = "从本地导入模型（ZIP）"
                    summary = "选择已下载好的模型压缩包，自动识别引擎、解压安装并设为当前模型"
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
                    key = KEY_MANUAL_DOWNLOAD
                    title = "手动下载 · 查看 / 复制模型地址"
                    summary = "用浏览器或电脑下载 ZIP 后，用「从本地导入模型（ZIP）」安装"
                    isSelectable = true
                    setOnPreferenceClickListener {
                        showManualDownload(ctx)
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_SOURCE
                    title = getString(R.string.local_asr_source)
                    summary = getString(R.string.local_asr_source_summary)
                    isSelectable = true
                    sourcePreference = this
                    setOnPreferenceClickListener {
                        showSourceDialog(ctx)
                        true
                    }
                },
            )

            addPreference(
                PreferenceCategory(ctx).apply {
                    key = KEY_CATEGORY_RECOG
                    title = "识别选项"
                    isIconSpaceReserved = false
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_THREADS
                    title = getString(R.string.local_asr_threads)
                    summary = getString(R.string.local_asr_threads_summary)
                    entries = THREAD_VALUES
                    entryValues = THREAD_VALUES
                    threadsPreference = this
                    setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance())
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setThreads(
                            ctx,
                            LocalVoiceSettings.vendor(ctx),
                            (newValue as String).toInt(),
                        )
                        true
                    }
                },
            )

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_ITN
                    title = getString(R.string.local_asr_itn)
                    summary = getString(R.string.local_asr_itn_summary)
                    itnPreference = this
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setItn(ctx, LocalVoiceSettings.vendor(ctx), newValue as Boolean)
                        true
                    }
                },
            )

            addPreference(
                ListPreference(ctx).apply {
                    key = KEY_KEEP_ALIVE
                    title = "模型驻留内存"
                    summary = "控制识别完成后模型是否继续占用内存；改动会立即释放当前已加载的模型"
                    entries = KEEP_ALIVE_LABELS
                    entryValues = KEEP_ALIVE_VALUES
                    keepAlivePreference = this
                    setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance())
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setKeepAliveMinutes(
                            ctx,
                            LocalVoiceSettings.vendor(ctx),
                            (newValue as String).toInt(),
                        )
                        true
                    }
                },
            )

            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SAVE_RECORDING
                    title = getString(R.string.local_asr_save_recording)
                    summary = getString(R.string.local_asr_save_recording_summary)
                    isChecked = LocalVoiceSettings.isSaveRecording(ctx)
                    setOnPreferenceChangeListener { _, newValue ->
                        LocalVoiceSettings.setSaveRecording(ctx, newValue as Boolean)
                        true
                    }
                },
            )

            addPreference(
                Preference(ctx).apply {
                    key = KEY_USAGE
                    title = getString(R.string.local_asr_usage)
                    summary = getString(R.string.local_asr_usage_summary)
                    isSelectable = false
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

    /** 「本地识别引擎与模型」列表：每个家族一个自动扫描项 + 该家族下所有可下载模型。 */
    private fun engineItems(ctx: Context): List<EngineItem> {
        val installed = getString(R.string.local_asr_model_installed)
        val notInstalled = getString(R.string.local_asr_model_not_installed)
        val out = mutableListOf<EngineItem>()
        VoiceModels.FAMILIES.forEach { family ->
            val short = family.label.substringBefore("（")
            out += EngineItem("${family.id}|", short)
            // 已检测到完整模型的排在前面，方便直接切换，不必在长列表里找。
            ModelCatalog.variantsOf(family.id)
                .sortedByDescending { ModelInstaller.isInstalled(ctx, it) }
                .forEach { variant ->
                val state = if (ModelInstaller.isInstalled(ctx, variant)) installed else notInstalled
                out += EngineItem(variant.id, "    ${variant.label} · ${variant.downloadMiB} MiB · $state")
            }
        }
        return out
    }

    /** 返回 false：value 由 refresh() 统一写入，避免未完成安装时就显示成已选中。 */
    private fun onEngineSelected(ctx: Context, value: String): Boolean {
        val familyId = value.substringBefore('|')
        val variantKey = value.substringAfter('|')
        if (variantKey.isEmpty()) {
            LocalVoiceSettings.setVendor(ctx, familyId)
            LocalVoiceSettings.setSelectedVariant(ctx, familyId, "")
            refresh(ctx)
            return false
        }
        val variant = ModelCatalog.byId(value)
        if (variant == null) {
            refresh(ctx)
            return false
        }
        if (ModelInstaller.isInstalled(ctx, variant)) {
            LocalVoiceSettings.setVendor(ctx, familyId)
            LocalVoiceSettings.setSelectedVariant(ctx, familyId, variantKey)
            refresh(ctx)
        } else {
            confirmDownload(ctx, variant) { refresh(ctx) }
        }
        return false
    }

    private fun showModelList(ctx: Context) {
        // 完整（已安装）的模型置顶。
        val variants = ModelCatalog.VARIANTS.sortedByDescending { ModelInstaller.isInstalled(ctx, it) }
        val installed = getString(R.string.local_asr_model_installed)
        val notInstalled = getString(R.string.local_asr_model_not_installed)
        val labels = variants.map { variant ->
            val state = if (ModelInstaller.isInstalled(ctx, variant)) installed else notInstalled
            "${variant.displayName}\n    ${variant.downloadMiB} MiB · $state"
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.local_asr_models)
            .setItems(labels) { _, which ->
                val variant = variants[which]
                if (ModelInstaller.isInstalled(ctx, variant)) {
                    showInstalledActions(ctx, variant)
                } else {
                    confirmDownload(ctx, variant) { refresh(ctx) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    }
                    1 -> copyPath(ctx, path)
                    else -> {
                        ModelInstaller.uninstall(ctx, variant)
                        toast(ctx, getString(R.string.local_asr_model_deleted))
                    }
                }
                refresh(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 点击「模型下载源」：只做一件事——切换下载源。地址查看统一走上面的手动下载入口。 */
    private fun showSourceDialog(ctx: Context) {
        val original = LocalVoiceSettings.downloadSource(ctx)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.local_asr_source))
            .setSingleChoiceItems(ModelCatalog.SOURCES.toTypedArray(), original) { _, which ->
                LocalVoiceSettings.setDownloadSource(ctx, which)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> refresh(ctx) }
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

        val items = engineItems(ctx)
        vendorPreference?.apply {
            entries = items.map { it.label }.toTypedArray()
            entryValues = items.map { it.value }.toTypedArray()
            val family = LocalVoiceSettings.vendor(ctx)
            val wanted = "$family|${LocalVoiceSettings.selectedVariant(ctx, family)}"
            value = if (items.any { it.value == wanted }) wanted else "$family|"
        }
        val family = LocalVoiceSettings.vendor(ctx)
        val threads = LocalVoiceSettings.threads(ctx, family).toString()
        threadsPreference?.value = if (threads in THREAD_VALUES) threads else "2"
        val keepAlive = LocalVoiceSettings.keepAliveMinutes(ctx, family).toString()
        keepAlivePreference?.value = if (keepAlive in KEEP_ALIVE_VALUES) keepAlive else "-1"
        val source = LocalVoiceSettings.downloadSource(ctx)
        sourcePreference?.summary = ModelCatalog.SOURCES[source] + "\n点击切换下载源"
        itnPreference?.isChecked = LocalVoiceSettings.isItn(ctx, family)
        val installedCount = ModelCatalog.VARIANTS.count { ModelInstaller.isInstalled(ctx, it) }
        modelsPreference?.summary = getString(R.string.local_asr_models_summary) +
            "\n已安装 $installedCount / ${ModelCatalog.VARIANTS.size} 个模型"
    }

    private fun buildModelSummary(ctx: Context): String {
        val familyId = LocalVoiceSettings.vendor(ctx)
        val family = VoiceModels.family(familyId) ?: VoiceModels.FAMILIES.first()
        val selected = LocalVoiceSettings.selectedVariant(ctx, familyId)
        val status = runCatching { VoiceModels.status(ctx, familyId) }.getOrNull()
        return buildString {
            if (status == null) {
                append(getString(R.string.local_asr_model_unknown))
                return@buildString
            }
            append(getString(R.string.local_asr_model_root, displayPath(ctx, status.root.absolutePath)))
            append('\n')
            append(getString(R.string.local_asr_tap_copy_path))
            append('\n')
            append("voice 固定在本应用私有目录，不随「使用者资料夹」变化")
            append('\n')
            if (selected.isNotBlank()) append("当前模型：$familyId/$selected\n")
            if (status.ready) {
                append(getString(R.string.local_asr_model_ready))
                status.resolved?.dir?.let { append("\n检测到模型：${displayPath(ctx, it.absolutePath)}") }
            } else {
                append(getString(R.string.local_asr_model_missing))
                append("\n需要文件：${family.groups.joinToString("、") { it.first() }}")
                if (status.missing.isNotEmpty()) append("\n尚缺：${status.missing.joinToString("、")}")
                append("\n官方模型包：${family.packageName}")
            }
        }
    }

    private companion object {
        const val REQUEST_MIC = 1001
        const val KEY_ENABLED = "lv_enabled"
        const val KEY_VENDOR = "lv_vendor"
        const val KEY_PERMISSION = "lv_permission"
        const val KEY_STATUS = "lv_status"
        const val KEY_CATEGORY_MODELS = "lv_category_models"
        const val KEY_MODELS = "lv_models"
        const val KEY_IMPORT_ZIP = "lv_import_zip"
        const val KEY_MANUAL_DOWNLOAD = "lv_manual_download"
        const val KEY_SOURCE = "lv_source"
        const val KEY_CATEGORY_RECOG = "lv_category_recog"
        const val KEY_THREADS = "lv_threads"
        const val KEY_ITN = "lv_itn"
        const val KEY_KEEP_ALIVE = "lv_keep_alive"
        const val KEY_SAVE_RECORDING = "lv_save_recording"
        const val KEY_USAGE = "lv_usage"
        val THREAD_VALUES = arrayOf("1", "2", "3", "4", "6", "8")
        val KEEP_ALIVE_VALUES = arrayOf("-1", "0", "5", "15", "30")
        val KEEP_ALIVE_LABELS = arrayOf(
            "常驻内存（最快，但一直占用模型大小的内存）",
            "用完即卸载（最省内存，每次需重新加载）",
            "空闲 5 分钟后卸载",
            "空闲 15 分钟后卸载",
            "空闲 30 分钟后卸载",
        )
    }
}
