/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core.data

import android.content.res.AssetManager
import android.os.Build
import androidx.core.util.AtomicFile
import com.osfans.trime.TrimeApplication
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DataManager {
    private const val DATA_CHECKSUMS_NAME = "checksums.json"

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataChecksums(raw: String): DataChecksums = json.decodeFromString<DataChecksums>(raw)

    private fun serializeDataChecksums(checksums: DataChecksums): String = json.encodeToString(checksums)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Timber.d("Using device protected storage")
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }

    private fun AssetManager.dataChecksums(): DataChecksums = open(DATA_CHECKSUMS_NAME)
        .bufferedReader()
        .use { it.readText() }
        .let { deserializeDataChecksums(it) }

    private val externalFilesDir get() = TrimeApplication.getInstance().externalFilesDir

    val sharedDataDir = File(externalFilesDir, "shared").also { it.mkdirs() }
    /** 跟随 UserDataManager 的存储模式：EXTERNAL_DIRECT 时即为使用者资料夹的真实路径。 */
    val userDataDir: File get() = UserDataManager.userDataDir

    val prebuiltDataDir = File(sharedDataDir, "build")
    val stagingDir = File(userDataDir, "build")

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }

    fun sync() = lock.withLock {
        val destChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            destChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()

        DataDiff.diff(oldChecksums, newChecksums).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> appContext.assets.copyFile(it.path, newChecksums.files[it.path])

                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> removePath(it.path).getOrThrow()
            }
        }

        // 原子写：首次部署中途被系统杀掉时不会留下半个 checksums.json。
        // 若该文件损坏，下次启动会把全部 11MB 数据当成新建文件重新写一遍，
        // 顺带把词典源文件的 mtime 全部刷新，librime 又要重跑一遍工作区更新。
        val atomic = AtomicFile(destChecksumsFile)
        val out = atomic.startWrite()
        try {
            out.write(serializeDataChecksums(newChecksums).toByteArray())
            out.flush()
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }

        Timber.d("Synced!")
    }

    private fun AssetManager.copyFile(filename: String, expectedSha256: String?) {
        val target = File(externalFilesDir, filename)
        // 覆盖安装或 checksums.json 丢失时，diff 会把所有 assets 判成 CreateFile。
        // 内容已经一致就跳过写入，保留原 mtime：librime 的 DetectModifications
        // 按 .yaml 的 mtime 判断工作区是否需要重建，全量重写会让首次部署
        // 白白重跑一遍所有方案，界面长时间停在「正在部署」。
        if (expectedSha256 != null && target.isFile && sha256Hex(target) == expectedSha256) {
            Timber.d("Skip unchanged asset: $filename")
            return
        }
        open(filename).use { i ->
            target
                .also { it.parentFile?.mkdirs() }
                .outputStream()
                .use { o -> i.copyTo(o) }
        }
    }

    private fun sha256Hex(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()

    private fun removePath(path: String) = FileUtils.delete(externalFilesDir.resolve(path))
}
