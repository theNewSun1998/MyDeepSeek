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
    val createdAt: Long = System.currentTimeMillis()
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新的对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    var apiKey: String = "",
    var baseUrl: String = "https://api.deepseek.com",
    var model: String = "deepseek-flash",
    var safetyLevel: String = "high",
    var searchDefault: Boolean = true,
    var thinkingDefault: Boolean = true,
    var parsePurpose: String = "text_recognition",
    var systemPrompt: String = ""
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

    fun messageFromJson(o: JSONObject): ChatMessage = ChatMessage(
        id = o.optString("id", UUID.randomUUID().toString()),
        role = o.optString("role", "user"),
        text = o.optString("text", ""),
        thinking = o.optString("thinking", ""),
        thinkingMs = o.optLong("thinkingMs", 0L),
        rawContentJson = o.optString("raw", ""),
        status = o.optString("status", "done"),
        errorText = o.optString("error", ""),
        createdAt = o.optLong("createdAt", 0L),
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

    fun conversationToJson(c: Conversation): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put("messages", JSONArray().apply { c.messages.forEach { put(messageToJson(it)) } })
    }

    fun conversationFromJson(o: JSONObject): Conversation = Conversation(
        id = o.optString("id", UUID.randomUUID().toString()),
        title = o.optString("title", "新的对话"),
        createdAt = o.optLong("createdAt", 0L),
        updatedAt = o.optLong("updatedAt", 0L),
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
    }

    fun settingsFromJson(o: JSONObject): AppSettings = AppSettings(
        apiKey = o.optString("apiKey", ""),
        baseUrl = o.optString("baseUrl", "https://api.deepseek.com"),
        model = o.optString("model", "deepseek-flash"),
        safetyLevel = o.optString("safetyLevel", "high"),
        searchDefault = o.optBoolean("searchDefault", true),
        thinkingDefault = o.optBoolean("thinkingDefault", true),
        parsePurpose = o.optString("parsePurpose", "text_recognition"),
        systemPrompt = o.optString("systemPrompt", "")
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
