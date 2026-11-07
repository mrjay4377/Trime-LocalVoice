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
import java.io.InterruptedIOException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
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

    /**
     * 最近一次 PROPFIND 里 `<d:response>` 的原始数量（含集合自身和被忽略的条目）。
     * 与 list() 解析出的条目数对比，就能判断文件是不是在 href 解析阶段被丢掉。
     */
    @Volatile
    var lastResponseCount: Int = 0
        private set

    /** 远端目录留空时，地址本身就是根目录，不应在服务器上新建任何文件夹。 */
    private val autoCreateRoot: Boolean = remoteDir.trim().trim('/').isNotEmpty()

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
     * DNS 抖动、切换 Wi-Fi / 移动数据、连接超时这类瞬时错误，OkHttp 的
     * retryOnConnectionFailure 并不会重试（域名解析返回空地址时直接抛 UnknownHostException）。
     * 一次抖动会让整批十几个文件全部失败，所以在这里做少量短退避重试。
     * 只对 IOException 重试；HTTP 4xx/5xx 是正常响应，仍交给调用方按状态码处理。
     */
    private fun isTransientNetworkError(e: IOException): Boolean {
        if (e is UnknownHostException || e is InterruptedIOException) return true
        val msg = e.message.orEmpty().lowercase(Locale.US)
        return "unable to resolve host" in msg ||
            "no address associated" in msg ||
            "network is unreachable" in msg ||
            "failed to connect" in msg ||
            "connection reset" in msg ||
            "econnreset" in msg ||
            "software caused connection abort" in msg
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
        var networkRetries = 0
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
            val response = try {
                client.newCall(builder.method(method, requestBody).build()).execute()
            } catch (e: IOException) {
                if (!isTransientNetworkError(e) || networkRetries >= MAX_NETWORK_RETRIES) throw e
                networkRetries++
                // 每次重试都重建 Request / RequestBody：fileBody 每次 writeTo 会重新打开文件，
                // 所以 PUT 重发是安全的。
                try {
                    Thread.sleep(RETRY_DELAY_MS * networkRetries)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("WebDAV 同步已中断", e)
                }
                continue
            }
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

    /** PROPFIND Depth:0 只探测目录本身，不列子项。 */
    private fun exists(relative: String): Boolean =
        request(
            method = "PROPFIND",
            relative = relative,
            directory = true,
            headers = mapOf("Depth" to "0"),
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        ).use { it.code in 200..299 }

    /**
     * 只有填了「远端目录」并且该目录确实不存在时才新建文件夹。
     * 留空表示用户指定地址就是根目录，绝不对其发 MKCOL；
     * 已存在时也不再每次同步重复发 MKCOL（部分服务器会记日志或返回 403）。
     * 405/403/409 仍视为目录已存在或不允许重复创建。
     */
    fun ensureRoot() {
        if (!autoCreateRoot || exists("")) return
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
    fun list(relative: String): List<Entry> {
        // 必须以「本次请求的集合」为基准解析 href：坚果云等服务器会在 Depth:1 的响应里
        // 把集合自身也列出来，若仍拿全局 root 去减前缀，递归到 backgrounds/ 时就会把
        // 自身误认成子项 backgrounds，拼出 backgrounds/backgrounds 并对它发 PROPFIND → 404。
        val collection = urlOf(relative, directory = true)
        return request(
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
            parsePropfind(xml, collection)
        }
    }

    /** 当前实际连接的集合 URL，界面上用于核对「远端目录」有没有填错。 */
    fun rootUrl(): String = root

    /** 根目录一级项（目录带 / 后缀）；恢复时一个文件都扫不到时，用来判断备份是不是藏在子目录里。 */
    fun topLevelNames(): List<String> = runCatching {
        list("").map { if (it.isDirectory) "${it.name}/" else it.name }
    }.getOrDefault(emptyList())

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

    private fun parsePropfind(xml: String, collection: URL): List<Entry> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val result = mutableListOf<Entry>()
        val text = StringBuilder()
        var rawResponses = 0
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
                            rawResponses++
                            val rawHref = href?.trim()
                            val name = rawHref?.let { childName(it, collection) }.orEmpty()
                            // 部分服务器不给 <d:collection/>，但 href 以 / 结尾必定是集合；
                            // 误判成文件会去 GET 一个目录，里面的文件就整批漏掉。
                            val asDirectory = isDirectory || rawHref?.endsWith("/") == true
                            if (name.isNotEmpty()) {
                                result += Entry(name, asDirectory, lastModified, size)
                            }
                            inResponse = false
                        }
                    }
                    text.setLength(0)
                }
            }
            parser.next()
        }
        lastResponseCount = rawResponses
        return result
    }

    /** 去掉 DAV 命名空间前缀（D: / d: / lp1: 等）。 */
    private fun localName(parser: XmlPullParser): String =
        parser.name?.substringAfterLast(':') ?: ""

    /**
     * 把 href 还原成相对「本次列出的集合」的直接子项名称。
     * - 集合自身（多数服务器在 Depth:1 里会返回）必须跳过，否则递归时目录名会不断叠加；
     * - 只接受直接子项，深层路径一律忽略，避免把孙级文件记成当前层的条目；
     * - href 既可能是绝对路径，也可能是相对当前集合或相对 root 的路径，两种 base 都试一次。
     */
    /**
     * 统一 path 比较基准：去掉 query / fragment、解掉百分号编码、去掉尾部斜杠。
     * 上传用 `%20` / `%E4%B8%AD` 这类编码，服务器 href 却可能返回已解码的中文或空格；
     * 直接拿编码后的字符串做 startsWith 会让整个子目录被判成「不属于本集合」而整批丢弃，
     * 表现就是「同步成功但少了一部分文件」。
     */
    private fun normalizePath(value: String): String {
        val cleaned = value.substringBefore('?').substringBefore('#')
        return runCatching {
            // URLDecoder 会把 + 当空格，而上传时用的是 %20；文件名真含 + 时要保留。
            URLDecoder.decode(cleaned.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(cleaned).trimEnd('/')
    }

    private fun childName(href: String, collection: URL): String {
        val basePath = normalizePath(collection.path)
        val candidates = listOf(
            runCatching { URL(collection, href).path }.getOrDefault(""),
            runCatching { URL(URL(root), href).path }.getOrDefault(""),
            href,
        )
        val paths = candidates.map { normalizePath(it) }.filter { it.isNotEmpty() }
        // 任一解析方式指向集合自身就跳过：不同服务器返回绝对 / 相对 href 的写法不一致，
        // 只要有一个 base 能认出「这是自己」，就不能把它当成子目录再递归一层。
        if (paths.any { it == basePath }) return ""
        for (path in paths) {
            if (!path.startsWith("$basePath/")) continue
            val child = path.removePrefix("$basePath/")
            if (child.isEmpty() || child.contains('/')) continue
            return child
        }
        return ""
    }

    /**
     * 不同 WebDAV 服务返回的 getlastmodified 格式差别很大：坚果云 / nginx-dav 用
     * RFC 1123，Nextcloud 常用 ISO 8601，部分服务直接给 `yyyy-MM-dd HH:mm:ss`。
     * 少解析一种格式，就会让这批文件的远端时间变成 0，被同步逻辑当成「时间未知」整批跳过。
     */
    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        for (pattern in TIME_PATTERNS) {
            val time = runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { isLenient = false }
                    .parse(value)?.time
            }.getOrNull()
            if (time != null && time > 0L) return time
        }
        // 少数服务直接返回 epoch 秒 / 毫秒
        return value.toLongOrNull()?.let { if (it > 1_000_000_000_000L) it else it * 1000L } ?: 0L
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 15L
        const val READ_TIMEOUT_S = 60L
        const val BUFFER_SIZE = 64 * 1024
        const val USER_AGENT = "TrimeLocalVoice-WebDAV/1.0"
        const val MAX_REDIRECTS = 3
        const val MAX_NETWORK_RETRIES = 2
        const val RETRY_DELAY_MS = 400L
        val TIME_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss zzz",
        )
        val REDIRECT_CODES = setOf(301, 302, 307, 308)
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:resourcetype/><d:getlastmodified/><d:getcontentlength/>
</d:prop></d:propfind>"""
    }
}
