package com.hmdeepseek.app.net

import com.hmdeepseek.app.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * DeepSeek API 客户端（Messages 协议）。
 *
 * 只有 Messages 协议会把思考、检索步骤、抓页结果、引用都返回成结构化 block，
 * 正好对应 App 那套 UI；流式解析见 [SseParser]。
 */
object DeepSeekApi {

    class StreamCall {
        @Volatile
        var conn: HttpURLConnection? = null

        @Volatile
        var cancelled: Boolean = false

        fun cancel() {
            cancelled = true
            runCatching { conn?.disconnect() }
        }
    }

    data class UploadResult(
        val ok: Boolean = false,
        val fileId: String = "",
        val bytes: Long = 0L,
        val error: String = ""
    )

    fun baseUrl(settings: AppSettings): String {
        val raw = settings.baseUrl.trim().ifEmpty { "https://api.deepseek.com" }
        return raw.trimEnd('/')
    }

    suspend fun streamMessages(
        settings: AppSettings,
        body: String,
        withDocs: Boolean,
        call: StreamCall,
        onEvent: (String, String) -> Unit,
        onDone: (stopped: Boolean) -> Unit,
        onError: (message: String, httpCode: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("${baseUrl(settings)}/anthropic/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 300_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("x-api-key", settings.apiKey.trim())
                setRequestProperty("Authorization", "Bearer ${settings.apiKey.trim()}")
                if (withDocs) setRequestProperty("anthropic-beta", "files-api-2025-04-14")
            }
            call.conn = conn
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { r -> r.readText() } ?: "" }
                    .getOrDefault("")
                onError(friendlyError(code, err), code)
                return@withContext
            }

