/*
 * 极简 WebDAV 客户端：PROPFIND / MKCOL / GET / PUT + Basic 认证。
 * 兼容坚果云 / Nextcloud / ownCloud / 群晖 / nginx-dav 等常见服务。
 *
 * 为什么用 OkHttp：Android 内置的 HttpURLConnection（com.android.okhttp...）把请求方法
 * 限制在 9 个标准动词里，PROPFIND/MKCOL 会抛「Expected one of [OPTIONS, GET, HEAD, POS...]」；
 * 反射写 method 字段在部分 ROM 上会被隐藏 API 限制挡住（实现类自带同名字段，改不到），
 * 所以 v26 起改用 OkHttp —— 它原生支持任意 HTTP 方法，且 TLS/SNI、重定向、gzip 都处理妥当。
 */
package com.osfans.trime.core.data

import android.util.Base64
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

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

    /** 大文件边读边写，不整块载入内存；contentLength 让 OkHttp 不启用 chunked 传输。 */
    private fun fileBody(file: File): RequestBody = object : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                }
            }
        }
    }

    /**
     * 手动跟随 301/302/307/308（只限同主机，避免把 Basic 凭据发到第三方地址）。
     * 返回的 Response 由调用方 use{} 关闭。
     */
    private fun request(
        method: String,
        relative: String,
        directory: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        upload: File? = null,
    ): Response {
        var url = urlOf(relative, directory)
        var redirects = 0
        while (true) {
            val builder = Request.Builder()
                .url(url.toString())
                .header("Authorization", auth())
                // 坚果云等服务器会拒绝没有 User-Agent 的 WebDAV 请求
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
            headers.forEach { (key, value) -> builder.header(key, value) }
            val requestBody = when {
                body != null -> body.toRequestBody(XML_MEDIA_TYPE)
                upload != null -> fileBody(upload)
                else -> null
            }
            val response = client.newCall(builder.method(method, requestBody).build()).execute()
            val code = response.code
            val location = response.header("Location")
            if (code in REDIRECT_CODES && !location.isNullOrBlank() && redirects < MAX_REDIRECTS) {
                val next = runCatching { URL(url, location) }.getOrNull()
                if (next != null &&
                    next.host.equals(url.host, ignoreCase = true) &&
                    (next.protocol.equals("http", true) || next.protocol.equals("https", true))
                ) {
                    response.close()
                    url = next
                    redirects++
                    continue
                }
            }
            return response
        }
    }

    private fun httpError(action: String, relative: String, code: Int, message: String): WebDavException {
        val hint = when (code) {
            401 -> "（账号或密码不正确；坚果云需在网页端「账户信息 → 安全选项 → 添加应用」生成应用密码）"
            403 -> "（没有访问权限，或服务器禁止该目录/方法；请检查远端目录与账号权限）"
            404 -> "（地址不正确或远端目录不存在；坚果云地址通常是 https://dav.jianguoyun.com/dav）"
            405, 501 -> "（服务器不支持该 WebDAV 方法，请确认填写的是 WebDAV 地址而不是网页地址）"
            409 -> "（上级目录不存在，请先在网页端建好远端目录）"
            507 -> "（云端存储空间不足）"
            else -> ""
        }
        val target = relative.ifBlank { "/" }
        return WebDavException("$action $target 失败：HTTP $code $message$hint")
    }

    /** 确保配置的远端根目录存在；405/403/409 表示目录已存在或不允许重复创建，视为可用。 */
    fun ensureRoot() {
        request("MKCOL", "", directory = true).use { response ->
            if (response.code !in 200..299 &&
                response.code != 403 && response.code != 405 && response.code != 409
            ) {
                throw httpError("访问 WebDAV 目录", "", response.code, response.message)
            }
        }
    }

    /** 逐级创建目录，已存在时（405/409）直接跳过。 */
    fun ensureDir(relative: String) {
        val parts = relative.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            request("MKCOL", current, directory = true).use { response ->
                if (response.code !in 200..299 && response.code != 405 && response.code != 409) {
                    throw httpError("创建远端目录", current, response.code, response.message)
                }
            }
        }
    }

    fun ensureParent(relative: String) {
        val parent = relative.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) ensureDir(parent)
    }

    /** PROPFIND Depth:1 列出一层子项；207 Multi-Status 属于 2xx。 */
    fun list(relative: String): List<Entry> =
        request(
            method = "PROPFIND",
            relative = relative,
            directory = true,
            headers = mapOf("Depth" to "1"),
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        ).use { response ->
            if (response.code !in 200..299) {
                throw httpError("读取远端目录", relative, response.code, response.message)
            }
            val xml = response.body?.string()
                ?: throw WebDavException("读取远端目录 $relative 失败：服务器返回空内容")
            parsePropfind(xml)
        }

    fun upload(file: File, relative: String) {
        request("PUT", relative, upload = file).use { response ->
            if (response.code !in 200..299) {
                throw httpError("上传", relative, response.code, response.message)
            }
        }
    }

    /** 先写临时文件再替换，避免网络中断时损坏本地配置。 */
    fun download(relative: String, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.webdav.tmp")
        try {
            request("GET", relative).use { response ->
                if (response.code !in 200..299) {
                    throw httpError("下载", relative, response.code, response.message)
                }
                val stream = response.body?.byteStream()
                    ?: throw WebDavException("下载 $relative 失败：服务器返回空内容")
                stream.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, BUFFER_SIZE) }
                }
            }
            if (tmp.exists() && !tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
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
        const val CONNECT_TIMEOUT_S = 15L
        const val READ_TIMEOUT_S = 60L
        const val BUFFER_SIZE = 64 * 1024
        const val USER_AGENT = "TrimeLocalVoice-WebDAV/1.0"
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 307, 308)
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:resourcetype/><d:getlastmodified/><d:getcontentlength/>
</d:prop></d:propfind>"""
    }
}
