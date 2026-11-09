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
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
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
        // 与咩版一致：最前两项固定为「键盘样式」「方案」，模式切换放在其后。
        return arrayOf(
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
    }

    var popupMenu: PopupMenu? = null

    private val windowManager: BoardWindowManager by instance()

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
}
