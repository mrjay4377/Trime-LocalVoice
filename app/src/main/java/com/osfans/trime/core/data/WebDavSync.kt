/*
 * WebDAV 双向同步：把 rime 用户目录（配置 / 方案 / 词库）与远端目录按修改时间对齐。
 *
 * 设计取舍：
 * - voice/ 里是几百 MB 的离线模型，绝不参与同步（与 UserDataManager 的过滤保持一致）；
 * - 不做删除传播：只在一侧缺失时把文件补到另一侧，避免误删配置或词库；
 * - 远端时间未知时保守跳过，不覆盖任何一方；
 * - 单个文件失败不中断整批，最后统一报告。
 */
package com.osfans.trime.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object WebDavSync {
    /** WebDAV 的 getlastmodified 精度是秒，本地文件系统可能更粗，留 2 秒容差。 */
    private const val TIME_TOLERANCE_MS = 2_000L
    private const val MAX_DEPTH = 8

    data class Result(
        val uploaded: Int,
        val downloaded: Int,
        val skipped: Int,
        val failed: List<String>,
    ) {
        val succeeded: Boolean get() = failed.isEmpty()

        fun describe(): String = buildString {
            append("上传 $uploaded · 下载 $downloaded · 跳过 $skipped")
            if (failed.isNotEmpty()) append(" · 失败 ${failed.size}")
        }
    }

    suspend fun sync(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val prefs = WebDavPrefs(context)
        require(prefs.enabled) { "WebDAV 同步未启用" }
        require(prefs.isConfigured()) { "请先填写 WebDAV 地址、账号和密码" }
        require(!prefs.wifiOnly || onWifi(context)) { "仅 Wi-Fi 下同步：当前不是 Wi-Fi 网络" }

        val client = WebDavClient(prefs.url, prefs.remoteDir, prefs.username, prefs.password)
        val root = UserDataManager.userDataDir
        require(root.isDirectory || root.mkdirs()) { "用户数据目录不可用" }

        onProgress("连接 WebDAV 服务器…")
        client.ensureRoot()
        val remote = listRemote(client)
        val local = listLocal(root)
        onProgress("比对 ${local.size} 个本地文件 / ${remote.size} 个远端文件…")

        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        val failed = mutableListOf<String>()
        val paths = (local.keys + remote.keys).distinct().sorted()

        for ((index, relative) in paths.withIndex()) {
            onProgress("(${index + 1}/${paths.size}) $relative")
            val localFile = local[relative]
            val remoteEntry = remote[relative]
            when {
                localFile != null && remoteEntry == null -> {
                    runCatching {
                        client.ensureParent(relative)
                        client.upload(localFile, relative)
                        uploaded++
                    }.onFailure { failed += "上传 $relative：${it.message}" }
                }
                localFile == null && remoteEntry != null -> {
                    runCatching {
                        val dest = File(root, relative)
                        client.download(relative, dest)
                        if (remoteEntry.lastModified > 0) dest.setLastModified(remoteEntry.lastModified)
                        downloaded++
                    }.onFailure { failed += "下载 $relative：${it.message}" }
                }
                localFile != null && remoteEntry != null -> {
                    val localTime = localFile.lastModified()
                    val remoteTime = remoteEntry.lastModified
                    when {
                        // 部分服务器不返回时间；宁可跳过，也不要凭空覆盖较新的一方。
                        remoteTime <= 0L -> skipped++
                        localTime > remoteTime + TIME_TOLERANCE_MS -> {
                            runCatching {
                                client.upload(localFile, relative)
                                uploaded++
                            }.onFailure { failed += "上传 $relative：${it.message}" }
                        }
                        remoteTime > localTime + TIME_TOLERANCE_MS -> {
                            runCatching {
                                client.download(relative, localFile)
                                localFile.setLastModified(remoteTime)
                                downloaded++
                            }.onFailure { failed += "下载 $relative：${it.message}" }
                        }
                        else -> skipped++
                    }
                }
            }
        }

        val result = Result(uploaded, downloaded, skipped, failed)
        prefs.lastSyncTime = System.currentTimeMillis()
        prefs.lastSyncResult = result.describe()
        result
    }

    private fun listRemote(client: WebDavClient): Map<String, WebDavClient.Entry> {
        val result = mutableMapOf<String, WebDavClient.Entry>()

        fun walk(prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (entry in client.list(prefix)) {
                val relative = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
                if (isExcluded(relative)) continue
                if (entry.isDirectory) {
                    walk(relative, depth + 1)
                } else {
                    result[relative] = entry
                }
            }
        }
        walk("", 0)
        return result
    }

    private fun listLocal(root: File): Map<String, File> {
        val result = mutableMapOf<String, File>()
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
            if (!isExcluded(relative)) result[relative] = file
        }
        return result
    }

    /** 与使用者资料夹同步保持一致的排除项，外加临时文件与同步元数据。 */
    private fun isExcluded(relative: String): Boolean {
        val first = relative.substringBefore('/')
        if (first.equals("voice", ignoreCase = true)) return true
        if (first in EXCLUDED_DIRS) return true
        if (relative.endsWith(".tmp") || relative.endsWith(".part")) return true
        if (relative.startsWith("sync_manifest", ignoreCase = true)) return true
        return false
    }

    @Suppress("DEPRECATION")
    private fun onWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            val info = manager.activeNetworkInfo ?: return false
            info.type == ConnectivityManager.TYPE_WIFI || info.type == ConnectivityManager.TYPE_ETHERNET
        }
    }

    private val EXCLUDED_DIRS = setOf("build", ".git", "cache", "caches", ".gradle")
}