            val parser = SseParser(onEvent)
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!call.cancelled) {
                    val line = reader.readLine() ?: break
                    parser.feedLine(line)
                }
            }
            onDone(call.cancelled)
        } catch (e: Exception) {
            if (call.cancelled) {
                onDone(true)
            } else {
                onError("网络错误：${e.message ?: e.javaClass.simpleName}", 0)
            }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 流式上传文件（不把整个文件读进内存）。
     *
     * 之前是「读全文件 → 拼 multipart → 发」，40 MB 的 PDF 峰值能吃掉 150 MB 内存，
     * 中低端机上直接 OOM，表现就是「文件太大」。现在改成固定长度流式写：
     * 内存只占 64 KB 一块，同时能回报上传进度。
     */
    suspend fun uploadFile(
        settings: AppSettings,
        fileName: String,
        fileSize: Long,
        purpose: String,
        openStream: () -> java.io.InputStream,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): UploadResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        var stream: java.io.InputStream? = null
        try {
            val boundary = "----dshm" + UUID.randomUUID().toString().replace("-", "")
            val head = buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n")
                append(purpose).append("\r\n")
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                append(fileName.replace("\"", "_")).append("\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val total = head.size.toLong() + fileSize + tail.size

            conn = (URL("${baseUrl(settings)}/files").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = uploadTimeout(fileSize)
                doOutput = true
                if (fileSize > 0) {
                    setFixedLengthStreamingMode(total)
                } else {
                    // 拿不到文件大小时退回 chunked，避免 Content-Length 与实际字节数不符
                    setChunkedStreamingMode(64 * 1024)
                }
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("x-api-key", settings.apiKey.trim())
                setRequestProperty("Authorization", "Bearer ${settings.apiKey.trim()}")
            }

            conn.outputStream.use { out ->
                out.write(head)
                stream = openStream()
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    // 每块检查一次取消，保证点「取消」能在一块的粒度内生效
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = stream!!.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    sent += n
                    onProgress(sent, fileSize)
                }
                out.write(tail)
                out.flush()
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                return@withContext UploadResult(error = friendlyError(code, text))
            }
            val o = JSONObject(text)
            val id = o.optString("id")
            if (id.isEmpty()) {
                UploadResult(error = o.optJSONObject("error")?.optString("message") ?: "上传失败：没有返回 file_id")
            } else {
                UploadResult(ok = true, fileId = id, bytes = fileSize)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            UploadResult(error = if (msg.contains("cancel", true)) "已取消上传" else "上传失败：$msg")
        } finally {
            runCatching { stream?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    /** 超时按文件大小放宽：基础 120s，每 MB 加 3s，最多 15 分钟 */
    private fun uploadTimeout(fileSize: Long): Int {
        val extra = (fileSize / (1024 * 1024) * 3_000).toInt()
        return (120_000 + extra).coerceIn(120_000, 900_000)
    }

    suspend fun transcribe(settings: AppSettings, wav: ByteArray): String = withContext(Dispatchers.IO) {
        runCatching {
            val boundary = "----dshm" + UUID.randomUUID().toString().replace("-", "")
            val body = buildMultipart(boundary, emptyList(), "file", "speech.wav", wav)
            val text = postBytes(
                url = "${baseUrl(settings)}/v1/tools/asr",
                settings = settings,
                contentType = "multipart/form-data; boundary=$boundary",
                body = body,
                timeoutMs = 300_000
            )
            JSONObject(text).optString("text")
        }.getOrDefault("")
    }

    suspend fun fetchBalance(settings: AppSettings): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("${baseUrl(settings)}/user/balance").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-api-key", settings.apiKey.trim())
                setRequestProperty("Authorization", "Bearer ${settings.apiKey.trim()}")
            }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
            }.getOrDefault("")
            if (code !in 200..299) return@withContext friendlyError(code, text)
            val infos = JSONObject(text).optJSONArray("balance_infos")
            if (infos == null || infos.length() == 0) return@withContext "连接成功（未取到余额字段）"
            val first = infos.optJSONObject(0)
            val total = first?.optString("total_balance") ?: "-"
            val currency = first?.optString("currency") ?: ""
            "连接成功，余额 $total $currency"
        } catch (e: Exception) {
            "连接失败：${e.message ?: "未知错误"}"
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun friendlyError(code: Int, raw: String): String {
        val msg = runCatching {
            val o = JSONObject(raw)
            o.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { raw }
        }.getOrDefault(raw)
        return when (code) {
            401 -> "API Key 无效或已过期（401），去设置里检查一下。"
            402 -> "账户余额不足（402）。"
            408 -> "上传超时（408）：服务端要求 120 秒内传完，文件太大或网络太慢，建议压缩或拆分文件。"
            413 -> "文件超过 100 MiB（413）：服务端单文件上限就是 100 MiB，请压缩或拆分。"
            429 -> "请求太频繁或并发超限（429），稍等再试。"
            503 -> "服务繁忙（503），过一会儿再试。"
            504 -> "解析超时（504）：文件页数可能太多（上限 1000 页）或解析太慢，建议拆分后再传。"
            else -> "HTTP $code：${msg.take(300)}"
        }
    }

    /** 单文件上限（服务端 100 MiB），客户端先拦一道 */
    const val MAX_UPLOAD_BYTES: Long = 100L * 1024 * 1024

    /** 超过这个体积就提示用户「会比较慢」 */
    const val SLOW_UPLOAD_BYTES: Long = 20L * 1024 * 1024

    private suspend fun postBytes(
        url: String,
        settings: AppSettings,
        contentType: String,
        body: ByteArray,
        timeoutMs: Int
    ): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("x-api-key", settings.apiKey.trim())
                setRequestProperty("Authorization", "Bearer ${settings.apiKey.trim()}")
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException(friendlyError(code, text))
            text
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun buildMultipart(
        boundary: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        fileBytes: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fields.forEach { (k, v) ->
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
            write("$v\r\n")
        }
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n")
        write("Content-Type: application/octet-stream\r\n\r\n")
        out.write(fileBytes)
        write("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    /**
     * SSE 行解析：官方流里混着 `: keep-alive` 注释，事件是 event: / data: 两行一组，
     * 空行表示一个事件结束。
     */
    private class SseParser(private val onEvent: (String, String) -> Unit) {
        private var eventName: String = ""
        private val data = StringBuilder()

        fun feedLine(line: String) {
            if (line.isEmpty()) {
                flush()
                return
            }
            when {
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> eventName = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trim())
                }
            }
        }

        private fun flush() {
            if (data.isEmpty()) {
                eventName = ""
                return
            }
            val name = eventName
            val payload = data.toString()
            eventName = ""
            data.setLength(0)
            onEvent(name, payload)
        }
    }
}
