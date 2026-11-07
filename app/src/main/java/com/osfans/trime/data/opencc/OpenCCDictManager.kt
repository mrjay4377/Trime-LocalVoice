// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.opencc

import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.opencc.dict.Dictionary
import com.osfans.trime.data.opencc.dict.OpenCCDictionary
import com.osfans.trime.data.opencc.dict.TextDictionary
import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import kotlin.system.measureTimeMillis

object OpenCCDictManager {
    init {
        System.loadLibrary("rime_jni")
    }

    private val sharedDir = File(DataManager.sharedDataDir, "opencc").also { it.mkdirs() }
    // 不要在 APP 一启动就在使用者资料夹里凭空建一个空的 opencc/ 目录：
    // 这个目录只有在用户导入自定义 OpenCC 词典时才会用到，
    // 无条件 mkdirs 会让 WebDAV / 资料夹同步的用户莫名多出一个文件夹。
    private val userDir = File(UserDataManager.userDataDir, "opencc")

    fun sharedDictionaries(): List<Dictionary> = sharedDir
        .listFiles()
        ?.mapNotNull { Dictionary.new(it) } ?: listOf()

    fun userDictionaries(): List<Dictionary> = userDir
        .listFiles()
        ?.mapNotNull { Dictionary.new(it) } ?: listOf()

    fun getAllDictionaries(): List<Dictionary> = sharedDictionaries() + userDictionaries()

    fun importFromFile(file: File): OpenCCDictionary {
        val raw =
            Dictionary.new(file)
                ?: throw IllegalArgumentException("${file.path} is not a opencc/text dictionary")
        userDir.mkdirs()
        // convert to opencc format in dictionaries dir
        // preserve original file name
        val new =
            raw.toOpenCCDictionary(
                File(
                    userDir,
                    file.nameWithoutExtension + ".${Dictionary.Type.OCD2.ext}",
                ),
            )
        Timber.d("Converted $raw to $new")
        return new
    }

    /**
     * Convert internal text dict to opencc format
     */
    @JvmStatic
    fun buildOpenCCDict() {
        for (d in getAllDictionaries()) {
            if (d is TextDictionary) {
                val result: Result<OpenCCDictionary>
                measureTimeMillis {
                    result = runCatching { d.toOpenCCDictionary() }
                }.also {
                    result
                        .onSuccess { r ->
                            Timber.d("Took $it to convert to $r")
                        }.onFailure {
                            Timber.e(it, "Failed to convert $d")
                        }
                }
            }
        }
    }

    fun importFromInputStream(
        stream: InputStream,
        name: String,
    ): OpenCCDictionary {
        val tempFile = File(appContext.cacheDir, name)
        tempFile.outputStream().use {
            stream.copyTo(it)
        }
        val new = importFromFile(tempFile)
        tempFile.delete()
        return new
    }

    @JvmStatic
    fun convertLine(
        input: String,
        configFileName: String,
    ): String {
        if (configFileName.isEmpty()) return input
        with(File(userDir, configFileName)) {
            if (exists()) return openCCLineConv(input, path)
        }
        with(File(sharedDir, configFileName)) {
            if (exists()) return openCCLineConv(input, path)
        }
        Timber.w("Specified config $configFileName doesn't exist, returning raw input ...")
        return input
    }

    @JvmStatic
    external fun openCCDictConv(
        src: String,
        dest: String,
        mode: Boolean,
    )

    @JvmStatic
    external fun openCCLineConv(
        input: String,
        configFileName: String,
    ): String

    const val MODE_BIN_TO_TXT = true // OCD(2) to TXT
    const val MODE_TXT_TO_BIN = false // TXT to OCD2
}
