package com.hmdeepseek.app.net

import com.hmdeepseek.app.model.AppSettings
import com.hmdeepseek.app.model.Attachment
import com.hmdeepseek.app.model.ChatMessage
import com.hmdeepseek.app.model.Citation
import com.hmdeepseek.app.model.Conversation
import com.hmdeepseek.app.model.SearchHit
import com.hmdeepseek.app.model.SearchStep
import com.hmdeepseek.app.model.SendOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val onPersist: (Conversation) -> Unit
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
            if (!streaming) return
            streaming = false
            if (messages.isNotEmpty() && messages.last().role == "assistant") {
                messages[messages.size - 1] = messages.last().copy(status = "stopped")
            }
            persist()
            emit(true)
        }
    }

    fun release() {
        call?.cancel()
        job?.cancel()
    }

    // ------------------------------------------------------------ 内部

    private fun resetAccumulators() {
        blocks.clear()
        steps.clear()
        stepOfTool.clear()
        thinkingMs = 0L
        firstTextAt = 0L
        thinkingStart = 0L
    }

    private fun start(opts: SendOptions) {
        streaming = true
        resetAccumulators()
        thinkingStart = System.currentTimeMillis()
        messages.add(ChatMessage(role = "assistant", status = "streaming"))
        emit(true)

        val body = buildBody(opts)
        val withDocs = messages.any { m -> m.attachments.any { it.fileId.isNotEmpty() } }
        val c = DeepSeekApi.StreamCall()
        call = c
        job = scope.launch {
            DeepSeekApi.streamMessages(
                settings = settings(),
                body = body,
                withDocs = withDocs,
                call = c,
                onEvent = { _, data -> handleEvent(data) },
                onDone = { stopped -> finish(if (stopped) "stopped" else "done") },
                onError = { message -> fail(message) }
            )
        }
    }

    private fun buildBody(opts: SendOptions): String {
        val s = settings()
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
        if (s.systemPrompt.isNotBlank()) {
            sb.append("\"system\":").append(JSONObject.quote(s.systemPrompt)).append(',')
        }
        sb.append("\"messages\":[").append(messagesJson()).append(']')
        sb.append('}')
        return sb.toString()
    }

    private fun messagesJson(): String {
        val out = mutableListOf<String>()
        for (m in messages) {
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
                "content_block_start" -> onBlockStart(o)
                "content_block_delta" -> onBlockDelta(o)
                "message_delta" -> {
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
        }
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
            thinkingMs = thinkingMs
        )
    }

    private fun finish(status: String) {
        if (!streaming) return
        streaming = false
        call = null
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            val last = messages.last()
            val empty = last.text.isEmpty() && last.thinking.isEmpty() && last.steps.isEmpty()
            messages[messages.size - 1] = last.copy(
                status = if (empty) "error" else status,
                errorText = if (empty) "没有收到内容，换个说法或关掉联网再试。" else last.errorText
            )
        }
        persist()
        emit(true)
    }

    private fun fail(message: String) {
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            messages[messages.size - 1] = messages.last()
                .copy(status = "error", errorText = message)
        }
        streaming = false
        call?.cancel()
        call = null
        persist()
        emit(true)
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
