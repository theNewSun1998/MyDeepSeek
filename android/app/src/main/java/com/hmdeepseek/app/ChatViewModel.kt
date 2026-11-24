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
import com.hmdeepseek.app.common.Pricing
import com.hmdeepseek.app.data.Store
import com.hmdeepseek.app.model.AppSettings
import com.hmdeepseek.app.model.Attachment
import com.hmdeepseek.app.model.ChatMessage
import com.hmdeepseek.app.model.Conversation
import com.hmdeepseek.app.model.SendOptions
import com.hmdeepseek.app.model.UsageStats
import com.hmdeepseek.app.net.ChatSession
import com.hmdeepseek.app.net.DeepSeekApi
import com.hmdeepseek.app.service.KeepAliveService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val recorder = WavRecorder()
    private var uploadJob: Job? = null

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
    var uploadProgress by mutableStateOf(-1)
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

    private val session = ChatSession(        settings = { store.settings },
        scope = viewModelScope,
        onSnapshot = { msgs, isStreaming, t ->
            main.post {
                messages = msgs
                streaming = isStreaming
                title = t
            }
        },
        onPersist = { conv -> store.saveConversation(conv) },
        onUsage = { info -> store.addUsage(info) },
        onStreamingChanged = { running ->
            val ctx = getApplication<Application>()
            if (running) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
        }
    )

    fun load(id: String?) {
        // 切换会话时先把上一条流掐掉
        session.abort()
        pendingTranscript = null
        // 新对话：先只放内存里，等发出第一条消息再落库（和官方 App 一致）
        val conv: Conversation = if (id.isNullOrEmpty()) {
            Conversation()
        } else {
            store.find(id) ?: Conversation()
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

    fun attach(uri: Uri) = attachAll(listOf(uri))

    /** 拍照 / 相册多选 / 文件，统一走这里：流式上传 → 换 file_id */
    fun attachAll(uris: List<Uri>) {
        if (!store.hasKey() || uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        uploadJob = viewModelScope.launch {
            try {
                uploading = true
                for (uri in uris) {
                    val name = queryFileName(uri) ?: "file"
                    val size = queryFileSize(uri)

                    // 预检：服务端单文件上限 100 MiB，超了不必等 413
                    if (size > DeepSeekApi.MAX_UPLOAD_BYTES) {
                        attachments = attachments + Attachment(
                            name = name,
                            sizeBytes = size,
                            uploading = false,
                            errorText = "超过 ${Pricing.humanSize(DeepSeekApi.MAX_UPLOAD_BYTES)} 上限"
                        )
                        continue
                    }
                    if (size > DeepSeekApi.SLOW_UPLOAD_BYTES) {
                        transientNotice = "文件 ${Pricing.humanSize(size)}，上传 + 解析可能要 1~2 分钟"
                    }

                    val placeholder = Attachment(name = name, sizeBytes = size, uploading = true)
                    attachments = attachments + placeholder
                    uploadProgress = 0
                    var lastPct = -1

                    val result = runCatching {
                        DeepSeekApi.uploadFile(
                            settings = store.settings,
                            fileName = name,
                            fileSize = size,
                            purpose = store.settings.parsePurpose,
                            openStream = {
                                resolver.openInputStream(uri) ?: throw IllegalStateException("读取文件失败")
                            },
                            onProgress = { sent, total ->
                                val pct = if (total <= 0) 0 else ((sent * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    // 回调发生在 IO 线程，回到主线程改状态
                                    main.post { uploadProgress = pct }
                                }
                            }
                        )
                    }.getOrElse {
                        DeepSeekApi.UploadResult(error = "上传失败：${it.message ?: "未知错误"}")
                    }

                    uploadProgress = -1
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
                }
            } finally {
                // 正常结束或被取消都走到这里，别留下「上传中」的悬挂状态
                uploading = false
                uploadProgress = -1
                attachments = attachments.map { a ->
                    if (a.uploading) a.copy(uploading = false, errorText = "已取消") else a
                }
                uploadJob = null
            }
        }
    }

    /** 取消正在进行的上传（切走/手滑选错文件时用） */
    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        uploading = false
        uploadProgress = -1
        attachments = attachments.map { a ->
            if (a.uploading) a.copy(uploading = false, errorText = "已取消") else a
        }
    }

    /** 一次性提示（大文件提醒之类），由界面取走后 Toast */
    var transientNotice by mutableStateOf<String?>(null)
        private set

    fun consumeNotice(): String? {
        val n = transientNotice
        transientNotice = null
        return n
    }

    private fun queryFileSize(uri: Uri): Long {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                } ?: 0L
        }.getOrDefault(0L)
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

    fun renameConversation(id: String, title: String) = store.rename(id, title)

    fun togglePin(id: String) {
        val conv = store.find(id) ?: return
        store.setPinned(id, !conv.pinned)
    }

    fun isPinned(id: String): Boolean = store.find(id)?.pinned == true

    fun exportJson(): String = store.exportJson()

    fun importJson(text: String): Pair<Int, Int> = store.importJson(text)

    fun usageStats(): UsageStats = store.usage

    fun resetUsage() = store.resetUsage()

    /** 侧边栏搜索：标题 + 消息正文 */
    fun searchConversations(query: String): List<Conversation> = store.search(query)

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
