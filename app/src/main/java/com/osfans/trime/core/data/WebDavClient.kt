/*
 * 极简 WebDAV 客户端：只依赖 HttpURLConnection，实现 PROPFIND / MKCOL / GET / PUT + Basic 认证。
 * 兼容 Nextcloud / ownCloud / 群晖 / nginx-dav 等常见服务；不引入第三方网络库，避免增大安装包。
 */
package com.osfans.trime.core.data

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class WebDavException(message: String) : IOException(message)

class WebDavClient(
    baseUrl: String,
    remoteDir: String,
    private val username: String,
    private val password: String,
) {
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val lastModified: Long,
        val size: Long,
    )

    /** 已归一化、以 / 结尾的集合 URL（含 remoteDir）。 */
    private val root: String = buildRoot(baseUrl, remoteDir)

    private fun auth(): String = "Basic " + Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP,
    )

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun buildRoot(base: String, dir: String): String {
        require(base.startsWith("http://", true) || base.startsWith("https://", true)) {
            "服务器地址必须以 http:// 或 https:// 开头"
        }
        val trimmedBase = base.trim().trimEnd('/')
        val encodedDir = dir.trim().trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        return if (encodedDir.isEmpty()) "$trimmedBase/" else "$trimmedBase/$encodedDir/"
    }

    private fun urlOf(relative: String, directory: Boolean = false): URL {
        val encoded = relative.trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        val suffix = when {
            encoded.isEmpty() -> ""
            directory -> "$encoded/"
            else -> encoded
        }
        return URL(root + suffix)
    }

    private fun open(method: String, relative: String, directory: Boolean = false): HttpURLConnection {
        val conn = urlOf(relative, directory).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", auth())
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doInput = true
        return conn
    }

    private fun check(code: Int, action: String, relative: String, message: String) {
        if (code !in 200..299) {
            throw WebDavException("$action $relative 失败：HTTP $code $message")
        }
    }

    /** 确保配置的远端根目录存在；405/403/409 表示目录已存在或不允许重复创建，视为可用。 */
    fun ensureRoot() {
        val conn = open("MKCOL", "", directory = true)
        val code = conn.responseCode
        val message = conn.responseMessage.orEmpty()
        conn.disconnect()
        if (code !in 200..299 && code != 403 && code != 405 && code != 409) {
            throw WebDavException("无法访问 WebDAV 目录：HTTP $code $message")
        }
    }

    /** 逐级创建目录，已存在时（405/409）直接跳过。 */
    fun ensureDir(relative: String) {
        val parts = relative.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            val conn = open("MKCOL", current, directory = true)
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299 && code != 405 && code != 409) {
                throw WebDavException("创建远端目录 $current 失败：HTTP $code")
            }
        }
    }

    fun ensureParent(relative: String) {
        val parent = relative.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) ensureDir(parent)
    }

    /** PROPFIND Depth:1 列出一层子项。 */
    fun list(relative: String): List<Entry> {
        val conn = open("PROPFIND", relative, directory = true)
        conn.setRequestProperty("Depth", "1")
        conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        conn.doOutput = true
        conn.outputStream.use { it.write(PROPFIND_BODY.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val message = conn.responseMessage.orEmpty()
        if (code !in 200..299) {
            conn.disconnect()
            throw WebDavException("读取远端目录 $relative 失败：HTTP $code $message")
        }
        val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        return parsePropfind(xml)
    }

    fun upload(file: File, relative: String) {
        val length = file.length()
        require(length <= Int.MAX_VALUE) { "文件过大，无法上传：$relative" }
        val conn = open("PUT", relative)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(length.toInt())
        conn.outputStream.use { out ->
            file.inputStream().use { input -> input.copyTo(out, BUFFER_SIZE) }
        }
        val code = conn.responseCode
        val message = conn.responseMessage.orEmpty()
        conn.disconnect()
        check(code, "上传", relative, message)
    }

    /** 先写临时文件再替换，避免网络中断时损坏本地配置。 */
    fun download(relative: String, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.webdav.tmp")
        val conn = open("GET", relative)
        val code = conn.responseCode
        val message = conn.responseMessage.orEmpty()
        if (code !in 200..299) {
            conn.disconnect()
            throw WebDavException("下载 $relative 失败：HTTP $code $message")
        }
        conn.inputStream.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out, BUFFER_SIZE) }
        }
        conn.disconnect()
        if (tmp.exists() && !tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun parsePropfind(xml: String): List<Entry> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val result = mutableListOf<Entry>()
        val text = StringBuilder()
        var inResponse = false
        var href: String? = null
        var isDirectory = false
        var lastModified = 0L
        var size = 0L
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (localName(parser)) {
                        "response" -> {
                            inResponse = true
                            href = null
                            isDirectory = false
                            lastModified = 0L
                            size = 0L
                        }
                        "collection" -> if (inResponse) isDirectory = true
                    }
                }
                XmlPullParser.TEXT -> if (inResponse) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (localName(parser)) {
                        "href" -> href = text.toString().trim()
                        "getlastmodified" -> lastModified = parseTime(text.toString().trim())
                        "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0L
                        "response" -> {
                            val name = href?.let(::childName).orEmpty()
                            if (name.isNotEmpty()) {
                                result += Entry(name, isDirectory, lastModified, size)
                            }
                            inResponse = false
                        }
                    }
                    text.setLength(0)
                }
            }
            parser.next()
        }
        return result
    }

    /** 去掉 DAV 命名空间前缀（D: / d: / lp1: 等）。 */
    private fun localName(parser: XmlPullParser): String =
        parser.name?.substringAfterLast(':') ?: ""

    /** 把 href 还原成相对当前集合的直接子项名称。 */
    private fun childName(href: String): String {
        val basePath = URL(root).path
        val path = runCatching { URL(URL(root), href).path }.getOrDefault(href)
        val child = if (path.startsWith(basePath)) {
            path.removePrefix(basePath).trim('/')
        } else {
            path.trim('/').substringAfterLast('/')
        }
        if (child.isEmpty() || child.contains('/')) return ""
        return runCatching { URLDecoder.decode(child, "UTF-8") }.getOrDefault(child)
    }

    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .parse(value)?.time ?: 0L
        }.getOrElse {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                    .parse(value)?.time ?: 0L
            }.getOrDefault(0L)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_SIZE = 64 * 1024
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:resourcetype/><d:getlastmodified/><d:getcontentlength/>
</d:prop></d:propfind>"""
    }
}
