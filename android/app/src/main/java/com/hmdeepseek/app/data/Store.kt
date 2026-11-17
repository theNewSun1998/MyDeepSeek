package com.hmdeepseek.app.data

import android.content.Context
import com.hmdeepseek.app.model.AppSettings
import com.hmdeepseek.app.model.Conversation
import com.hmdeepseek.app.model.JsonCodec
import org.json.JSONArray
import org.json.JSONObject

/** 本地存储：设置 + 全部会话，直接存成一个 JSON 字符串（量级完全够用） */
class Store private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("deepseek_app", Context.MODE_PRIVATE)

    var settings: AppSettings = AppSettings()
        private set

    var conversations: MutableList<Conversation> = mutableListOf()
        private set

    init {
        load()
    }

    private fun load() {
        val settingsRaw = prefs.getString(KEY_SETTINGS, null)
        if (!settingsRaw.isNullOrEmpty()) {
            runCatching { settings = JsonCodec.settingsFromJson(JSONObject(settingsRaw)) }
        }
        val convsRaw = prefs.getString(KEY_CONVS, null)
        if (!convsRaw.isNullOrEmpty()) {
            runCatching {
                val arr = JSONArray(convsRaw)
                val list = mutableListOf<Conversation>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(JsonCodec.conversationFromJson(o))
                }
                conversations = list
            }
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

    fun hasKey(): Boolean = settings.apiKey.trim().isNotEmpty()

    fun sorted(): List<Conversation> = conversations.sortedByDescending { it.updatedAt }

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
            .sortedByDescending { it.updatedAt }
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

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_CONVS = "conversations"

        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context).also { instance = it }
            }
    }
}
