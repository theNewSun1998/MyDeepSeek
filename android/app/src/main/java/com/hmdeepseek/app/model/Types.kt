package com.hmdeepseek.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Citation(
    val url: String = "",
    val title: String = "",
    val citedText: String = ""
)

data class SearchHit(
    val title: String = "",
    val url: String = "",
    val siteName: String = "",
    val snippet: String = "",
    val pageAge: String = "",
    val imageUrl: String = ""
)

data class SearchStep(
    val kind: String = "search",
    val action: String = "search",
    val queries: List<String> = emptyList(),
    val url: String = "",
    val pattern: String = "",
    val hits: List<SearchHit> = emptyList(),
    val fetchTitle: String = "",
    val fetchUrl: String = "",
    val errorCode: String = ""
)

data class Attachment(
    val fileId: String = "",
    val name: String = "",
    val sizeBytes: Long = 0L,
    val isImage: Boolean = false,
    val uploading: Boolean = false,
    val errorText: String = ""
)

/** 单轮回答的用量（来自服务端 usage） */
data class UsageInfo(
    val inputTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val outputTokens: Int = 0,
    val searchCount: Int = 0,
    val fetchCount: Int = 0
) {
    val isEmpty: Boolean
        get() = inputTokens == 0 && outputTokens == 0 && searchCount == 0 && fetchCount == 0
}

/** 累计用量（本地统计，跨会话） */
data class UsageStats(
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val searchCount: Long = 0L,
    val fetchCount: Long = 0L,
    val answers: Long = 0L
)

/**
 * 一条消息。rawContentJson 是服务端原文（含 thinking 的 signature、检索块），
 * 下一轮必须原样回传，否则模型会忘记刚看过的网页。
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String = "user",
    val text: String = "",
    val thinking: String = "",
    val thinkingMs: Long = 0L,
    val steps: List<SearchStep> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val rawContentJson: String = "",
    val status: String = "done",
    val errorText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val usage: UsageInfo = UsageInfo(),
    /** 本轮因超长被自动压缩时，这里放一句给用户看的说明 */
    val compressedNote: String = ""
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新的对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false
)

data class AppSettings(
    var apiKey: String = "",
    var baseUrl: String = "https://api.deepseek.com",
    var model: String = "deepseek-flash",
    var safetyLevel: String = "high",
    var searchDefault: Boolean = true,
    var thinkingDefault: Boolean = true,
    var parsePurpose: String = "text_recognition",
    var systemPrompt: String = "",
    /** 上下文估算超过这个 token 数就自动压缩早期轮次（0 = 不压缩） */
    var compressThresholdTokens: Int = 128_000,
    /** system | light | dark */
    var themeMode: String = "system",
    /** 单轮工具调用上限（搜索+抓页），0 = 不限 */
    var toolCallLimit: Int = 8
)

data class SendOptions(
    val search: Boolean = true,
    val thinking: Boolean = true
)

object JsonCodec {

    fun messageToJson(m: ChatMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("role", m.role)
        put("text", m.text)
        put("thinking", m.thinking)
        put("thinkingMs", m.thinkingMs)
        put("raw", m.rawContentJson)
        put("status", m.status)
        put("error", m.errorText)
        put("createdAt", m.createdAt)
        put("compressedNote", m.compressedNote)
        put("usage", JSONObject().apply {
            put("input", m.usage.inputTokens)
            put("cacheHit", m.usage.cacheHitTokens)
            put("output", m.usage.outputTokens)
            put("search", m.usage.searchCount)
            put("fetch", m.usage.fetchCount)
        })
        put("citations", JSONArray().apply {
            m.citations.forEach { c ->
                put(JSONObject().apply {
                    put("url", c.url)
                    put("title", c.title)
                    put("citedText", c.citedText)
                })
            }
        })
        put("steps", JSONArray().apply {
            m.steps.forEach { s ->
                put(JSONObject().apply {
                    put("kind", s.kind)
                    put("action", s.action)
                    put("queries", JSONArray(s.queries))
                    put("url", s.url)
                    put("pattern", s.pattern)
                    put("fetchTitle", s.fetchTitle)
                    put("fetchUrl", s.fetchUrl)
                    put("errorCode", s.errorCode)
                    put("hits", JSONArray().apply {
                        s.hits.forEach { h ->
                            put(JSONObject().apply {
                                put("title", h.title)
                                put("url", h.url)
                                put("siteName", h.siteName)
                                put("snippet", h.snippet)
                                put("pageAge", h.pageAge)
                                put("imageUrl", h.imageUrl)
                            })
                        }
                    })
                })
            }
        })
        put("attachments", JSONArray().apply {
            m.attachments.forEach { a ->
                put(JSONObject().apply {
                    put("fileId", a.fileId)
                    put("name", a.name)
                    put("size", a.sizeBytes)
                    put("isImage", a.isImage)
                })
            }
        })
    }

