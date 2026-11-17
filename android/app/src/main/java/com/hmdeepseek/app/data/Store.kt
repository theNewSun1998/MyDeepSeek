package com.hmdeepseek.app.data

import android.content.Context
import com.hmdeepseek.app.model.AppSettings
import com.hmdeepseek.app.model.Conversation
import com.hmdeepseek.app.model.JsonCodec
import com.hmdeepseek.app.model.UsageInfo
import com.hmdeepseek.app.model.UsageStats
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 本地存储：设置 + 全部会话 + 累计用量 */
class Store private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("deepseek_app", Context.MODE_PRIVATE)

    var settings: AppSettings = AppSettings()
        private set

    var conversations: MutableList<Conversation> = mutableListOf()
        private set

    var usage: UsageStats = UsageStats()
        private set

    init {
        load()
    }

    private fun load() {
        runCatching {
            val raw = prefs.getString(KEY_SETTINGS, null)
            if (!raw.isNullOrEmpty()) settings = JsonCodec.settingsFromJson(JSONObject(raw))
        }
        runCatching {
            val raw = prefs.getString(KEY_CONVS, null)
            if (!raw.isNullOrEmpty()) {
                val arr = JSONArray(raw)
                val list = mutableListOf<Conversation>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(JsonCodec.conversationFromJson(o))
                }
                conversations = list
            }
        }
        runCatching {
            val raw = prefs.getString(KEY_USAGE, null)
            if (!raw.isNullOrEmpty()) usage = JsonCodec.usageStatsFromJson(JSONObject(raw))
        }
    }

    fun updateSettings(block: (AppSettings) -> Unit) {
        block(settings)
        prefs.edit().putString(KEY_SETTINGS, JsonCodec.settingsToJson(settings).toString()).apply()
    }

    fun saveConversations() {
        val arr = JSONArray()
        conversations.forEach { arr.put(JsonCodec.conversationToJson(it)) }
        prefs.edit().putString(KEY_CONVS, arr.toString()).apply()
    }

    fun saveConversation(conv: Conversation) {
        val idx = conversations.indexOfFirst { it.id == conv.id }
        if (idx >= 0) conversations[idx] = conv else conversations.add(conv)
        saveConversations()
    }

    fun saveUsage() {
        prefs.edit().putString(KEY_USAGE, JsonCodec.usageStatsToJson(usage).toString()).apply()
    }

    fun addUsage(info: UsageInfo) {
        usage = usage.copy(
            inputTokens = usage.inputTokens + info.inputTokens,
            cacheHitTokens = usage.cacheHitTokens + info.cacheHitTokens,
            outputTokens = usage.outputTokens + info.outputTokens,
            searchCount = usage.searchCount + info.searchCount,
            fetchCount = usage.fetchCount + info.fetchCount,
            answers = usage.answers + 1
        )
        saveUsage()
    }

    fun resetUsage() {
        usage = UsageStats()
        saveUsage()
    }

    fun hasKey(): Boolean = settings.apiKey.trim().isNotEmpty()

    /** 置顶的排前面，其余按更新时间倒序 */
    fun sorted(): List<Conversation> = conversations.sortedWith(
        compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt }
    )

    /** 侧边栏搜索：标题命中，或任意一条消息正文/思考内容命中 */
    fun search(query: String): List<Conversation> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return sorted()
        return conversations
            .filter { conv ->
                conv.title.lowercase().contains(q) ||
                    conv.messages.any { msg ->
                        msg.text.lowercase().contains(q) ||
                            msg.thinking.lowercase().contains(q)
                    }
            }
            .sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun find(id: String): Conversation? = conversations.firstOrNull { it.id == id }

    fun create(): Conversation {
        val conv = Conversation()
        conversations.add(conv)
        saveConversations()
        return conv
    }

    fun remove(id: String) {
        conversations.removeAll { it.id == id }
        saveConversations()
    }

    fun clearAll() {
        conversations.clear()
        saveConversations()
    }

    fun rename(id: String, title: String) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        conversations[idx] = conversations[idx].copy(title = title.trim().ifEmpty { "新的对话" })
        saveConversations()
    }

    fun setPinned(id: String, pinned: Boolean) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx < 0) return
        conversations[idx] = conversations[idx].copy(pinned = pinned)
        saveConversations()
    }

    /** 导出全部会话为 JSON（含用量，方便换机/备份） */
    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "hmdeepseek")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("conversations", JSONArray().apply { conversations.forEach { put(JsonCodec.conversationToJson(it)) } })
        root.put("usage", JsonCodec.usageStatsToJson(usage))
        return root.toString(2)
    }

    /** 导入：合并（id 冲突时直接新增一条新 id），返回导入/跳过数量 */
    fun importJson(text: String): Pair<Int, Int> {
        var added = 0
        var skipped = 0
        runCatching {
            val trimmed = text.trim()
            val arr: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> {
                    val root = JSONObject(trimmed)
                    root.optJSONObject("usage")?.let { usage = JsonCodec.usageStatsFromJson(it); saveUsage() }
                    root.optJSONArray("conversations") ?: JSONArray()
                }
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val conv = runCatching { JsonCodec.conversationFromJson(o) }.getOrNull()
                if (conv == null || conv.messages.isEmpty()) {
                    skipped++
                    continue
                }
                val id = if (conversations.any { it.id == conv.id }) UUID.randomUUID().toString() else conv.id
                conversations.add(conv.copy(id = id))
                added++
            }
            if (added > 0) saveConversations()
        }.onFailure { skipped++ }
        return added to skipped
    }

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_CONVS = "conversations"
        private const val KEY_USAGE = "usage"

        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context).also { instance = it }
            }
    }
}
