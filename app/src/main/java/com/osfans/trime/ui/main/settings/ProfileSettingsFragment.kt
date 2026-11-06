/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
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

    private val storageAccess = StorageAccess(this)

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
        dataPathPreference.isEnabled = dataStorageMode == DataStorageMode.EXTERNAL_SYNC
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
                }
                return@launch
            }
            val ctx = requireContext()
            val externalSyncSetup = pendingExternalSyncSetup
            pendingExternalSyncSetup = false
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
                    }.onFailure {
                        if (externalSyncSetup) {
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
        webDavSyncNowPreference?.isEnabled = prefs.enabled && prefs.isConfigured()
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

    private fun startWebDavSync(ctx: Context) {
        lifecycleScope.launch {
            withLoadingDialog(ctx) {
                val outcome =
                    try {
                        Result.success(WebDavSync.sync(ctx))
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                outcome
                    .onSuccess { result ->
                        if (result.succeeded) {
                            // 让 Rime 重新加载同步后的配置与用户词库
                            runCatching { viewModel.rime.runOnReady { syncUserData() } }
                            ctx.toast("WebDAV 同步完成：${result.describe()}")
                        } else {
                            showWebDavFailures(ctx, result.failed)
                        }
                    }.onFailure { showWebDavError(ctx, it) }
                updateWebDavUi()
            }
        }
    }

    /** 失败原因往往很长（含 HTTP 状态码与排查提示），Toast 会被截断，改用弹窗完整展示。 */
    private fun showWebDavError(ctx: Context, error: Throwable) {
        val reason = error.message ?: error.javaClass.simpleName
        AlertDialog
            .Builder(ctx)
            .setTitle("WebDAV 同步失败")
            .setMessage(
                "$reason\n\n排查建议：\n" +
                    "· 坚果云地址为 https://dav.jianguoyun.com/dav\n" +
                    "· 账号用邮箱，密码用网页端生成的「应用密码」，不是登录密码\n" +
                    "· 远端目录可先留空或填 trime，确认能同步后再改\n" +
                    "· 若提示 401/403，检查账号、应用密码与该目录权限",
            ).setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showWebDavFailures(ctx: Context, failures: List<String>) {
        AlertDialog
            .Builder(ctx)
            .setTitle("WebDAV 同步完成（部分失败）")
            .setMessage(
                failures.take(10).joinToString("\n") +
                    if (failures.size > 10) "\n…共 ${failures.size} 项" else "",
            ).setPositiveButton(android.R.string.ok, null)
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
                            if (
                                oldMode == DataStorageMode.APP_STORAGE &&
                                mode == DataStorageMode.EXTERNAL_SYNC &&
                                !UserDataManager.hasExternalAccess()
                            ) {
                                promptExternalSyncFolderSelection()
                            } else if (
                                oldMode == DataStorageMode.EXTERNAL_SYNC &&
                                mode == DataStorageMode.APP_STORAGE
                            ) {
                                // The picked folder and its grant are kept, so switching back to
                                // external sync does not ask the user to pick it again.
                                updateDataPathSummary()
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
                    summary = "把 rime 用户目录（配置 / 方案 / 词库）双向同步到服务器；不含 voice 模型"
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
                    hint = "trime",
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