    fun messageFromJson(o: JSONObject): ChatMessage {
        val usageObj = o.optJSONObject("usage")
        return ChatMessage(
            id = o.optString("id", UUID.randomUUID().toString()),
            role = o.optString("role", "user"),
            text = o.optString("text", ""),
            thinking = o.optString("thinking", ""),
            thinkingMs = o.optLong("thinkingMs", 0L),
            rawContentJson = o.optString("raw", ""),
            status = o.optString("status", "done"),
            errorText = o.optString("error", ""),
            compressedNote = o.optString("compressedNote", ""),
            createdAt = o.optLong("createdAt", 0L),
            usage = if (usageObj == null) UsageInfo() else UsageInfo(
                inputTokens = usageObj.optInt("input", 0),
                cacheHitTokens = usageObj.optInt("cacheHit", 0),
                outputTokens = usageObj.optInt("output", 0),
                searchCount = usageObj.optInt("search", 0),
                fetchCount = usageObj.optInt("fetch", 0)
            ),
            citations = o.optJSONArray("citations").mapObjects { c ->
                Citation(c.optString("url"), c.optString("title"), c.optString("citedText"))
            },
            steps = o.optJSONArray("steps").mapObjects { s ->
                SearchStep(
                    kind = s.optString("kind", "search"),
                    action = s.optString("action", "search"),
                    queries = s.optJSONArray("queries").strings(),
                    url = s.optString("url", ""),
                    pattern = s.optString("pattern", ""),
                    fetchTitle = s.optString("fetchTitle", ""),
                    fetchUrl = s.optString("fetchUrl", ""),
                    errorCode = s.optString("errorCode", ""),
                    hits = s.optJSONArray("hits").mapObjects { h ->
                        SearchHit(
                            title = h.optString("title", ""),
                            url = h.optString("url", ""),
                            siteName = h.optString("siteName", ""),
                            snippet = h.optString("snippet", ""),
                            pageAge = h.optString("pageAge", ""),
                            imageUrl = h.optString("imageUrl", "")
                        )
                    }
                )
            },
            attachments = o.optJSONArray("attachments").mapObjects { a ->
                Attachment(
                    fileId = a.optString("fileId", ""),
                    name = a.optString("name", ""),
                    sizeBytes = a.optLong("size", 0L),
                    isImage = a.optBoolean("isImage", false)
                )
            }
        )
    }

    fun conversationToJson(c: Conversation): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put("pinned", c.pinned)
        put("messages", JSONArray().apply { c.messages.forEach { put(messageToJson(it)) } })
    }

    fun conversationFromJson(o: JSONObject): Conversation = Conversation(
        id = o.optString("id", UUID.randomUUID().toString()),
        title = o.optString("title", "新的对话"),
        createdAt = o.optLong("createdAt", 0L),
        updatedAt = o.optLong("updatedAt", 0L),
        pinned = o.optBoolean("pinned", false),
        messages = o.optJSONArray("messages").mapObjects { messageFromJson(it) }
    )

    fun settingsToJson(s: AppSettings): JSONObject = JSONObject().apply {
        put("apiKey", s.apiKey)
        put("baseUrl", s.baseUrl)
        put("model", s.model)
        put("safetyLevel", s.safetyLevel)
        put("searchDefault", s.searchDefault)
        put("thinkingDefault", s.thinkingDefault)
        put("parsePurpose", s.parsePurpose)
        put("systemPrompt", s.systemPrompt)
        put("compressThresholdTokens", s.compressThresholdTokens)
        put("themeMode", s.themeMode)
        put("toolCallLimit", s.toolCallLimit)
    }

    fun settingsFromJson(o: JSONObject): AppSettings = AppSettings(
        apiKey = o.optString("apiKey", ""),
        baseUrl = o.optString("baseUrl", "https://api.deepseek.com"),
        model = o.optString("model", "deepseek-flash"),
        safetyLevel = o.optString("safetyLevel", "high"),
        searchDefault = o.optBoolean("searchDefault", true),
        thinkingDefault = o.optBoolean("thinkingDefault", true),
        parsePurpose = o.optString("parsePurpose", "text_recognition"),
        systemPrompt = o.optString("systemPrompt", ""),
        compressThresholdTokens = o.optInt("compressThresholdTokens", 128_000),
        themeMode = o.optString("themeMode", "system"),
        toolCallLimit = o.optInt("toolCallLimit", 8)
    )

    fun usageStatsToJson(u: UsageStats): JSONObject = JSONObject().apply {
        put("inputTokens", u.inputTokens)
        put("cacheHitTokens", u.cacheHitTokens)
        put("outputTokens", u.outputTokens)
        put("searchCount", u.searchCount)
        put("fetchCount", u.fetchCount)
        put("answers", u.answers)
    }

    fun usageStatsFromJson(o: JSONObject): UsageStats = UsageStats(
        inputTokens = o.optLong("inputTokens", 0L),
        cacheHitTokens = o.optLong("cacheHitTokens", 0L),
        outputTokens = o.optLong("outputTokens", 0L),
        searchCount = o.optLong("searchCount", 0L),
        fetchCount = o.optLong("fetchCount", 0L),
        answers = o.optLong("answers", 0L)
    )
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it, "") }
}

private fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i)
        if (o != null) out.add(block(o))
    }
    return out
}
