/*
 * 手写输入的独立设置存储：不再借用语音模块的 SharedPreferences。
 * 旧版把手写开关存在 local_voice / handwriting_enabled，首次读取时迁移一次。
 */
package com.osfans.trime.handwriting

import android.content.Context

object HandwritingSettings {
    private const val PREFS_NAME = "handwriting"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CODE_SOURCE = "candidate_code_source"
    /** 模型是否放使用者资料夹；路径由 app 层写入（本模块不依赖 app 的 UserDataManager）。 */
    private const val KEY_USE_USER_DIR = "use_user_data_dir"
    private const val KEY_USER_DIR_PATH = "user_dir_path"
    private const val LEGACY_PREFS_NAME = "local_voice"
    private const val LEGACY_KEY_ENABLED = "handwriting_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (!p.contains(KEY_ENABLED)) {
            val legacy =
                context.applicationContext
                    .getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (legacy.contains(LEGACY_KEY_ENABLED)) {
                p.edit().putBoolean(KEY_ENABLED, legacy.getBoolean(LEGACY_KEY_ENABLED, true)).apply()
            }
        }
        return p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * 候选提示码来源：空串 = 关闭；否则为一份已部署 Rime 方案字典的 id
     * （见 HandwritingCodeLookup.sources）。设置页与手写面板「码」按钮共用。
     */
    fun codeSource(context: Context): String =
        prefs(context).getString(KEY_CODE_SOURCE, "") ?: ""

    fun setCodeSource(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CODE_SOURCE, value).apply()

    /**
     * 模型存放位置：true = 跟随使用者资料夹（native 需真实路径 + 所有文件访问权限）；
     * false = 固定应用私有目录。默认开，但只有 app 层写入了可用路径才会真正生效。
     */
    fun useUserDataDir(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_USER_DIR, true)

    fun setUseUserDataDir(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_USE_USER_DIR, value).apply()

    /** app 层写入的使用者资料夹真实绝对路径（rime 根目录）；空串表示未配置/未授权。 */
    fun userDirPath(context: Context): String =
        prefs(context).getString(KEY_USER_DIR_PATH, "") ?: ""

    /** 用 commit 落盘：:hw_infer 子进程要跨进程读同一份 prefs，apply 的异步写盘会有竞态。 */
    fun setUserDirPath(context: Context, path: String): Boolean =
        prefs(context).edit().putString(KEY_USER_DIR_PATH, path).commit()
}
