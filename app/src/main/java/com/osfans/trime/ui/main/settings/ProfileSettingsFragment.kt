/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.core.data.WebDavPrefs
import com.osfans.trime.core.data.WebDavSync
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.common.withLoadingDialog
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.buildDocumentsProviderIntent
import com.osfans.trime.util.customFormatTimeInDefault
import com.osfans.trime.util.toast
import io.github.whiredplanck.storageaccess.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileSettingsFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val prefs = AppPrefs.defaultInstance().profile
    private var dataStorageMode by prefs.dataStorageMode
    private val externalRimeDir by prefs.externalRimeTreeUri
    private val backgroundSyncEnable = prefs.periodicBackgroundSync
    private val lastSyncTime by prefs.lastBackgroundSyncTime
    private val lastSyncStatus by prefs.lastBackgroundSyncStatus

    /** Set while the picker runs as part of switching the storage mode to external sync. */
    private var pendingExternalSyncSetup = false

    /** Set while the picker runs as part of switching the storage mode to external direct. */
    private var pendingExternalDirectSetup = false

    private val onBackgroundSyncEnable = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        editSyncIntervalPreference.isEnabled = v
    }

    private val onSyncIntervalChange =
        PreferenceDelegate.OnChangeListener<Int> { _, _ ->
            if (backgroundSyncEnable.getValue()) {
                viewModel.restartBackgroundSyncWork.value = true
            }
        }

    private val onDataPathChange = PreferenceDelegate.OnChangeListener<String> { _, _ ->
        updateDataPathSummary()
    }

    private val onStorageModeChange =
        PreferenceDelegate.OnChangeListener<DataStorageMode> { _, _ ->
            updateStorageModeUi()
        }

    private lateinit var dataStorageModePreference: ListPreference
    private lateinit var dataPathPreference: Preference

    private lateinit var editSyncIntervalPreference: EditTextIntPreference
    private var webDavStatusPreference: Preference? = null
    private var webDavSyncNowPreference: Preference? = null
    private var webDavRestorePreference: Preference? = null

    private val storageAccess = StorageAccess(this)

    /** 跳系统设置申请「所有文件访问权限」，用于 EXTERNAL_DIRECT 模式按真实路径直读资料夹。 */
    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateStorageModeUi()
            updateDataPathSummary()
            // 授予权限后资料夹变为可直接读写，重新部署以把配置落到外部资料夹。
            if (prefs.dataStorageMode.getValue() == DataStorageMode.EXTERNAL_DIRECT) {
                lifecycleScope.launch {
                    viewModel.rime.runOnReady { deploy() }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.periodicBackgroundSync.registerOnChangeListener(onBackgroundSyncEnable)
        prefs.periodicBackgroundSyncInterval.registerOnChangeListener(onSyncIntervalChange)
        prefs.externalRimeTreeUri.registerOnChangeListener(onDataPathChange)
        prefs.dataStorageMode.registerOnChangeListener(onStorageModeChange)
    }

    private fun provideDataPathSummary(): String? {
        if (externalRimeDir.isEmpty()) return null
        val dirUri = Uri.parse(externalRimeDir)
        val docId = DocumentsContract.getTreeDocumentId(dirUri)
        return if (docId.contains(':')) {
            val (volume, rel) = docId.split(':', limit = 2)
            when (volume) {
                "raw" -> rel
                "primary" -> "/$rel"
                else -> "/storage/$volume/$rel"
            }
        } else {
            docId
        }.removePrefix("/storage/emulated/0")
    }

    private fun updateDataPathSummary() {
        dataPathPreference.summary = provideDataPathSummary()
    }

    private fun updateStorageModeUi() {
        dataStorageModePreference.value = dataStorageMode.name
        dataPathPreference.isEnabled =
            dataStorageMode == DataStorageMode.EXTERNAL_SYNC ||
            dataStorageMode == DataStorageMode.EXTERNAL_DIRECT
    }

    private fun pickDataPath() {
        lifecycleScope.launch {
            val picked = storageAccess.pickDirectory()
            if (picked == null) {
                // Cancelling the picker never nags: the current folder stays as it is, and a
                // storage mode switch the user did not finish setting up is undone silently.
                if (pendingExternalSyncSetup) {
                    pendingExternalSyncSetup = false
                    cancelExternalSyncSetup()
                } else if (pendingExternalDirectSetup) {
                    pendingExternalDirectSetup = false
                    cancelExternalDirectSetup()
                }
                return@launch
            }
            val ctx = requireContext()
            val externalSyncSetup = pendingExternalSyncSetup
            val externalDirectSetup = pendingExternalDirectSetup
            pendingExternalSyncSetup = false
            pendingExternalDirectSetup = false
            lifecycleScope.launch {
                withLoadingDialog(ctx) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            UserDataManager.persistTreeUri(picked.uri)
                            viewModel.rime.runOnReady { deploy() }
                        }
                    }.onSuccess {
                        updateDataPathSummary()
                        ctx.toast(R.string.setup__data_path_imported)
                        // 直接使用外部文件夹：选完目录后还需「所有文件访问」权限才能按路径直读。
                        if (prefs.dataStorageMode.getValue() == DataStorageMode.EXTERNAL_DIRECT &&
                            !UserDataManager.hasAllFilesAccess()
                        ) {
                            requestAllFilesAccess()
                        }
                    }.onFailure {
                        if (externalSyncSetup || externalDirectSetup) {
                            fallbackToAppStorage()
                        } else {
                            // Keep the picked folder: it stays the user's choice, and a folder
                            // that keeps failing makes the deploy path fall back on its own.
                            updateDataPathSummary()
                            ctx.toast(R.string.setup__data_path_import_failed)
                        }
                    }
                }
            }
        }
    }

    private fun promptSelectAnotherDirectory() {
        AlertDialog
            .Builder(requireContext())
            .setMessage(R.string.select_another_directory_to_sync)
            .setPositiveButton(R.string.select_another_directory) { _, _ ->
                // The current folder stays in place until the user picks another one.
                pickDataPath()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptExternalSyncFolderSelection() {
        val ctx = requireContext()
        AlertDialog
            .Builder(ctx)
            .setMessage(R.string.external_sync_select_folder_message)
            .setPositiveButton(R.string.setup__select_data_path) { _, _ ->
                pendingExternalSyncSetup = true
                pickDataPath()
            }.setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelExternalSyncSetup()
            }.setOnCancelListener {
                cancelExternalSyncSetup()
            }.show()
    }

    /** Falls back to app-specific storage when the user gives up on choosing a folder. */
    private fun cancelExternalSyncSetup() {
        prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
        updateStorageModeUi()
    }

    /** Prompts the user to pick a folder for the "use external folder directly" mode. */
    private fun promptExternalDirectFolderSelection() {
        val ctx = requireContext()
        AlertDialog
            .Builder(ctx)
            .setMessage(R.string.external_direct_select_folder_message)
            .setPositiveButton(R.string.setup__select_data_path) { _, _ ->
                pendingExternalDirectSetup = true
                pickDataPath()
            }.setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelExternalDirectSetup()
            }.setOnCancelListener {
                cancelExternalDirectSetup()
            }.show()
    }

    /** Falls back to app-specific storage when the user gives up on the direct-folder setup. */
    private fun cancelExternalDirectSetup() {
        prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
        updateStorageModeUi()
    }

    /** 跳系统设置申请「所有文件访问权限」（EXTERNAL_DIRECT 模式按真实路径直读资料夹所必需）。 */
    private fun requestAllFilesAccess() {
        if (UserDataManager.hasAllFilesAccess()) return
        allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    private fun fallbackToAppStorage() {
        prefs.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
        updateStorageModeUi()
        updateDataPathSummary()
        AlertDialog
            .Builder(requireContext())
            .setMessage(R.string.external_sync_fallback_app_storage)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** WebDAV 配置项：用自己的 SharedPreferences 管理，避免与 Preference 内置存储不一致。 */
    private fun webDavTextPreference(
        ctx: Context,
        key: String,
        title: String,
        hint: String,
        password: Boolean = false,
        getter: (WebDavPrefs) -> String,
        setter: (WebDavPrefs, String) -> Unit,
    ): Preference {
        val prefs = WebDavPrefs(ctx.applicationContext)
        return Preference(ctx).apply {
            this.key = key
            this.title = title
            isIconSpaceReserved = false
            fun shown(): String = when {
                password && getter(prefs).isNotEmpty() -> "••••••（已设置）"
                getter(prefs).isBlank() -> "未设置"
                else -> getter(prefs)
            }
            summary = shown()
            setOnPreferenceClickListener {
                val pad = (20 * ctx.resources.displayMetrics.density).toInt()
                val input =
                    EditText(ctx).apply {
                        setText(getter(prefs))
                        setSelection(text.length)
                        this.hint = hint
                        setSingleLine(true)
                        if (password) {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                        setPadding(pad, pad / 2, pad, pad / 2)
                    }
                val box =
                    FrameLayout(ctx).apply {
                        setPadding(pad / 2, pad / 4, pad / 2, 0)
                        addView(
                            input,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
                AlertDialog
                    .Builder(ctx)
                    .setTitle(title)
                    .setView(box)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        setter(prefs, input.text.toString().trim())
                        summary = shown()
                        updateWebDavUi()
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }

    private fun updateWebDavUi() {
        val ctx = context ?: return
        val prefs = WebDavPrefs(ctx.applicationContext)
        webDavStatusPreference?.summary = buildWebDavStatus(ctx, prefs)
        val ready = prefs.isConfigured()
        webDavSyncNowPreference?.isEnabled = ready
        // 「从服务器恢复」是重装后的一次性入口，不受「启用 WebDAV 同步」开关限制：
        // 开关默认关闭时若把条目置灰，用户点它就会完全没反应。
        webDavRestorePreference?.isEnabled = true
    }

    private fun buildWebDavStatus(ctx: Context, prefs: WebDavPrefs): String = buildString {
        append("状态：").append(if (prefs.enabled) "已启用" else "未启用")
        append("\n配置：").append(if (prefs.isConfigured()) "已填写" else "未填写完整")
        val base = prefs.url.trim().trimEnd('/')
        append("\n远端：").append(base.ifBlank { "未设置" })
        val dir = prefs.remoteDir.trim().trim('/')
        if (dir.isNotBlank()) append("/").append(dir)
        if (prefs.lastSyncTime > 0L) {
            append("\n上次：").append(customFormatTimeInDefault("yyyy-MM-dd HH:mm", prefs.lastSyncTime))
            if (prefs.lastSyncResult.isNotBlank()) append("\n结果：").append(prefs.lastSyncResult)
        } else {
            append("\n上次：尚未同步")
        }
    }

    private fun startWebDavSync(ctx: Context, preferRemote: Boolean = false) {
        val prefs = WebDavPrefs(ctx.applicationContext)
        if (!prefs.isConfigured()) {
            AlertDialog
                .Builder(ctx)
                .setTitle(if (preferRemote) "无法从服务器恢复" else "无法同步")
                .setMessage(
                    "请先填好 WebDAV 的「服务器地址」「账号」「密码 / 应用专用密码」。\n\n" +
                        "手动同步与恢复不需要打开「启用 WebDAV 同步」开关，那个开关只管后台定时同步。",
                ).setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val shownUrl = buildString {
            append(prefs.url.trim().trimEnd('/')).append('/')
            val dir = prefs.remoteDir.trim().trim('/')
            if (dir.isNotEmpty()) append(dir).append('/')
        }
        val actionName = if (preferRemote) "从服务器恢复" else "WebDAV 同步"
        // 进度回调发生在 IO 线程，投递到主线程时同步可能已经结束；用标志位防止
        // 迟到的进度把最终状态栏覆盖回「进行中」。
        val syncing = java.util.concurrent.atomic.AtomicBoolean(true)
        // 点击后立刻给可见反馈：以前只有 200ms 后才出现的转圈，网络慢或下载 0 个时
        // 用户会以为点了没反应。
        ctx.toast("$actionName 开始…")
        webDavStatusPreference?.summary = "$actionName 中…\n远端：$shownUrl"
        lifecycleScope.launch {
            withLoadingDialog(ctx) {
                val outcome =
                    try {
                        Result.success(
                            WebDavSync.sync(
                                ctx,
                                onProgress = { message ->
                                    if (syncing.get()) {
                                        lifecycleScope.launch(Dispatchers.Main) {
                                            if (syncing.get()) {
                                                webDavStatusPreference?.summary =
                                                    "$actionName 中…\n$message\n远端：$shownUrl"
                                            }
                                        }
                                    }
                                },
                                preferRemote = preferRemote,
                            ),
                        )
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                outcome
                    .onSuccess { result ->
                        if (!result.succeeded) {
                            showWebDavFailures(ctx, result.failed, preferRemote)
                        } else if (preferRemote) {
                            // 成功也弹窗：Toast 一闪而过，重装后看不到结果就等于「没反应」。
                            // 重新部署改由用户点按钮触发，不再自动跑。
                            showWebDavRestoreResult(ctx, result) { triggerRedeploy(ctx) }
                        } else {
                            // 普通同步只重载配置，避免每次都全量编译词典。
                            // 不要用 syncUserData()：它会导出词典快照，多出 sync/<id>/ 文件夹。
                            runCatching { viewModel.rime.runOnReady { updateConfig() } }
                            ctx.toast("WebDAV 同步完成：${result.describe()}")
                        }
                    }.onFailure { showWebDavError(ctx, it, preferRemote) }
                syncing.set(false)
                updateWebDavUi()
            }
        }
    }

    /** 失败原因往往很长（含 HTTP 状态码与排查提示），Toast 会被截断，改用弹窗完整展示。 */
    private fun showWebDavError(ctx: Context, error: Throwable, restore: Boolean = false) {
        val reason = error.message ?: error.javaClass.simpleName
        AlertDialog
            .Builder(ctx)
            .setTitle(if (restore) "从服务器恢复失败" else "WebDAV 同步失败")
            .setMessage(
                "$reason\n\n排查建议：\n" +
                    "· 坚果云地址为 https://dav.jianguoyun.com/dav\n" +
                    "· 账号用邮箱，密码用网页端生成的「应用密码」，不是登录密码\n" +
                    "· 远端目录留空不会新建文件夹，直接使用上面的地址；填 trime 才会在服务器新建 trime 目录\n" +
                    "· 备份若存放在服务器的某个子目录里，「远端目录」要填那个目录名，否则会读不到文件\n" +
                    "· 手动同步 / 恢复不需要打开「启用 WebDAV 同步」开关\n" +
                    "· 若提示 401/403，检查账号、应用密码与该目录权限\n" +
                    "· 若提示 Unable to resolve host / No address associated，是手机 DNS 解析不到服务器，先确认能上网（切换 Wi-Fi / 移动数据，或开关一次飞行模式刷新 DNS）再重试",
            ).setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 全量部署会跑 `UserDataManager.sync()`，在「使用者资料夹 / 外部同步」模式下会拿
     * 外部目录与本地做双向对齐，可能把刚恢复的文件覆盖或删掉 —— 这是「同步成功但
     * 少了一部分文件」的一个来源。改成用户在结果弹窗里主动点按钮才执行。
     */
    private fun triggerRedeploy(ctx: Context) {
        ctx.toast("正在重新部署…")
        lifecycleScope.launch {
            runCatching { viewModel.rime.runOnReady { deploy() } }
                .onFailure { ctx.toast("部署失败：${it.message}") }
        }
    }

    /** 恢复结论 + 核对清单；弹窗显示前 20 条，「复制核对清单」输出全量。 */
    private fun buildRestoreReport(result: WebDavSync.Result, full: Boolean = false): String {
        val limit = if (full) Int.MAX_VALUE else 20

        fun appendPaths(sb: StringBuilder, title: String, paths: List<String>) {
            if (paths.isEmpty()) return
            sb.append(title).append(" ").append(paths.size).appendLine(" 个：")
            paths.take(limit).forEach { sb.append("· ").appendLine(it) }
            if (paths.size > limit) {
                sb.append("…还有 ").append(paths.size - limit)
                    .appendLine(" 个（点「复制核对清单」查看全部）")
            }
            sb.appendLine()
        }

        return buildString {
            append("远端地址：").appendLine(result.remoteRootUrl)
            append("PROPFIND 返回 response：").append(result.remoteEntries).appendLine(" 个")
            append("解析出有效条目：").append(result.parsedEntries).appendLine(" 个")
            append("可恢复文件：").append(result.remoteFiles).appendLine(" 个")
            append("按规则跳过：").append(result.excludedRemote.size).appendLine(" 个")
            if (result.samplePaths.isNotEmpty()) {
                append("文件路径示例：").appendLine(result.samplePaths.joinToString("、"))
            }
            val firstSegments = result.samplePaths.map { it.substringBefore('/', "") }.distinct()
            if (result.samplePaths.size >= 2 && firstSegments.size == 1 && firstSegments[0].isNotEmpty()) {
                append("提示：文件都落在 ").append(firstSegments[0])
                    .appendLine("/ 子目录里；若这不是预期的层级，请把「远端目录」改成这个目录名再恢复一次。")
            }
            appendLine()
            append("下载覆盖：").append(result.downloaded).appendLine(" 个")
            append("本地独有保留（未上传）：").append(result.skipped).appendLine(" 个")
            if (result.failed.isNotEmpty()) {
                append("传输失败：").append(result.failed.size).appendLine(" 项")
            }
            appendPaths(this, "恢复后仍缺失", result.missingLocal)
            appendPaths(this, "被规则跳过的远端路径", result.excludedRemote)
            if (result.missingLocal.isNotEmpty()) {
                appendLine("有文件没恢复回来，请点「复制核对清单」把完整清单发出来，")
                appendLine("并留意这些路径是否含中文 / 空格 / 特殊符号。")
            } else if (result.remoteFiles > 0 && result.downloaded > 0) {
                appendLine("已扫描到的可恢复文件都已写入本地；若服务器实际文件更多，")
                appendLine("请对比上面的 response 数、解析条目数与文件路径示例。")
            }
            appendLine()
            append("配置需要重新部署才会生效。若正在使用「使用者资料夹 / 外部同步」，")
            append("重新部署会把本地与外部目录双向对齐，可能覆盖刚恢复的文件，建议先确认外部目录内容。")
        }
    }

    /** 恢复必须给看得见的结论，并逐项核对远端文件是否真的落盘。 */
    private fun showWebDavRestoreResult(
        ctx: Context,
        result: WebDavSync.Result,
        onRedeploy: () -> Unit,
    ) {
        if (result.remoteFiles == 0) {
            val items = result.remoteTopLevel
            val hint = if (items.isEmpty()) {
                "服务器在该地址下没有返回任何内容，请确认地址、账号和「远端目录」。"
            } else {
                "服务器该地址下的一级内容：\n" + items.take(12).joinToString("、") +
                    (if (items.size > 12) " 等 ${items.size} 项" else "") +
                    "\n\n如果备份在某个子目录里（例如 trime/），请把「远端目录」改成那个目录名，再点一次恢复。"
            }
            AlertDialog
                .Builder(ctx)
                .setTitle("服务器上没有找到可恢复的文件")
                .setMessage(
                    "当前读取的远端地址：\n${result.remoteRootUrl}\n\n" +
                        "$hint\n\n本次下载 0 个文件，本地配置没有变化。",
                ).setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val builder =
            AlertDialog
                .Builder(ctx)
                .setTitle(
                    when {
                        result.missingLocal.isNotEmpty() -> "从服务器恢复不完整"
                        result.downloaded > 0 -> "从服务器恢复完成"
                        else -> "从服务器恢复结束（无文件更新）"
                    },
                ).setMessage(buildRestoreReport(result))
                .setNeutralButton("复制核对清单") { _, _ ->
                    val clipboard =
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "WebDAV 恢复核对",
                            buildRestoreReport(result, full = true),
                        ),
                    )
                    ctx.toast("核对清单已复制")
                }.setNegativeButton("关闭", null)
        if (result.downloaded > 0) {
            builder.setPositiveButton("重新部署并关闭") { _, _ -> onRedeploy() }
        }
        builder.show()
    }

    /** 设备侧 DNS / 连接异常会把每个文件都刷成同一条错误，聚合展示才看得清根因。 */
    private fun isNetworkFailure(line: String): Boolean {
        val msg = line.lowercase()
        return "unable to resolve host" in msg ||
            "no address associated" in msg ||
            "unknownhostexception" in msg ||
            "network is unreachable" in msg ||
            "failed to connect" in msg ||
            "timed out" in msg ||
            "sockettimeout" in msg ||
            "connection reset" in msg
    }

    private fun showWebDavFailures(
        ctx: Context,
        failures: List<String>,
        restore: Boolean = false,
    ) {
        val prefix = if (restore) "从服务器恢复" else "WebDAV 同步"
        val network = failures.filter { isNetworkFailure(it) }
        val others = failures.filterNot { isNetworkFailure(it) }
        val message = buildString {
            if (network.isNotEmpty()) {
                append("【网络异常】${network.size} 项未能传输：设备解析不到 WebDAV 服务器域名或连接超时，本次同步没有真正完成。\n\n")
                append("请确认手机能上网（切换 Wi-Fi / 移动数据，或开关一次飞行模式刷新 DNS），再点「")
                    .append(if (restore) "从服务器恢复" else "立即同步")
                    .append("」重试。\n\n")
                append("原始错误：").appendLine(network.first().substringAfter("："))
                append("受影响文件：")
                append(network.take(8).joinToString("、") { it.substringBefore("：") })
                if (network.size > 8) append(" 等 ${network.size} 项")
                appendLine()
            }
            if (others.isNotEmpty()) {
                if (network.isNotEmpty()) appendLine()
                appendLine("其它失败 ${others.size} 项：")
                append(others.take(10).joinToString("\n"))
                if (others.size > 10) append("\n…共 ${others.size} 项")
            }
        }
        AlertDialog
            .Builder(ctx)
            .setTitle(
                if (network.isNotEmpty() && others.isEmpty()) {
                    "$prefix 失败：网络异常"
                } else {
                    "$prefix 完成（部分失败）"
                },
            ).setMessage(message.trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.storage) {
                isIconSpaceReserved = false
                val storageModes = DataStorageMode.entries
                addPreference(
                    ListPreference(ctx).apply {
                        dataStorageModePreference = this
                        key = AppPrefs.Profile.DATA_STORAGE_MODE
                        isIconSpaceReserved = false
                        setTitle(R.string.data_storage_mode)
                        entries = storageModes.map { getString(it.stringRes) }.toTypedArray()
                        entryValues = storageModes.map { it.name }.toTypedArray()
                        value = dataStorageMode.name
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        setOnPreferenceChangeListener { _, newValue ->
                            val oldMode = prefs.dataStorageMode.getValue()
                            val mode =
                                DataStorageMode.valueOf(newValue as String)
                            prefs.dataStorageMode.setValue(mode)
                            when {
                                mode == DataStorageMode.EXTERNAL_DIRECT -> {
                                    // 需要选过资料夹 + 所有文件权限；缺哪个补哪个。
                                    if (externalRimeDir.isEmpty()) {
                                        promptExternalDirectFolderSelection()
                                    } else if (!UserDataManager.hasAllFilesAccess()) {
                                        requestAllFilesAccess()
                                    }
                                }
                                oldMode == DataStorageMode.APP_STORAGE &&
                                    mode == DataStorageMode.EXTERNAL_SYNC &&
                                    !UserDataManager.hasExternalAccess() -> {
                                    promptExternalSyncFolderSelection()
                                }
                                oldMode == DataStorageMode.EXTERNAL_SYNC &&
                                    mode == DataStorageMode.APP_STORAGE -> {
                                    // The picked folder and its grant are kept, so switching back to
                                    // external sync does not ask the user to pick it again.
                                    updateDataPathSummary()
                                }
                            }
                            true
                        }
                    },
                )
                addPreference(
                    Preference(ctx).apply {
                        dataPathPreference = this
                        key = AppPrefs.Profile.EXTERNAL_RIME_TREE_URI
                        isIconSpaceReserved = false
                        setTitle(R.string.user_data_dir)
                        summary = provideDataPathSummary()
                        setOnPreferenceClickListener {
                            promptSelectAnotherDirectory()
                            true
                        }
                    },
                )
            }
            addCategory(R.string.synchronization) {
                isIconSpaceReserved = false
                addPreference(R.string.sync_user_data_immediately) {
                    lifecycleScope.launch {
                        withLoadingDialog(ctx) {
                            runCatching {
                                viewModel.rime.runOnReady { syncUserData() }
                            }.onSuccess { success ->
                                ctx.toast(
                                    when {
                                        !success -> R.string.sync_user_data_failure

                                        UserDataManager.useExternalSync() ->
                                            R.string.sync_user_data_success_external

                                        else -> R.string.sync_user_data_success
                                    },
                                )
                            }.onFailure {
                                ctx.toast(R.string.sync_user_data_failure)
                            }
                        }
                    }
                }
                addPreference(
                    SwitchPreferenceCompat(ctx).apply {
                        key = AppPrefs.Profile.PERIODIC_BACKGROUND_SYNC
                        isIconSpaceReserved = false
                        setTitle(R.string.periodic_background_sync)
                        setDefaultValue(false)
                        summaryProvider = Preference.SummaryProvider<SwitchPreferenceCompat> {
                            if (backgroundSyncEnable.getValue()) {
                                val lastTime: String
                                val lastStatus: String
                                if (lastSyncTime != 0L) {
                                    lastTime = customFormatTimeInDefault("yyyy-MM-dd HH:mm", lastSyncTime)
                                    lastStatus = getString(if (lastSyncStatus) R.string.success else R.string.failure)
                                } else {
                                    lastTime = "N/A"
                                    lastStatus = "N/A"
                                }
                                getString(
                                    R.string.periodic_background_sync_status,
                                    lastTime,
                                    lastStatus,
                                )
                            } else {
                                ""
                            }
                        }
                    },
                )
                addPreference(
                    EditTextIntPreference(ctx).apply {
                        editSyncIntervalPreference = this
                        key = AppPrefs.Profile.PERIODIC_BACKGROUND_SYNC_INTERVAL
                        isIconSpaceReserved = false
                        setTitle(R.string.periodic_background_sync_interval)
                        min = 15
                        setDefaultValue(30)
                        summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                        isEnabled = backgroundSyncEnable.getValue()
                    },
                )
            }
            addPreference(
                PreferenceCategory(ctx).apply {
                    key = "webdav_category"
                    title = "WebDAV 同步"
                    isIconSpaceReserved = false
                },
            )
            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = "webdav_enabled"
                    title = "启用 WebDAV 同步"
                    summary = "只控制后台定时同步；不含 voice 模型。手动「立即同步 / 从服务器恢复」不需要打开此开关"
                    isIconSpaceReserved = false
                    isChecked = WebDavPrefs(ctx).enabled
                    setOnPreferenceChangeListener { _, value ->
                        WebDavPrefs(ctx).enabled = value as Boolean
                        updateWebDavUi()
                        true
                    }
                },
            )
            addPreference(
                webDavTextPreference(
                    ctx,
                    key = "webdav_url",
                    title = "服务器地址",
                    hint = "https://dav.jianguoyun.com/dav（坚果云）",
                    getter = { it.url },
                    setter = { prefs, value -> prefs.url = value },
                ),
            )
            addPreference(
                webDavTextPreference(
                    ctx,
                    key = "webdav_username",
                    title = "账号",
                    hint = "WebDAV 登录名",
                    getter = { it.username },
                    setter = { prefs, value -> prefs.username = value },
                ),
            )
            addPreference(
                webDavTextPreference(
                    ctx,
                    key = "webdav_password",
                    title = "密码 / 应用专用密码",
                    hint = "坚果云需在网页端生成应用密码，不是登录密码",
                    password = true,
                    getter = { it.password },
                    setter = { prefs, value -> prefs.password = value },
                ),
            )
            addPreference(
                webDavTextPreference(
                    ctx,
                    key = "webdav_remote_dir",
                    title = "远端目录",
                    hint = "留空＝不新建文件夹，直接用服务器地址；填 trime 会新建 trime 子目录",
                    getter = { it.remoteDir },
                    setter = { prefs, value -> prefs.remoteDir = value },
                ),
            )
            addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = "webdav_wifi_only"
                    title = "仅在 Wi-Fi 下同步"
                    summary = "开启后移动数据网络会跳过 WebDAV 同步"
                    isIconSpaceReserved = false
                    isChecked = WebDavPrefs(ctx).wifiOnly
                    setOnPreferenceChangeListener { _, value ->
                        WebDavPrefs(ctx).wifiOnly = value as Boolean
                        true
                    }
                },
            )
            addPreference(
                Preference(ctx).apply {
                    webDavSyncNowPreference = this
                    key = "webdav_sync_now"
                    title = "立即同步"
                    summary = "与服务器比对并同步 rime 用户目录（配置 / 方案 / 词库）"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        startWebDavSync(ctx)
                        true
                    }
                },
            )
            addPreference(
                Preference(ctx).apply {
                    webDavRestorePreference = this
                    key = "webdav_restore"
                    title = "从服务器恢复（覆盖本地同名文件）"
                    summary = "重新安装 / 本地配置丢失时用：把远端全部文件下载并覆盖本地，不上传本机预设"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        AlertDialog
                            .Builder(ctx)
                            .setTitle("从服务器恢复")
                            .setMessage(
                                "将用远端 WebDAV 目录里的所有文件覆盖本地同名文件；本地独有文件不会删除，\n" +
                                    "也不会把本机重装后的预设文件上传到服务器。\n\n" +
                                    "不需要打开「启用 WebDAV 同步」开关；恢复完成后会自动重新部署配置。\n\n" +
                                    "适用于重新安装后恢复配置，确定继续？",
                            ).setPositiveButton(android.R.string.ok) { _, _ ->
                                startWebDavSync(ctx, preferRemote = true)
                            }.setNegativeButton(android.R.string.cancel, null)
                            .show()
                        true
                    }
                },
            )
            addPreference(
                Preference(ctx).apply {
                    webDavStatusPreference = this
                    key = "webdav_status"
                    title = "同步状态"
                    isIconSpaceReserved = false
                    isSelectable = false
                },
            )
            addCategory(R.string.maintenance) {
                isIconSpaceReserved = false
                addPreference(
                    title = getString(R.string.browse_app_data_dir),
                    summary = DataManager.userDataDir.absolutePath,
                ) {
                    runCatching {
                        ctx.startActivity(buildDocumentsProviderIntent())
                    }.onFailure {
                        ctx.toast(R.string.browse_app_data_dir_failed)
                    }
                }
                addPreference(R.string.reset, R.string.reset_hint) {
                    val items = ctx.assets.list("shared") ?: return@addPreference
                    val checked = BooleanArray(items.size) { false }
                    AlertDialog
                        .Builder(ctx)
                        .setTitle(R.string.reset)
                        .setMultiChoiceItems(items, checked) { _, id, isChecked ->
                            checked[id] = isChecked
                        }.setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            lifecycleScope.launch {
                                var res = true
                                withLoadingDialog(ctx) {
                                    withContext(Dispatchers.IO) {
                                        res =
                                            items
                                                .filterIndexed { index, _ -> checked[index] }
                                                .fold(true) { acc, asset ->
                                                    val destPath =
                                                        DataManager.sharedDataDir.resolve(asset).absolutePath
                                                    ResourceUtils
                                                        .copyFile("shared/$asset", destPath)
                                                        .fold({ acc and true }, { acc and false })
                                                }
                                    }
                                }
                                ctx.toast((if (res) R.string.reset_success else R.string.reset_failure))
                            }
                        }.show()
                }
            }
        }
        updateStorageModeUi()
        updateWebDavUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.periodicBackgroundSync.unregisterOnChangeListener(onBackgroundSyncEnable)
        prefs.periodicBackgroundSyncInterval.unregisterOnChangeListener(onSyncIntervalChange)
        prefs.externalRimeTreeUri.unregisterOnChangeListener(onDataPathChange)
        prefs.dataStorageMode.unregisterOnChangeListener(onStorageModeChange)
    }

    override fun onResume() {
        super.onResume()
        updateStorageModeUi()
        updateWebDavUi()
        val ctx = requireContext()
        if (UserDataManager.useExternalSync() && !UserDataManager.hasExternalAccess()) {
            ctx.toast(R.string.data_path_permission_revoked)
        }
    }
}
