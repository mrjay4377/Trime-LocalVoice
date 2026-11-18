/*
 * 手写输入独立设置页：与「本地语音识别」完全分开。
 * 模型下载、目录、开关都走 :handwriting 模块自己的 API，不再触碰语音设置。
 *
 * 「候选提示码」列出 Rime 部署目录里已启用方案（default.custom.yaml 的 schema_list）
 * 引用的字典；用户选哪一份，手写候选旁就显示哪一份的编码。未启用任何方案时，
 * 弹窗只保留「关闭提示码」。切换只在设置页进行；手写单字上屏后还会用这些字典的词组行
 * 给出联想词（已过滤掉与已上屏字重复拼接的词组）。
 */
package com.osfans.trime.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.handwriting.HandwritingModels
import com.osfans.trime.handwriting.HandwritingSettings
import com.osfans.trime.ime.handwriting.HandwritingCodeLookup
import com.osfans.trime.ime.handwriting.HandwritingStorageBridge
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HandwritingSettingsFragment : PaddingPreferenceFragment() {
    private val mainViewModel: MainViewModel by activityViewModels()
    private var modelPreference: Preference? = null
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
            SwitchPreferenceCompat(ctx).apply {
                key = "hw_use_user_dir"
                title = "模型存放位置：用户目录"
                summary = "跟随使用者资料夹；关闭则固定应用私有目录"
                isIconSpaceReserved = false
                isChecked = HandwritingSettings.useUserDataDir(ctx)
                setOnPreferenceChangeListener { _, value ->
                    HandwritingSettings.setUseUserDataDir(ctx, value as Boolean)
                    // 立即按新开关重算落点并迁移已有模型，避免重新下载。
                    HandwritingStorageBridge.sync(ctx.applicationContext)
                    HandwritingModels.migrateLegacy(ctx.applicationContext)
                    refresh(ctx)
                    true
                }
            },
        )

        screen.addPreference(
            Preference(ctx).apply {
                key = "hw_help"
                title = "如何使用"
                summary = "模型就绪后，键盘「更多」→「手写输入」打开面板；逐字书写或叠写，点候选上屏。点击看完整说明与致谢开源"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    AlertDialog
                        .Builder(ctx)
                        .setTitle("手写输入 · 使用说明")
                        .setMessage(HW_USAGE)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    true
                }
            },
        )

        refresh(ctx)
    }

    /**
     * 首选 Rime 引擎的权威启用方案列表（enabledSchemata，含 id+中文名）；引擎拿不到时退回文件解析
     * （default.custom.yaml）。放 IO 线程扫部署目录；每次打开都刷新，重新部署后立即生效。
     */
    private fun showCodeSourcePicker(ctx: Context) {
        lifecycleScope.launch {
            // rime 未 ready 时 whenReady 会一直挂起；限时 1.5s，超时直接走内置方案，绝不卡住弹窗。
            val enabled =
                runCatching {
                    withTimeout(1500) {
                        mainViewModel.rime.runOnReady {
                            enabledSchemata().map { it.id to it.name }
                        }
                    }
                }.getOrNull()
            timber.log.Timber.tag("HwCode").w("enabledSchemata=%s", enabled)
            val list =
                withContext(Dispatchers.IO) {
                    HandwritingCodeLookup.invalidate()
                    if (enabled.isNullOrEmpty()) {
                        HandwritingCodeLookup.sources(ctx.applicationContext)
                    } else {
                        HandwritingCodeLookup.sources(ctx.applicationContext, enabled)
                    }
                }
            if (!isAdded) return@launch
            val current = HandwritingSettings.codeSource(ctx)
            // setSingleChoiceItems 要的是 Array<CharSequence>，Kotlin 数组不变，显式声明避免类型不匹配。
            val labels: Array<CharSequence> =
                buildList<CharSequence> {
                    add("关闭提示码")
                    list.forEach { add(it.name) }
                }.toTypedArray()
            // 兼容旧配置：current 可能是方案 id（新）或 dict 名（旧），两者都参与匹配。
            val checked =
                list.indexOfFirst { it.id == current || it.dictName == current }
                    .let { if (it < 0) 0 else it + 1 }
            AlertDialog
                .Builder(ctx)
                .setTitle("候选提示码")
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
        toast("正在下载手写模型（约 2.1 MB）…")
        lifecycleScope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    HandwritingStorageBridge.sync(app)
                    HandwritingModels.download(app)
                }
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
        // 按当前存储模式/权限刷新落点（使用者资料夹 ↔ 私有目录）。
        val appliedPath = HandwritingStorageBridge.sync(ctx.applicationContext)
        modelPreference?.summary =
            if (HandwritingModels.isReady(ctx)) {
                "已就绪 · ochwpro.onnx + char_index.json（MIT，可离线手写）"
            } else {
                "未安装 · 点击下载约 2.1 MB，或按下面路径手动放置"
            }
        // 显示「模型存放位置」开关的真实状态，权限/资料夹未就绪时给出可操作提示。
        findPreference<SwitchPreferenceCompat>("hw_use_user_dir")?.summary =
            when {
                !HandwritingSettings.useUserDataDir(ctx) -> "关闭 · 固定使用应用私有目录"
                appliedPath.isEmpty() -> "需在「设置 → 数据位置」选择使用者资料夹并授予「所有文件访问权限」，才会放到用户目录"
                else -> "当前：使用者资料夹（$appliedPath）"
            }
        val id = HandwritingSettings.codeSource(ctx)
        codePreference?.summary =
            if (id.isEmpty()) {
                "关闭 · 候选旁不显示编码"
            } else {
                "当前：$id（已启用方案的字典，候选旁显示其编码）"
            }
    }

    private fun toast(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /** 「如何使用」弹窗全文：用法 + 致谢与开源（与语音设置页风格一致）。 */
        private const val HW_USAGE =
            "模型就绪后，点键盘「更多」→「手写输入」打开面板；逐字书写，或在同一区域连续叠写多字，再点候选上屏。候选旁会按你选的 Rime 方案字典显示提示码，并据此给出词组联想。全程离线识别，不联网。\n\n" +
                "── 致谢与开源 ──\n" +
                "手写识别引擎采用 ochwpro（StrokeTransformer 模型，MIT 协议），叠写切分算法参考上游 stroke_segmenter.py；模型与字表由 XIME / CyIME 共用：\n" +
                "https://github.com/ximeiorg/ochwpro\n" +
                "https://github.com/Cyletix/CyIME\n\n" +
                "推理运行时使用 ONNX Runtime（Microsoft，MIT 协议）；模型为官方 INT8 动态量化版，经 ModelScope 分发：\n" +
                "https://github.com/microsoft/onnxruntime\n" +
                "https://www.modelscope.cn/models/bikeand/ochwpro\n\n" +
                "输入法内核基于同文输入法 osfans/trime（GPL-3.0）。本分支同样开源：\n" +
                "https://github.com/osfans/trime\n" +
                "https://github.com/mrjay4377/Trime-LocalVoice"
    }
}
