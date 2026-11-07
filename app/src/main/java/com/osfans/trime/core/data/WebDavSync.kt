/*
 * WebDAV 双向同步：把 rime 用户目录（配置 / 方案 / 词库）与远端目录按修改时间对齐。
 *
 * 设计取舍：
 * - voice/ 里是几百 MB 的离线模型，绝不参与同步（与 UserDataManager 的过滤保持一致）；
 * - 不做删除传播：只在一侧缺失时把文件补到另一侧，避免误删配置或词库；
 * - 远端时间未知时保守跳过，并在结果里单独计数（可用「从服务器恢复」强制覆盖）；
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
    private const val MAX_DEPTH = 16

    data class Result(
        val uploaded: Int,
        val downloaded: Int,
        val skipped: Int,
        /** 因服务器没给 getlastmodified 而无法判断新旧、被保守跳过的数量。 */
        val skippedNoTime: Int,
        val failed: List<String>,
        /** 本次扫描到的远端可用文件数（已排除 voice / build / sync 等）。 */
        val remoteFiles: Int = 0,
        /** 实际连接的远端集合 URL，便于核对「远端目录」是否填对。 */
        val remoteRootUrl: String = "",
        /** 远端根目录的一级项；远端文件为 0 时提示备份是否藏在子目录里。 */
        val remoteTopLevel: List<String> = emptyList(),
        /** PROPFIND 原始 response 数，用来发现「解析阶段就丢了文件」。 */
        val remoteEntries: Int = 0,
        /** 解析后得到的有效条目数（含目录）；与 remoteEntries 差距过大即解析有遗漏。 */
        val parsedEntries: Int = 0,
        /** 被排除规则跳过的远端路径（恢复时可核对到底漏了什么）。 */
        val excludedRemote: List<String> = emptyList(),
        /** 恢复结束后服务器上仍有、本地却不存在的文件；正常应为空。 */
        val missingLocal: List<String> = emptyList(),
        /** 远端文件路径示例，用来核对是不是多了一层目录（例如全都是 trime/xxx）。 */
        val samplePaths: List<String> = emptyList(),
    ) {
        val succeeded: Boolean get() = failed.isEmpty()

        fun describe(): String = buildString {
            append("上传 $uploaded · 下载 $downloaded · 跳过 $skipped")
            if (skippedNoTime > 0) append("（其中 $skippedNoTime 项服务器未提供修改时间）")
            if (failed.isNotEmpty()) append(" · 失败 ${failed.size}")
            if (missingLocal.isNotEmpty()) append(" · 未恢复 ${missingLocal.size}")
        }
    }

    /**
     * @param preferRemote 恢复模式：两边都有同名文件时无条件以远端覆盖本地，
     *   用于重新安装 / 本地配置丢失后把服务器上的备份完整拉回来。
     */
    suspend fun sync(
        context: Context,
        onProgress: (String) -> Unit = {},
        preferRemote: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val prefs = WebDavPrefs(context)
        // enabled 只控制后台定时同步（BackgroundSyncWork）。手动「立即同步 / 从服务器恢复」
        // 不该被这个开关挡住：重装后开关默认是关的，会让恢复入口点了毫无反应。
        require(prefs.isConfigured()) { "请先填写 WebDAV 地址、账号和密码" }
        require(!prefs.wifiOnly || onWifi(context)) { "仅 Wi-Fi 下同步：当前不是 Wi-Fi 网络" }

        val client = WebDavClient(prefs.url, prefs.remoteDir, prefs.username, prefs.password)
        val root = UserDataManager.userDataDir
        require(root.isDirectory || root.mkdirs()) { "用户数据目录不可用" }

        onProgress("连接 WebDAV 服务器…")
        client.ensureRoot()
        val failed = mutableListOf<String>()
        val excludedRemote = mutableListOf<String>()
        var remoteEntries = 0
        var parsedEntries = 0
        val remote = listRemote(client, failed, preferRemote, excludedRemote) { raw, parsed ->
            remoteEntries += raw
            parsedEntries += parsed
        }
        val local = listLocal(root, preferRemote)
        onProgress("比对 ${local.size} 个本地文件 / ${remote.size} 个远端文件…")

        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var skippedNoTime = 0
        val paths = (local.keys + remote.keys).distinct().sorted()

        for ((index, relative) in paths.withIndex()) {
            onProgress("(${index + 1}/${paths.size}) $relative")
            val localFile = local[relative]
            val remoteEntry = remote[relative]
            when {
                localFile != null && remoteEntry == null -> {
                    // 恢复模式只从服务器取回，不把本机重装后的预设文件推上去污染备份。
                    if (preferRemote) {
                        skipped++
                    } else {
                        runCatching {
                            client.ensureParent(relative)
                            client.upload(localFile, relative)
                            uploaded++
                        }.onFailure { failed += "上传 $relative：${it.message}" }
                    }
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
                    if (preferRemote) {
                        runCatching {
                            client.download(relative, localFile)
                            if (remoteEntry.lastModified > 0) localFile.setLastModified(remoteEntry.lastModified)
                            downloaded++
                        }.onFailure { failed += "恢复 $relative：${it.message}" }
                    } else {
                        val localTime = localFile.lastModified()
                        val remoteTime = remoteEntry.lastModified
                        when {
                            // 服务器没返回可解析的时间时无从判断新旧，保守跳过；
                            // 单独计数，好在状态栏说明「跳过」不是同步完成。
                            remoteTime <= 0L -> {
                                skipped++
                                skippedNoTime++
                            }
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
        }

        // 恢复时一个远端文件都没扫到，多半是「远端目录」指错了；多列一次根目录内容，
        // 好让界面直接告诉用户备份是不是在某个子目录里，而不是含糊地报「完成」。
        // 恢复结束后重新扫一次本地，逐项核对远端文件是否真的落盘。
        // 以前只看「下载计数」，路径解析丢条目、或恢复后被别处删掉，界面完全看不出来。
        val missing = if (preferRemote) {
            val localAfter = listLocal(root, preferRemote)
            remote.keys.filter { it !in localAfter }.sorted()
        } else {
            emptyList()
        }
        val topLevel = if (remote.isEmpty()) client.topLevelNames() else emptyList()
        val result = Result(
            uploaded, downloaded, skipped, skippedNoTime, failed,
            remote.size, client.rootUrl(), topLevel,
            remoteEntries, parsedEntries, excludedRemote.distinct().sorted(), missing,
            remote.keys.sorted().take(5),
        )
        // 有失败项、恢复模式远端为空、或核对发现缺文件时，不要把「上次同步」刷新成
        // 当前时间，否则状态栏会显示刚刚同步，让用户误以为配置已完整恢复。
        val incomplete = failed.isNotEmpty() ||
            (preferRemote && (remote.isEmpty() || missing.isNotEmpty()))
        if (!incomplete) prefs.lastSyncTime = System.currentTimeMillis()
        prefs.lastSyncResult =
            if (incomplete) "未完成 · ${result.describe()}" else result.describe()
        result
    }

    private fun listRemote(
        client: WebDavClient,
        failures: MutableList<String>,
        preferRemote: Boolean,
        excluded: MutableList<String>,
        onEntries: (Int, Int) -> Unit,
    ): Map<String, WebDavClient.Entry> {
        val result = mutableMapOf<String, WebDavClient.Entry>()

        fun walk(prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            // 子目录可能被并发删除或临时不可读，不应让整批同步直接失败；
            // 根目录读不到才真正抛错（说明地址 / 账号 / 权限有问题）。
            val entries = try {
                client.list(prefix)
            } catch (e: Exception) {
                if (prefix.isEmpty()) throw e
                failures += "读取远端目录 $prefix：${e.message}"
                return
            }
            onEntries(client.lastResponseCount, entries.size)
            for (entry in entries) {
                val relative = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
                if (isExcluded(relative, preferRemote)) {
                    excluded += relative
                    continue
                }
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

    private fun listLocal(root: File, preferRemote: Boolean): Map<String, File> {
        val result = mutableMapOf<String, File>()
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
            if (!isExcluded(relative, preferRemote)) result[relative] = file
        }
        return result
    }

    /**
     * voice/ 是几百 MB 模型、build/ 是本机编译缓存，两者任何模式都不碰。
     * 恢复模式放宽 sync / cache / .git 等目录：那些是用户目录里的真实资料
     * （sync/<id>/ 里是 librime 用户词典快照 .userdb.txt），重装后本就该一起拉回来；
     * 普通同步仍按老规则排除，避免把本机快照推上服务器。
     */
    private fun isExcluded(relative: String, preferRemote: Boolean): Boolean {
        val first = relative.substringBefore('/')
        if (first.equals("voice", ignoreCase = true)) return true
        if (first.equals("build", ignoreCase = true)) return true
        if (!preferRemote && first.lowercase() in EXCLUDED_DIRS) return true
        if (relative.endsWith(".tmp") || relative.endsWith(".part")) return true
        // UserDataManager 的资料夹镜像清单是本机元数据，不该同步到服务器。
        if (first.equals(".sync-manifest.json", ignoreCase = true)) return true
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

    /** librime 的 sync/ 是本机用户词典快照（*.userdb.txt 与配置备份），不是要跨设备同步的资料。 */
    private val EXCLUDED_DIRS = setOf("build", "sync", ".git", "cache", "caches", ".gradle")
}
