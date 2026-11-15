/*
 * 手写诊断日志兼容层（v83 起调试输出已关闭）。
 *
 * v79–v81 为定位「写完跳回键盘」，曾把全链路写进 Download/Trime 与应用私有目录，
 * 并在面板上常驻显示日志路径、中断断点与切回调用栈。根因（char_index.json 的
 * {"chars":[...]} 格式未被解析）修复后取证代码不再需要：正式版不创建、不追加任何
 * 日志文件，也不再做 fsync / MediaStore 导出，面板上也不再显示任何调试信息。
 *
 * 保留类名与方法签名，仅作为残留调用点的空操作兼容层；若将来需要临时取证，
 * 把 DEBUG 置 true 并恢复落盘实现即可，调用点无需改动。
 */
package com.osfans.trime.handwriting

import android.content.Context
import java.io.File

object HandwritingLog {
    /** 调试日志总开关：false 时所有调用都是空操作，零文件 IO。 */
    private const val DEBUG = false

    const val FILE_NAME = "handwriting.log"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun userFile(): File = File("")

    fun userHint(): String = ""

    fun export(context: Context): String = ""

    fun i(
        context: Context,
        tag: String,
        msg: String,
        t: Throwable? = null,
    ) {
        if (!DEBUG) return
    }

    fun w(
        context: Context,
        tag: String,
        msg: String,
        t: Throwable? = null,
    ) {
        if (!DEBUG) return
    }

    fun e(
        context: Context,
        tag: String,
        msg: String,
        t: Throwable? = null,
    ) {
        if (!DEBUG) return
    }

    fun callers(
        context: Context,
        tag: String,
        msg: String,
        depth: Int = 12,
    ) {
        if (!DEBUG) return
    }

    fun clear(context: Context) {
        if (!DEBUG) return
    }
}
