/*
 * 手写输入独立设置页：与「本地语音识别」完全分开。
 * 模型下载、目录、开关都走 :handwriting 模块自己的 API，不再触碰语音设置。
 *
 * 「候选提示码」列出 Rime 部署目录里全部 *.dict.yaml（排除随包 luna_pinyin），
 * default.custom.yaml 中启用的方案排在前面；用户选哪一份，手写候选旁就显示哪一份的编码。
 * 切换只在设置页进行；手写单字上屏后还会用这些字典的词组行给出联想词。
 */
package com.osfans.trime.ui.main.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.handwriting.HandwritingModels
import com.osfans.trime.handwriting.HandwritingSettings
import com.osfans.trime.ime.handwriting.HandwritingCodeLookup
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HandwritingSettingsFragment : PaddingPreferenceFragment() {
    private var modelPreference: Preference? = null
    private var pathPreference: Preference? = null
    private var codePreference: Preference? = null
    private var downloading = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen

        screen.addPreference(
            SwitchPreferenceCompat(ctx).apply {
                key = "hw_enabled"
                title = "启用手写输入"
                summary = "在「更多」菜单里显示「手写输入」；主题工具栏也可用 command: handwriting"
                isIconSpaceReserved = false
                isChecked = HandwritingSettings.isEnabled(ctx)
                setOnPreferenceChangeListener { _, value ->
                    HandwritingSettings.setEnabled(ctx, value as Boolean)
                    true
                }
            },
        )

        screen.addPreference(
            Preference(ctx).apply {
                key = "hw_code_source"
                title = "候选提示码"
                summary = "关闭 · 候选旁不显示编码"
                isIconSpaceReserved = false
                codePreference = this
                setOnPreferenceClickListener {
                    showCodeSourcePicker(ctx)
                    true
                }
            },
        )

        screen.addPreference(
            Preference(ctx).apply {
                key = "hw_model"
                title = "手写模型"
                isIconSpaceReserved = false
                modelPreference = this
                setOnPreferenceClickListener {
                    if (!downloading) {
                        if (HandwritingModels.isReady(ctx)) {
                            toast("手写模型已就绪")
                        } else {
                            downloadModel()
                        }
                    }
                    true
                }
            },
        )

        screen.addPreference(
            Preference(ctx).apply {
                key = "hw_path"
                title = "复制模型目录"
                isIconSpaceReserved = false
                pathPreference = this
                setOnPreferenceClickListener {
                    val path = HandwritingModels.dir(ctx).absolutePath
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("手写模型目录", path))
                    toast("已复制：$path")
                    true
                }
            },
        )

        screen.addPreference(
            Preference(ctx).apply {
                key = "hw_help"
                title = "如何使用"
                summary = "模型就绪后，键盘「更多」→「手写输入」打开面板；逐字书写或在同一区域连续叠写，点候选上屏。全程离线，模型为 MIT 协议的 ochwpro。"
                isIconSpaceReserved = false
            },
        )

        refresh(ctx)
    }

    /** 扫描部署目录放到 IO 线程；每次打开都刷新，用户重新部署方案后立刻能看到新方案。 */
    private fun showCodeSourcePicker(ctx: Context) {
        lifecycleScope.launch {
            val list =
                withContext(Dispatchers.IO) {
                    HandwritingCodeLookup.invalidate()
                    HandwritingCodeLookup.sources(ctx.applicationContext)
                }
            if (!isAdded) return@launch
            val current = HandwritingSettings.codeSource(ctx)
            // setSingleChoiceItems 要的是 Array<CharSequence>，Kotlin 数组不变，显式声明避免类型不匹配。
            val labels: Array<CharSequence> =
                buildList<CharSequence> {
                    add("关闭提示码")
                    list.forEach { add("${it.name}  ·  ${it.detail}") }
                }.toTypedArray()
            val checked =
                list.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it + 1 }
            AlertDialog
                .Builder(ctx)
                .setTitle("候选提示码来源（Rime 已部署字典）")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    val id =
                        if (which == 0) {
                            HandwritingCodeLookup.OFF
                        } else {
                            list.getOrNull(which - 1)?.id ?: HandwritingCodeLookup.OFF
                        }
                    HandwritingSettings.setCodeSource(ctx, id)
                    refresh(ctx)
                    dialog.dismiss()
                }.setNegativeButton("取消", null)
                .show()
        }
    }

    private fun downloadModel() {
        val app = requireContext().applicationContext
        downloading = true
        toast("正在下载手写模型（约 6.7 MB）…")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { HandwritingModels.download(app) }
            downloading = false
            if (isAdded) {
                toast(
                    if (ok) {
                        "手写模型下载完成"
                    } else {
                        "下载失败：请检查网络，或把 ochwpro.onnx 与 char_index.json 手动放到下面的目录"
                    },
                )
                refresh(requireContext())
            }
        }
    }

    private fun refresh(ctx: Context) {
        modelPreference?.summary =
            if (HandwritingModels.isReady(ctx)) {
                "已就绪 · ochwpro.onnx + char_index.json（MIT，可离线手写）"
            } else {
                "未安装 · 点击下载约 6.7 MB，或按下面路径手动放置"
            }
        pathPreference?.summary = HandwritingModels.dir(ctx).absolutePath
        val id = HandwritingSettings.codeSource(ctx)
        codePreference?.summary =
            if (id.isEmpty()) {
                "关闭 · 候选旁不显示编码"
            } else {
                "当前：$id（已部署字典，见设置页选择）"
            }
    }

    private fun toast(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() }
    }
}
