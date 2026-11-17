/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.app.Dialog
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeConfig
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.handwriting.HandwritingSettings
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.handwriting.HandwritingWindow
import com.osfans.trime.ime.keyboard.KeyboardLayoutMode
import com.osfans.trime.ime.keyboard.KeyboardLayoutState
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

class SwitchOptionWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    /** 每次打开/切换后重建，让两个布局入口的标签带上当前状态。 */
    private fun buildStaticEntries(): Array<SwitchOptionEntry> {
        val mode = KeyboardLayoutState.mode()
        val oneHandLabel =
            when (mode) {
                KeyboardLayoutMode.ONE_HAND_LEFT ->
                    context.getString(R.string.one_hand_keyboard_mode) +
                        " · " + context.getString(R.string.one_hand_left)
                KeyboardLayoutMode.ONE_HAND_RIGHT ->
                    context.getString(R.string.one_hand_keyboard_mode) +
                        " · " + context.getString(R.string.one_hand_right)
                else -> context.getString(R.string.one_hand_keyboard_mode)
            }
        val floatingLabel =
            if (mode == KeyboardLayoutMode.FLOATING) {
                context.getString(R.string.floating_keyboard) +
                    " · " + context.getString(R.string.keyboard_layout_on)
            } else {
                context.getString(R.string.floating_keyboard)
            }
        // 与「悬浮键盘 · 已开启」一致：状态反映「当前是否在手写面板」，
        // 而不是设置页的功能总开关。这样在键盘里显示「已关闭」，进手写后显示「已开启」，
        // 每次打开菜单都会按来源窗口重新计算，不会出现永远只显示「已开启」。
        val handwritingOn = HandwritingSettings.isEnabled(context) && handwritingActive
        val handwritingLabel =
            context.getString(R.string.handwriting_settings) +
                " · " + context.getString(
                    if (handwritingOn) R.string.keyboard_layout_on else R.string.keyboard_layout_off,
                )
        // 与咩版一致：最前两项固定为「键盘样式」「方案」，模式切换放在其后。
        val entries = mutableListOf<SwitchOptionEntry>(
            SwitchOptionEntry.Static(
                context.getString(R.string.keyboard_style),
                R.drawable.ic_baseline_color_lens_24,
                SwitchOptionEntry.Static.Type.ThemeList,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.schemata),
                R.drawable.ic_round_view_list_24,
                SwitchOptionEntry.Static.Type.SchemaList,
            ),
            SwitchOptionEntry.Static(
                floatingLabel,
                R.drawable.ic_baseline_floating_keyboard_24,
                SwitchOptionEntry.Static.Type.FloatingKeyboard,
            ),
            SwitchOptionEntry.Static(
                oneHandLabel,
                R.drawable.ic_baseline_one_hand_mode_24,
                SwitchOptionEntry.Static.Type.OneHandMode,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.update_config),
                R.drawable.ic_baseline_sync_24,
                SwitchOptionEntry.Static.Type.UpdateConfig,
            ),
            SwitchOptionEntry.Static(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                SwitchOptionEntry.Static.Type.Keyboard,
            ),
        )
        // 手写输入在设置里关闭时不在「更多」菜单显示这一格（只有开启才提示）。
        // 例外：已经在手写面板里时仍保留，方便点它退回普通键盘。
        // icon 传 0 时宫格用「手」字作图标，无需新增 drawable。
        if (HandwritingSettings.isEnabled(context) || handwritingActive) {
            entries += SwitchOptionEntry.Static(
                handwritingLabel,
                0,
                SwitchOptionEntry.Static.Type.Handwriting,
            )
        }
        // 单手模式下才出现「键盘宽度」三档，避免普通 / 悬浮模式的宫格被无用项占满。
        if (mode.isOneHand) {
            val current = KeyboardLayoutState.oneHandWidthPercent(context)
            val currentLabel =
                when {
                    current <= KeyboardLayoutState.ONE_HAND_WIDTH_NARROW ->
                        context.getString(R.string.one_hand_width_narrow)
                    current >= KeyboardLayoutState.ONE_HAND_WIDTH_WIDE ->
                        context.getString(R.string.one_hand_width_wide)
                    else -> context.getString(R.string.one_hand_width_standard)
                }
            entries += SwitchOptionEntry.Static(
                context.getString(R.string.one_hand_width) + " · " +
                    context.getString(R.string.one_hand_width_current, currentLabel),
                R.drawable.ic_baseline_one_hand_mode_24,
                SwitchOptionEntry.Static.Type.OneHandWidth,
            )
        }
        return entries.toTypedArray()
    }

    var popupMenu: PopupMenu? = null

    private val windowManager: BoardWindowManager by instance()

    /**
     * 菜单打开时记住「进来之前是不是手写面板」。
     * BoardWindowManager 在 onAttached() 之后才把 currentWindow 换成菜单，
     * 所以那一刻读到的 topWindow 仍是来源窗口；每次打开菜单都重新快照，状态不会丢。
     */
    private var handwritingActive = false

    /** 切换后回到键盘，让用户直接看到效果而不是停留在菜单页。 */
    private fun returnToKeyboard() {
        windowManager.attachWindow(KeyboardWindow)
    }

    private val saveOptions by lazy {
        RimeConfig.openConfig("default").use {
            it.getList("switcher/save_options", RimeConfig::getString).toSet()
        }
    }

    private suspend fun RimeApi.applyOption(option: String, value: Boolean) {
        setRuntimeOption(option, value)
        if (option in saveOptions) {
            RimeConfig.openUserConfig("user").use {
                it.setBool("var/option/$option", value)
            }
        }
    }

    private fun showDialog(builder: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(builder(api))
            }
        }
    }

    private val adapter: SwitchOptionAdapter by lazy {
        object : SwitchOptionAdapter() {
            override val scope: ThemeScope = this@SwitchOptionWindow.scope

            override fun onItemClick(
                view: View,
                entry: SwitchOptionEntry,
            ) {
                when (entry) {
                    is SwitchOptionEntry.Static -> when (entry.type) {
                        // 与「悬浮键盘」一样是窗口开关：点一下进手写面板，已在手写时再点回键盘。
                        // 不再改动设置页的功能总开关 —— 之前默认「已开启」时点一下会把手写关掉并退回键盘。
                        SwitchOptionEntry.Static.Type.Handwriting -> {
                            if (!HandwritingSettings.isEnabled(context)) {
                                Toast
                                    .makeText(
                                        service,
                                        "手写输入已在设置里关闭（设置 → 手写输入 可开启）",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            } else if (handwritingActive) {
                                windowManager.attachWindow(KeyboardWindow)
                            } else {
                                windowManager.attachWindow(HandwritingWindow(di))
                            }
                        }

                        SwitchOptionEntry.Static.Type.SchemaList -> showDialog { r ->
                            EnabledSchemaPickerDialog.build(r, service.lifecycleScope, context) {
                                setNegativeButton(R.string.enable_schemata) { _, _ ->
                                    AppUtils.launchMainToSchemaList(context)
                                }
                            }
                        }

                        SwitchOptionEntry.Static.Type.UpdateConfig -> rime.launchOnReady { r ->
                            r.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }

                        SwitchOptionEntry.Static.Type.Keyboard -> AppUtils.launchMainToKeyboard(context)

                        SwitchOptionEntry.Static.Type.ThemeList -> showDialog { r ->
                            ThemePickerDialog.build(service.lifecycleScope, context) {
                                r.commitComposition()
                            }
                        }

                        SwitchOptionEntry.Static.Type.OneHandMode -> {
                            val popup = PopupMenu(context, view)
                            val labels =
                                arrayOf(
                                    context.getString(R.string.keyboard_layout_normal),
                                    context.getString(R.string.one_hand_left),
                                    context.getString(R.string.one_hand_right),
                                )
                            val modes =
                                arrayOf(
                                    KeyboardLayoutMode.NORMAL,
                                    KeyboardLayoutMode.ONE_HAND_LEFT,
                                    KeyboardLayoutMode.ONE_HAND_RIGHT,
                                )
                            labels.forEachIndexed { i, label ->
                                popup.menu.add(0, i, i, label).setOnMenuItemClickListener {
                                    KeyboardLayoutState.setMode(service, modes[i])
                                    Toast.makeText(service, label, Toast.LENGTH_SHORT).show()
                                    refreshEntries()
                                    returnToKeyboard()
                                    true
                                }
                            }
                            popupMenu?.dismiss()
                            popupMenu = popup
                            popup.show()
                        }

                        SwitchOptionEntry.Static.Type.OneHandWidth -> {
                            val popup = PopupMenu(context, view)
                            val labels =
                                arrayOf(
                                    context.getString(R.string.one_hand_width_narrow),
                                    context.getString(R.string.one_hand_width_standard),
                                    context.getString(R.string.one_hand_width_wide),
                                )
                            val percents =
                                intArrayOf(
                                    KeyboardLayoutState.ONE_HAND_WIDTH_NARROW,
                                    KeyboardLayoutState.ONE_HAND_WIDTH_STANDARD,
                                    KeyboardLayoutState.ONE_HAND_WIDTH_WIDE,
                                )
                            labels.forEachIndexed { i, label ->
                                popup.menu.add(0, i, i, label).setOnMenuItemClickListener {
                                    KeyboardLayoutState.setOneHandWidthPercent(service, percents[i])
                                    Toast.makeText(service, label, Toast.LENGTH_SHORT).show()
                                    refreshEntries()
                                    returnToKeyboard()
                                    true
                                }
                            }
                            popupMenu?.dismiss()
                            popupMenu = popup
                            popup.show()
                        }

                        SwitchOptionEntry.Static.Type.FloatingKeyboard -> {
                            val next =
                                if (KeyboardLayoutState.mode() == KeyboardLayoutMode.FLOATING) {
                                    KeyboardLayoutMode.NORMAL
                                } else {
                                    KeyboardLayoutMode.FLOATING
                                }
                            KeyboardLayoutState.setMode(service, next)
                            Toast.makeText(
                                service,
                                if (next == KeyboardLayoutMode.FLOATING) {
                                    R.string.floating_keyboard_tip
                                } else {
                                    R.string.floating_keyboard_off
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                            refreshEntries()
                            returnToKeyboard()
                        }
                    }

                    is SwitchOptionEntry.Custom -> {
                        val options = entry.switch.options
                        if (options.isEmpty()) {
                            rime.launchOnReady {
                                val oldValue = it.getRuntimeOption(entry.switch.name)
                                it.applyOption(entry.switch.name, !oldValue)
                            }
                        } else {
                            val popup = PopupMenu(context, view)
                            val menu = popup.menu
                            entry.switch.states.forEachIndexed { i, state ->
                                menu.add(0, 0, 0, state).apply {
                                    setOnMenuItemClickListener {
                                        rime.launchOnReady {
                                            options.forEachIndexed { j, option ->
                                                it.applyOption(option, i == j)
                                            }
                                        }
                                        true
                                    }
                                }
                            }
                            popupMenu?.dismiss()
                            popupMenu = popup
                            popup.show()
                        }
                    }
                }
            }
        }
    }

    val view by lazy {
        context.recyclerView {
            layoutManager = gridLayoutManager(4)
            adapter = this@SwitchOptionWindow.adapter
        }
    }

    private fun refreshEntries() = updateSchemaOptionEntries()

    private fun updateSchemaOptionEntries() {
        val switches = rime.run { schemaCached }.switches
        adapter.submitList(
            listOf(
                *buildStaticEntries(),
                *switches.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }.toTypedArray(),
            ),
        )
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        updateSchemaOptionEntries()
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        updateSchemaOptionEntries()
    }

    private var viewCreated = false

    override fun onCreateView(): View {
        viewCreated = true
        return view
    }

    override fun refreshColors() {
        if (!viewCreated) return
        (view.adapter as? SwitchOptionAdapter)?.refreshColors()
        settingsButton.refreshColors()
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, scope).apply {
            setOnClickListener { AppUtils.launchMainActivity(context) }
        }
    }

    private val barExternalView by lazy {
        context.constraintLayout {
            val size = dp(theme.style.run { candidateViewHeight + commentHeight })
            add(
                settingsButton,
                lParams(size, size) {
                    endOfParent()
                },
            )
        }
    }

    override fun onCreateBarView() = barExternalView

    override fun onAttached() {
        KeyboardLayoutState.attach(service)
        // attachWindow 先调 onAttached() 再更新 currentWindow，所以此刻 topWindow 仍是来源窗口：
        // 从手写面板进来 = 已开启，从键盘进来 = 已关闭。每次打开菜单都重算，状态不会丢。
        handwritingActive = windowManager.topWindow is HandwritingWindow
        rime.launchOnReady { api ->
            val data = api.currentSchema().switches
            service.lifecycleScope.launch {
                adapter.submitList(
                    listOf(
                        *buildStaticEntries(),
                        *data.mapNotNull { SwitchOptionEntry.fromSwitch(rime, it) }.toTypedArray(),
                    ),
                )
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    /**
     * 从手写面板打开「更多」菜单时，QuickBar 返回键应回到手写面板，而不是普通键盘。
     * handwritingActive 在 onAttached() 里按来源窗口快照，进来前是手写面板则为 true。
     */
    override fun onBackPressed(): Boolean {
        if (handwritingActive && HandwritingSettings.isEnabled(context)) {
            windowManager.attachWindow(HandwritingWindow(di))
            return true
        }
        return false
    }
}
