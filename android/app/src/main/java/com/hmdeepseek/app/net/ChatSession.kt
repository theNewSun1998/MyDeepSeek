package com.hmdeepseek.app.net

import com.hmdeepseek.app.common.Tokens
import com.hmdeepseek.app.model.AppSettings
import com.hmdeepseek.app.model.Attachment
import com.hmdeepseek.app.model.ChatMessage
import com.hmdeepseek.app.model.Citation
import com.hmdeepseek.app.model.Conversation
import com.hmdeepseek.app.model.SearchHit
import com.hmdeepseek.app.model.SearchStep
import com.hmdeepseek.app.model.SendOptions
import com.hmdeepseek.app.model.UsageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TreeMap

/**
 * 一轮对话的状态机：拼 Messages 请求 → 解流 → 累积 block → 回调快照 → 落盘。
 *
 * 助手消息的 content 块会原样保存（text/thinking 重建、工具块用服务端原文），
 * 下一轮直接拼回去：signature 里压着读过的网页，动了模型就"失忆"。
 */
class ChatSession(
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val onSnapshot: (messages: List<ChatMessage>, streaming: Boolean, title: String) -> Unit,
    private val onPersist: (Conversation) -> Unit,
    private val onUsage: (UsageInfo) -> Unit = {},
    private val onStreamingChanged: (Boolean) -> Unit = {}
) {

    private var messages: MutableList<ChatMessage> = mutableListOf()
    private var convId: String = ""
    private var title: String = "新的对话"
    private var createdAt: Long = System.currentTimeMillis()
    private var streaming: Boolean = false
    private var call: DeepSeekApi.StreamCall? = null
    private var job: Job? = null

    private val blocks = TreeMap<Int, BlockAcc>()
    private val steps = mutableListOf<SearchStep>()
    private val stepOfTool = mutableMapOf<String, Int>()
    private var lastEmit: Long = 0L
    private var thinkingStart: Long = 0L
    private var firstTextAt: Long = 0L
    private var thinkingMs: Long = 0L

    // 本轮用量
    private var inputTokens: Int = 0
    private var cacheHitTokens: Int = 0
    private var outputTokens: Int = 0
    private var searchCount: Int = 0
    private var fetchCount: Int = 0

    // 本轮请求体（重试要用）与重试次数
    private var lastBody: String = ""
    private var attempts: Int = 0
    private var compressionNote: String = ""

    /** UI 线程发消息、IO 线程收流式，所有状态改动都过这把锁 */
    private val lock = Any()

    private class BlockAcc(val type: String) {
        var text: String = ""
        var thinking: String = ""
        var signature: String = ""
        var raw: String = ""
        val citations = mutableListOf<Citation>()
    }

    fun load(conv: Conversation) {
        convId = conv.id
        title = conv.title
        createdAt = conv.createdAt
        messages = conv.messages.toMutableList()
        streaming = false
        resetAccumulators()
        onSnapshot(messages.toList(), false, title)
    }

    fun isStreaming(): Boolean = streaming

    fun send(text: String, attachments: List<Attachment>, opts: SendOptions) {
        synchronized(lock) {
            if (streaming) return
            messages.add(
                ChatMessage(
                    role = "user",
                    text = text,
                    attachments = attachments.filter { it.fileId.isNotEmpty() }
                )
            )
            if (title == "新的对话" || title.isEmpty()) {
                title = makeTitle(text)
            }
            start(opts)
        }
    }

    fun regenerate(opts: SendOptions) {
        synchronized(lock) {
            if (streaming) return
            while (messages.isNotEmpty() && messages.last().role == "assistant") {
                messages.removeAt(messages.size - 1)
            }
            if (messages.isEmpty()) return
            start(opts)
        }
    }

    fun stop() {
        synchronized(lock) {
            call?.cancel()
            call = null
            job?.cancel()
            job = null
            if (!streaming) return
            streaming = false
            commitUsage()
            if (messages.isNotEmpty() && messages.last().role == "assistant") {
                messages[messages.size - 1] = messages.last().copy(status = "stopped")
            }
            persist()
            emit(true)
            onStreamingChanged(false)
        }
    }

    fun release() {
        call?.cancel()
        job?.cancel()
    }

    /** 切换会话时用：掐掉在跑的流，但不改已保存的内容 */
    fun abort() {
        synchronized(lock) {
            call?.cancel()
            call = null
            job?.cancel()
            job = null
            streaming = false
            onStreamingChanged(false)
        }
    }

    // ------------------------------------------------------------ 内部

    private fun resetAccumulators() {
        blocks.clear()
        steps.clear()
        stepOfTool.clear()
        thinkingMs = 0L
        firstTextAt = 0L
        thinkingStart = 0L
        inputTokens = 0
        cacheHitTokens = 0
        outputTokens = 0
        searchCount = 0
        fetchCount = 0
        attempts = 0
        compressionNote = ""
    }

    private fun start(opts: SendOptions) {
        streaming = true
        resetAccumulators()
        thinkingStart = System.currentTimeMillis()
        messages.add(ChatMessage(role = "assistant", status = "streaming"))
        emit(true)
        onStreamingChanged(true)

        lastBody = buildBody(opts)
        launchStream(lastBody)
    }

    private fun launchStream(body: String) {
        val c = DeepSeekApi.StreamCall()
        call = c
        job = scope.launch {
            DeepSeekApi.streamMessages(
                settings = settings(),
                body = body,
                withDocs = hasDocuments(),
                call = c,
                onEvent = { _, data -> handleEvent(data) },
                onDone = { stopped -> finish(if (stopped) "stopped" else "done") },
                onError = { message, code -> onStreamError(message, code) }
            )
        }
    }

    /** 429 / 网络中断 / 5xx 自动重试两次，其余直接报错 */
    private fun onStreamError(message: String, code: Int) {
        synchronized(lock) {
            val retryable = code == 0 || code == 429 || code >= 500
            if (retryable && attempts < 2 && streaming) {
                attempts++
                emit(true)
                scope.launch {
                    delay(1200L * attempts)
                    synchronized(lock) {
                        if (!streaming) return@launch
                        blocks.clear()
                        steps.clear()
                        stepOfTool.clear()
                        thinkingMs = 0
                        firstTextAt = 0
                        thinkingStart = System.currentTimeMillis()
                        if (messages.isNotEmpty() && messages.last().role == "assistant") {
                            messages[messages.size - 1] = messages.last().copy(
                                text = "",
                                thinking = "",
                                steps = emptyList(),
                                citations = emptyList(),
                                rawContentJson = "",
                                status = "streaming",
                                errorText = "网络抖动，正在重试（第 $attempts 次）…"
                            )
                        }
                        emit(true)
                    }
                    launchStream(lastBody)
                }
            } else {
                fail(message)
            }
        }
    }

    private fun hasDocuments(): Boolean = messages.any { m -> m.attachments.any { it.fileId.isNotEmpty() } }

    private fun buildBody(opts: SendOptions): String {
        val s = settings()
        val history = compressIfNeeded()
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"model\":").append(JSONObject.quote(s.model)).append(',')
        sb.append("\"max_tokens\":8192,")
        sb.append("\"stream\":true,")
        if (opts.thinking) {
            sb.append("\"thinking\":{\"type\":\"enabled\"},")
        }
        val tools = mutableListOf<String>()
        if (opts.search) {
            tools.add("{\"type\":\"deepseek_web_search\",\"name\":\"deepseek_web_search\"}")
        }
        tools.add("{\"type\":\"deepseek_read_url\",\"name\":\"deepseek_read_url\"}")
        sb.append("\"tools\":[").append(tools.joinToString(",")).append("],")
        if (s.safetyLevel != "off") {
            sb.append("\"safety_check\":{\"level\":").append(JSONObject.quote(s.safetyLevel)).append("},")
        }
        sb.append("\"title\":{\"enabled\":true},")

        val systemParts = mutableListOf<String>()
        if (s.systemPrompt.isNotBlank()) systemParts.add(s.systemPrompt)
        if (compressionNote.isNotEmpty()) systemParts.add("【提示】$compressionNote")
        if (s.toolCallLimit > 0) {
            systemParts.add("【提示】联网工具请尽量在 ${s.toolCallLimit} 次调用内完成。")
        }
        if (systemParts.isNotEmpty()) {
            sb.append("\"system\":").append(JSONObject.quote(systemParts.joinToString("\n"))).append(',')
        }

        sb.append("\"messages\":[").append(messagesJson(history)).append(']')
        sb.append('}')
        return sb.toString()
    }

    /**
     * 自动压缩：估算整段历史的 token，超过设置阈值就丢掉最早的若干条，
     * 并保证保留下来的第一条是 user（assistant 不能单独开头）。
     */
    private fun compressIfNeeded(): List<ChatMessage> {
        compressionNote = ""
        val threshold = settings().compressThresholdTokens
        if (threshold <= 0) return messages

        val total = Tokens.estimateMessages(
            messages.map { if (it.role == "user") it.text else it.text + it.thinking }
        )
        if (total <= threshold) return messages

        val kept = mutableListOf<ChatMessage>()
        var used = 0
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            val t = Tokens.estimate(m.text) + Tokens.estimate(m.thinking) + 8
            if (kept.isNotEmpty() && used + t > threshold * 3 / 4) break
            used += t
            kept.add(0, m)
        }
        if (kept.size >= messages.size) return messages
        while (kept.isNotEmpty() && kept.first().role != "user") {
            kept.removeAt(0)
        }
        if (kept.isEmpty()) return messages

        val dropped = messages.size - kept.size
        compressionNote = "更早的 $dropped 条消息已省略（估算 $total tokens，超过压缩线 $threshold）"
        return kept
    }

    private fun messagesJson(history: List<ChatMessage>): String {
        val out = mutableListOf<String>()
        for (m in history) {
            if (m.role == "user") {
                out.add("{\"role\":\"user\",\"content\":${userContentJson(m)}}")
            } else if (m.rawContentJson.length > 2) {
                out.add("{\"role\":\"assistant\",\"content\":${m.rawContentJson}}")
            }
        }
        return out.joinToString(",")
    }

    private fun userContentJson(m: ChatMessage): String {
        val docs = m.attachments.filter { it.fileId.isNotEmpty() }
        if (docs.isEmpty()) return JSONObject.quote(m.text)
        val blocksJson = mutableListOf<String>()
        docs.forEach {
            blocksJson.add(
                "{\"type\":\"document\",\"source\":{\"type\":\"file\",\"file_id\":${JSONObject.quote(it.fileId)}}}"
            )
        }
        blocksJson.add("{\"type\":\"text\",\"text\":${JSONObject.quote(m.text)}}")
        return "[" + blocksJson.joinToString(",") + "]"
    }

    private fun handleEvent(data: String) {
        synchronized(lock) {
            val o = runCatching { JSONObject(data) }.getOrNull() ?: return
            when (o.optString("type")) {
                "message_start" -> readUsage(o.optJSONObject("message") ?: JSONObject())
                "content_block_start" -> onBlockStart(o)
                "content_block_delta" -> onBlockDelta(o)
                "message_delta" -> {
                    readUsage(o)
                    val d = o.optJSONObject("delta")
                    val stopReason = d?.optString("stop_reason").orEmpty()
                    val serverTitle = d?.optString("title").orEmpty()
                    if (serverTitle.isNotEmpty()) title = serverTitle
                    if (stopReason == "refusal") finish("refused")
                }
                "message_stop" -> finish("done")
                "error" -> fail(o.optJSONObject("error")?.optString("message") ?: "服务端返回错误")
            }
            rebuild()
            emit(false)
            maybeStopForToolLimit()
        }
    }

    private fun readUsage(o: JSONObject) {
        val u = o.optJSONObject("usage") ?: return
        inputTokens = maxOf(inputTokens, u.optInt("input_tokens", 0))
        cacheHitTokens = maxOf(cacheHitTokens, u.optInt("cache_read_input_tokens", 0))
        outputTokens = maxOf(outputTokens, u.optInt("output_tokens", 0))
        u.optJSONObject("server_tool_use")?.let { t ->
            searchCount = maxOf(searchCount, t.optInt("web_search_requests", 0) + t.optInt("tool_image_search", 0))
            fetchCount = maxOf(fetchCount, t.optInt("web_fetch_requests", 0))
        }
    }

    private fun maybeStopForToolLimit() {
        val limit = settings().toolCallLimit
        if (limit <= 0 || !streaming) return
        if (steps.size <= limit) return
        call?.cancel()
        call = null
        finish("done", note = "本轮工具调用已达上限（$limit 次），已提前结束回答。可在设置里调大上限。")
    }

    private fun onBlockStart(o: JSONObject) {
        val index = o.optInt("index")
        val block = o.optJSONObject("content_block") ?: return
        val type = block.optString("type")
        val acc = BlockAcc(type)
        when (type) {
            "text" -> acc.text = block.optString("text")
            "thinking" -> {
                acc.thinking = block.optString("thinking")
                acc.signature = block.optString("signature")
            }
            else -> acc.raw = block.toString()
        }
        blocks[index] = acc
        when (type) {
            "server_tool_use" -> addStep(block)
            "web_search_tool_result" -> fillSearchResult(block)
            "web_fetch_tool_result" -> fillFetchResult(block)
        }
    }

    private fun onBlockDelta(o: JSONObject) {
        val acc = blocks[o.optInt("index")] ?: return
        val delta = o.optJSONObject("delta") ?: return
        when (delta.optString("type")) {
            "text_delta" -> {
                if (firstTextAt == 0L) {
                    firstTextAt = System.currentTimeMillis()
                    thinkingMs = firstTextAt - thinkingStart
                }
                acc.text += delta.optString("text")
            }
            "thinking_delta" -> acc.thinking += delta.optString("thinking")
            "signature_delta" -> acc.signature += delta.optString("signature")
            "citations_delta" -> {
                val c = delta.optJSONObject("citation") ?: return
                acc.citations.add(
                    Citation(
                        url = c.optString("url"),
                        title = c.optString("title"),
                        citedText = c.optString("cited_text")
                    )
                )
            }
        }
    }

    private fun addStep(block: JSONObject) {
        val input = block.optJSONObject("input") ?: JSONObject()
        val queries = mutableListOf<String>()
        input.optJSONArray("queries")?.let { arr ->
            for (i in 0 until arr.length()) queries.add(arr.optString(i))
        }
        steps.add(
            SearchStep(
                kind = block.optString("kind"),
                action = input.optString("action"),
                queries = queries,
                url = input.optString("url"),
                pattern = input.optString("pattern")
            )
        )
        val id = block.optString("id")
        if (id.isNotEmpty()) stepOfTool[id] = steps.size - 1
    }

    private fun fillSearchResult(block: JSONObject) {
        val idx = stepOfTool[block.optString("tool_use_id")] ?: return
        val content = block.optJSONArray("content") ?: return
        val hits = mutableListOf<SearchHit>()
        var errorCode = steps[idx].errorCode
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") == "web_search_tool_result_error") {
                errorCode = item.optString("error_code")
                continue
            }
            hits.add(
                SearchHit(
                    title = item.optString("title"),
                    url = item.optString("url"),
                    siteName = item.optString("site_name"),
                    snippet = item.optString("snippet"),
                    pageAge = item.optString("page_age"),
                    imageUrl = item.optString("image_url")
                )
            )
        }
        steps[idx] = steps[idx].copy(hits = hits, errorCode = errorCode)
    }

    private fun fillFetchResult(block: JSONObject) {
        val idx = stepOfTool[block.optString("tool_use_id")] ?: return
        val content = block.optJSONObject("content") ?: return
        if (content.optString("type") == "web_fetch_tool_result_error") {
            steps[idx] = steps[idx].copy(errorCode = content.optString("error_code"))
            return
        }
        val url = content.optString("url")
        val docTitle = content.optJSONObject("content")?.optString("title").orEmpty()
        steps[idx] = steps[idx].copy(fetchUrl = url, fetchTitle = docTitle.ifEmpty { url })
    }

    private fun rebuild() {
        if (messages.isEmpty()) return
        val last = messages.last()
        if (last.role != "assistant") return

        val textSb = StringBuilder()
        val thinkSb = StringBuilder()
        val cites = mutableListOf<Citation>()
        val parts = mutableListOf<String>()

        for ((_, acc) in blocks) {
            when (acc.type) {
                "text" -> {
                    textSb.append(acc.text)
                    parts.add("{\"type\":\"text\",\"text\":${JSONObject.quote(acc.text)}}")
                    cites.addAll(acc.citations)
                }
                "thinking" -> {
                    thinkSb.append(acc.thinking)
                    parts.add(
                        "{\"type\":\"thinking\",\"thinking\":${JSONObject.quote(acc.thinking)}," +
                            "\"signature\":${JSONObject.quote(acc.signature)}}"
                    )
                }
                else -> if (acc.raw.isNotEmpty()) parts.add(acc.raw)
            }
        }

        messages[messages.size - 1] = last.copy(
            text = textSb.toString(),
            thinking = thinkSb.toString(),
            steps = steps.toList(),
            citations = cites,
            rawContentJson = "[" + parts.joinToString(",") + "]",
            thinkingMs = thinkingMs,
            usage = currentUsage(),
            compressedNote = compressionNote
        )
    }

    private fun currentUsage(): UsageInfo = UsageInfo(
        inputTokens = inputTokens,
        cacheHitTokens = cacheHitTokens,
        outputTokens = outputTokens,
        searchCount = searchCount,
        fetchCount = fetchCount
    )

    /** 把累计用量记到消息上并上报给统计 */
    private fun commitUsage() {
        if (messages.isEmpty()) return
        val last = messages.last()
        if (last.role != "assistant") return
        val info = currentUsage()
        messages[messages.size - 1] = last.copy(usage = info)
        if (!info.isEmpty) onUsage(info)
    }

    private fun finish(status: String, note: String = "") {
        if (!streaming) return
        streaming = false
        call = null
        job?.cancel()
        job = null
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            val last = messages.last()
            val empty = last.text.isEmpty() && last.thinking.isEmpty() && last.steps.isEmpty()
            messages[messages.size - 1] = last.copy(
                status = if (empty && status == "done") "error" else status,
                errorText = if (empty && status == "done") "没有收到内容，换个说法或关掉联网再试。" else last.errorText,
                compressedNote = note.ifEmpty { compressionNote }
            )
        }
        commitUsage()
        persist()
        emit(true)
        onStreamingChanged(false)
    }

    private fun fail(message: String) {
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            messages[messages.size - 1] = messages.last()
                .copy(status = "error", errorText = message)
        }
        streaming = false
        call?.cancel()
        call = null
        job?.cancel()
        job = null
        persist()
        emit(true)
        onStreamingChanged(false)
    }

    private fun emit(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmit < 60) return
        lastEmit = now
        onSnapshot(messages.toList(), streaming, title)
    }

    private fun persist() {
        onPersist(
            Conversation(
                id = convId,
                title = title,
                messages = messages.toList(),
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun makeTitle(text: String): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return "新的对话"
        return if (cleaned.length > 24) cleaned.take(24) + "…" else cleaned
    }
}
