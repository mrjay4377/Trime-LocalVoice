/*
 * WebDAV 同步配置：独立 SharedPreferences，不参与 Rime 部署，也不写进主题偏好。
 */
package com.osfans.trime.core.data

import android.content.Context

class WebDavPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 服务器根地址，例如 https://dav.example.com/remote.php/dav/files/alice/ */
    var url: String
        get() = sp.getString(KEY_URL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_URL, value.trim()).apply()

    var username: String
        get() = sp.getString(KEY_USERNAME, "").orEmpty()
        set(value) = sp.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = sp.getString(KEY_PASSWORD, "").orEmpty()
        set(value) = sp.edit().putString(KEY_PASSWORD, value).apply()

    /** 服务器上的子目录，例如 trime；留空表示使用地址本身作为根目录。 */
    var remoteDir: String
        get() = sp.getString(KEY_REMOTE_DIR, "trime").orEmpty()
        set(value) = sp.edit().putString(KEY_REMOTE_DIR, value.trim().trim('/')).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = sp.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var lastSyncTime: Long
        get() = sp.getLong(KEY_LAST_TIME, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_TIME, value).apply()

    var lastSyncResult: String
        get() = sp.getString(KEY_LAST_RESULT, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_RESULT, value).apply()

    fun isConfigured(): Boolean =
        url.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    private companion object {
        const val PREFS_NAME = "webdav_sync"
        const val KEY_ENABLED = "enabled"
        const val KEY_URL = "url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_REMOTE_DIR = "remote_dir"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_LAST_TIME = "last_time"
        const val KEY_LAST_RESULT = "last_result"
    }
}
