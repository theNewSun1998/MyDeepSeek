package com.hmdeepseek.app

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hmdeepseek.app.audio.WavRecorder
import com.hmdeepseek.app.data.Store
import com.hmdeepseek.app.model.AppSettings
import com.hmdeepseek.app.model.Attachment
import com.hmdeepseek.app.model.ChatMessage
import com.hmdeepseek.app.model.Conversation
import com.hmdeepseek.app.model.SendOptions
import com.hmdeepseek.app.net.ChatSession
import com.hmdeepseek.app.net.DeepSeekApi
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val recorder = WavRecorder()

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var streaming by mutableStateOf(false)
        private set
    var title by mutableStateOf("新的对话")
        private set
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set
    var uploading by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var busyText by mutableStateOf("")
        private set
    var searchOn by mutableStateOf(true)
        private set
    var thinkingOn by mutableStateOf(true)
        private set
    var convId: String = ""
        private set

    private val session = ChatSession(
        settings = { store.settings },
        scope = viewModelScope,
        onSnapshot = { msgs, isStreaming, t ->
            main.post {
                messages = msgs
                streaming = isStreaming
                title = t
            }
        },
        onPersist = { conv -> store.saveConversation(conv) }
    )

    fun load(id: String?) {
        val conv: Conversation = when {
            id.isNullOrEmpty() -> store.create()
            else -> store.find(id) ?: store.create()
        }
        attachments = emptyList()
        searchOn = store.settings.searchDefault
        thinkingOn = store.settings.thinkingDefault
        convId = conv.id
        session.load(conv)
    }

    fun toggleSearch() {
        searchOn = !searchOn
    }

    fun toggleThinking() {
        thinkingOn = !thinkingOn
    }

    fun send(text: String, onNeedKey: () -> Unit) {
        if (!store.hasKey()) {
            onNeedKey()
            return
        }
        if (streaming || uploading) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        val atts = attachments
        attachments = emptyList()
        session.send(trimmed, atts, SendOptions(search = searchOn, thinking = thinkingOn))
    }

    fun regenerate() {
        if (!streaming) session.regenerate(SendOptions(search = searchOn, thinking = thinkingOn))
    }

    fun stop() {
        session.stop()
    }

    fun removeAttachment(index: Int) {
        attachments = attachments.filterIndexed { i, _ -> i != index }
    }

    fun attach(uri: Uri) {
        if (!store.hasKey()) return
        val resolver = getApplication<Application>().contentResolver
        val name = queryFileName(uri) ?: "file"
        val placeholder = Attachment(name = name, uploading = true)
        attachments = attachments + placeholder
        uploading = true
        viewModelScope.launch {
            val result = runCatching {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("读取文件失败")
                DeepSeekApi.uploadFile(store.settings, bytes, name, store.settings.parsePurpose)
            }.getOrElse {
                DeepSeekApi.UploadResult(error = "上传失败：${it.message ?: "未知错误"}")
            }
            attachments = attachments.map { a ->
                if (a.name == placeholder.name && a.uploading) {
                    if (result.ok) {
                        a.copy(fileId = result.fileId, sizeBytes = result.bytes, uploading = false)
                    } else {
                        a.copy(uploading = false, errorText = result.error)
                    }
                } else {
                    a
                }
            }
            uploading = false
        }
    }

    private fun queryFileName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    fun startRecording(): Boolean {
        val ok = recorder.start()
        recording = ok
        return ok
    }

    fun stopRecordingAndTranscribe(onNoSpeech: () -> Unit) {
        val wav = recorder.stop()
        recording = false
        if (wav == null) {
            onNoSpeech()
            return
        }
        busyText = "正在识别…"
        viewModelScope.launch {
            val text = DeepSeekApi.transcribe(store.settings, wav)
            busyText = ""
            if (text.isBlank()) onNoSpeech() else onTranscript(text)
        }
    }

    fun cancelRecording() {
        recorder.cancel()
        recording = false
    }

    var pendingTranscript by mutableStateOf<String?>(null)
        private set

    private fun onTranscript(text: String) {
        pendingTranscript = text
    }

    fun consumeTranscript(): String? {
        val t = pendingTranscript
        pendingTranscript = null
        return t
    }

    // -------- 首页 / 设置用 --------

    fun settings(): AppSettings = store.settings

    fun updateSettings(block: (AppSettings) -> Unit) = store.updateSettings(block)

    fun hasKey(): Boolean = store.hasKey()

    fun conversations(): List<Conversation> = store.sorted()

    fun createConversation(): String = store.create().id

    fun deleteConversation(id: String) = store.remove(id)

    fun clearAllConversations() = store.clearAll()

    suspend fun testConnection(apiKey: String, baseUrl: String): String {
        val s = AppSettings(
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim().ifEmpty { "https://api.deepseek.com" }
        )
        return DeepSeekApi.fetchBalance(s)
    }

    override fun onCleared() {
        session.release()
        recorder.cancel()
        super.onCleared()
    }
}
