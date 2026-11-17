/*
 * 把 app 层的「使用者资料夹真实路径」同步给独立的 :handwriting 模块。
 *
 * handwriting 模块刻意不依赖 app 的 UserDataManager，因此这里做单向桥接：
 * 主进程算出 rime 资料夹的真实绝对路径（UserDataManager.externalRimeDirAsFile()），
 * 写进 HandwritingSettings.userDirPath，模块再据此把模型放到 <rime>/handwriting/。
 *
 * :hw_infer 子进程只读取这份 prefs，不参与计算，保证 native 按真实路径 fopen。
 * 未选择资料夹 / 未授权「所有文件访问」/ 未开启跟随时写空串，模块自动回退私有目录。
 */
package com.osfans.trime.ime.handwriting

import android.content.Context
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.handwriting.HandwritingSettings
import timber.log.Timber

object HandwritingStorageBridge {
    /**
     * 依据当前存储模式与权限，把可用的使用者资料夹路径写进 handwriting prefs。
     * @return 实际采用的路径（空串 = 回退应用私有目录）。
     */
    fun sync(context: Context): String {
        val ctx = context.applicationContext
        // 与 VoiceModels.externalVoiceRoot 的判定保持一致：
        // 显式开启「模型放用户目录」或全局 EXTERNAL_DIRECT 模式，都要有所有文件访问权限，
        // 且能解析出使用者资料夹的真实路径（native 才能 fopen）。
        val useUserDir = HandwritingSettings.useUserDataDir(ctx)
        val generalExternal = runCatching { UserDataManager.isExternalDirect() }.getOrDefault(false)
        if (!useUserDir && !generalExternal) {
            HandwritingSettings.setUserDirPath(ctx, "")
            return ""
        }
        val allFiles = runCatching { UserDataManager.hasAllFilesAccess() }.getOrDefault(false)
        val path =
            if (allFiles) {
                runCatching { UserDataManager.externalRimeDirAsFile()?.absolutePath }.getOrNull().orEmpty()
            } else {
                ""
            }
        HandwritingSettings.setUserDirPath(ctx, path)
        Timber.i("handwriting storage -> %s", path.ifEmpty { "private" })
        return path
    }
}
