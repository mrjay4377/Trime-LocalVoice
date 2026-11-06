/*
 * 极简 WebDAV 客户端：只依赖 HttpURLConnection，实现 PROPFIND / MKCOL / GET / PUT + Basic 认证。
 * 兼容坚果云 / Nextcloud / ownCloud / 群晖 / nginx-dav 等常见服务；不引入第三方网络库，避免增大安装包。
 *
 * 关键点：Android 内置的 HttpURLConnection（com.android.okhttp...）把请求方法限制在
 * OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/CONNECT/PATCH，直接 setRequestMethod("PROPFIND")
 * 会抛 ProtocolException「Expected one of [OPTIONS, GET, HEAD, POS...]」，WebDAV 同步必然失败。
 * 这里先尝试把 WebDAV 方法加进实现类的静态白名单，再退化为反射写 java.net.HttpURLConnection
 * 的 protected method 字段（公开 SDK 类，反射不受隐藏 API 限制），无需引入 OkHttp。
 */
package com.osfans.trime.core.data

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
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

    private fun openUrl(method: String, url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        applyMethod(conn, method)
        conn.setRequestProperty("Authorization", auth())
        // 坚果云等服务器会拒绝没有 User-Agent 的 WebDAV 请求。
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doInput = true
        conn.instanceFollowRedirects = false
        return conn
    }

    /**
     * 让 Android 的 HttpURLConnection 接受 WebDAV 专有方法。
     * 依次尝试：正规 API → 扩充实现类静态白名单 → 反射写 method 字段。
     */
    private fun applyMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.setRequestMethod(method)
            return
        } catch (_: ProtocolException) {
            // 落到兼容路径
        }
        patchMethodWhitelist()
        try {
            conn.setRequestMethod(method)
            if (method.equals(conn.requestMethod, ignoreCase = true)) return
        } catch (_: ProtocolException) {
            // 部分 ROM 的白名单不可写，继续用反射
        }
        // HttpURLConnection.method 是公开 SDK 类的 protected 字段；沿继承链把所有同名字段
        // 都写上，兼容存在隐藏字段的实现类。
        var patched = false
        var cls: Class<*>? = conn.javaClass
        while (cls != null) {
            val current = cls
            runCatching {
                val field = current.getDeclaredField("method")
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    field.set(conn, method)
                    patched = true
                }
            }
            cls = current.superclass
        }
        if (!patched || !method.equals(conn.requestMethod, ignoreCase = true)) {
            throw WebDavException(
                "当前系统不允许使用 WebDAV 方法 $method（请确认服务器地址是 WebDAV 地址）",
            )
        }
    }

    /** 各 Android 版本内置 OkHttp 实现类的位置不同，逐个尝试；失败不影响后续反射兜底。 */
    private fun patchMethodWhitelist() {
        for (name in IMPL_CLASSES) {
            runCatching {
                val cls = Class.forName(name)
                val field = cls.getDeclaredField("METHODS")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val methods = field.get(null) as? MutableSet<String>
                methods?.add("PROPFIND")
                methods?.add("MKCOL")
            }
        }
    }

    private class Response(
        val conn: HttpURLConnection,
        val code: Int,
        val message: String,
    )

    /**
     * 统一的请求执行：带 Body 的 PROPFIND / PUT、无 Body 的 MKCOL / GET 都走这里，
     * 并手动处理 301/302/307/308（HttpURLConnection 默认不会带 Body 跟随重定向）。
     * 重定向只允许同主机，避免把 Basic 凭据发到第三方地址。
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
            var opened: HttpURLConnection? = null
            try {
                val conn = openUrl(method, url)
                opened = conn
                headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
                when {
                    body != null -> {
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(body.size)
                        conn.outputStream.use { it.write(body) }
                    }
                    upload != null -> {
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(upload.length().toInt())
                        conn.outputStream.use { out ->
                            upload.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
                        }
                    }
                }
                val code = conn.responseCode
                val message = conn.responseMessage.orEmpty()
                val location = conn.getHeaderField("Location")
                if (code in REDIRECT_CODES && !location.isNullOrBlank() && redirects < MAX_REDIRECTS) {
                    val next = runCatching { URL(url, location) }.getOrNull()
                    if (next != null &&
                        next.host.equals(url.host, ignoreCase = true) &&
                        (next.protocol.equals("http", true) || next.protocol.equals("https", true))
                    ) {
                        conn.disconnect()
                        opened = null
                        url = next
                        redirects++
                        continue
                    }
                }
                return Response(conn, code, message)
            } catch (t: Throwable) {
                opened?.disconnect()
                throw t
            }
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
        val response = request("MKCOL", "", directory = true)
        response.conn.disconnect()
        if (response.code !in 200..299 && response.code != 403 &&
            response.code != 405 && response.code != 409
        ) {
            throw httpError("访问 WebDAV 目录", "", response.code, response.message)
        }
    }

    /** 逐级创建目录，已存在时（405/409）直接跳过。 */
    fun ensureDir(relative: String) {
        val parts = relative.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            val response = request("MKCOL", current, directory = true)
            response.conn.disconnect()
            if (response.code !in 200..299 && response.code != 405 && response.code != 409) {
                throw httpError("创建远端目录", current, response.code, response.message)
            }
        }
    }

    fun ensureParent(relative: String) {
        val parent = relative.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) ensureDir(parent)
    }

    /** PROPFIND Depth:1 列出一层子项；207 Multi-Status 属于 2xx。 */
    fun list(relative: String): List<Entry> {
        val response = request(
            method = "PROPFIND",
            relative = relative,
            directory = true,
            headers = mapOf(
                "Depth" to "1",
                "Content-Type" to "application/xml; charset=utf-8",
            ),
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        )
        try {
            if (response.code !in 200..299) {
                throw httpError("读取远端目录", relative, response.code, response.message)
            }
            val xml = response.conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parsePropfind(xml)
        } finally {
            response.conn.disconnect()
        }
    }

    fun upload(file: File, relative: String) {
        require(file.length() <= Int.MAX_VALUE) { "文件过大，无法上传：$relative" }
        val response = request(
            method = "PUT",
            relative = relative,
            headers = mapOf("Content-Type" to "application/octet-stream"),
            upload = file,
        )
        response.conn.disconnect()
        if (response.code !in 200..299) {
            throw httpError("上传", relative, response.code, response.message)
        }
    }

    /** 先写临时文件再替换，避免网络中断时损坏本地配置。 */
    fun download(relative: String, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.webdav.tmp")
        try {
            val response = request("GET", relative)
            try {
                if (response.code !in 200..299) {
                    throw httpError("下载", relative, response.code, response.message)
                }
                response.conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, BUFFER_SIZE) }
                }
            } finally {
                response.conn.disconnect()
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
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_SIZE = 64 * 1024
        const val USER_AGENT = "TrimeLocalVoice-WebDAV/1.0"
        const val MAX_REDIRECTS = 3
        val REDIRECT_CODES = setOf(301, 302, 307, 308)
        val IMPL_CLASSES = arrayOf(
            "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
            "com.squareup.okhttp.internal.huc.HttpURLConnectionImpl",
            "libcore.net.http.HttpURLConnectionImpl",
        )
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:resourcetype/><d:getlastmodified/><d:getcontentlength/>
</d:prop></d:propfind>"""
    }
}
