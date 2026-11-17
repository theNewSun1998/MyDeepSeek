package com.hmdeepseek.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmdeepseek.app.ChatViewModel
import com.hmdeepseek.app.model.Attachment
import com.hmdeepseek.app.model.ChatMessage
import com.hmdeepseek.app.model.Conversation
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 主界面：左侧抽屉（新建 / 搜索 / 历史）+ 聊天区。
 * 默认就是「新建对话」状态（欢迎语 + 输入框），和官方 App 一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var moreMenu by remember { mutableStateOf(false) }
    var attachSheet by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var convs by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) { vm.load(null) }

    // 抽屉打开或会话变化时刷新列表
    LaunchedEffect(drawerState.isOpen, query, vm.convId, vm.messages.size, vm.title) {
        if (drawerState.isOpen) convs = vm.searchConversations(query)
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.attach(uri)
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.attachAll(uris)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val uri = pendingCameraUri
        if (ok && uri != null) vm.attach(uri)
        pendingCameraUri = null
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        if (granted) vm.startRecording()
    }

    fun dismissInput() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun launchCamera() {
        runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri
            takePicture.launch(uri)
        }.onFailure {
            Toast.makeText(context, "无法启动相机：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openUrl(url: String) {
        if (url.isEmpty()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp),
                drawerContainerColor = AppColor.bg
            ) {
                DrawerPanel(
                    query = query,
                    onQueryChange = { query = it },
                    conversations = convs,
                    currentId = vm.convId,
                    onNewChat = {
                        query = ""
                        vm.load(null)
                        draft = TextFieldValue("")
                        scope.launch { drawerState.close() }
                    },
                    onSelect = { id ->
                        vm.load(id)
                        draft = TextFieldValue("")
                        scope.launch { drawerState.close() }
                    },
                    onDelete = { id ->
                        vm.deleteConversation(id)
                        convs = vm.searchConversations(query)
                    },
                    onOpenSettings = onOpenSettings
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().background(AppColor.bg)) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { scope.launch { drawerState.open() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text("☰", fontSize = 18.sp, color = AppColor.text)
                }
                Text(
                    text = vm.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "⋯",
                        fontSize = 20.sp,
                        color = AppColor.text,
                        modifier = Modifier.clickable { moreMenu = true }
                    )
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("新建对话") },
                            onClick = {
                                moreMenu = false
                                vm.load(null)
                                draft = TextFieldValue("")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除这个对话") },
                            onClick = {
                                moreMenu = false
                                vm.deleteConversation(vm.convId)
                                vm.load(null)
                                draft = TextFieldValue("")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = {
                                moreMenu = false
                                onOpenSettings()
                            }
                        )
                    }
                }
            }

            if (vm.messages.isEmpty() && !vm.streaming) {
                WelcomeView(
                    modifier = Modifier.weight(1f),
                    onPick = { suggestion ->
                        draft = TextFieldValue(text = suggestion, selection = TextRange(suggestion.length))
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(items = vm.messages, key = { it.id }) { msg: ChatMessage ->
                        MessageRow(
                            msg = msg,
                            isLast = msg.id == vm.messages.lastOrNull()?.id,
                            streaming = vm.streaming,
                            onRegenerate = { vm.regenerate() },
                            onOpenUrl = { openUrl(it) }
                        )
                    }
                }
            }

            if (vm.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    vm.attachments.forEachIndexed { index, att: Attachment ->
                        AttachmentChip(att = att, onRemove = { vm.removeAttachment(index) })
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            ComposerBar(
                value = draft,
                onValueChange = { draft = it },
                streaming = vm.streaming,
                searchOn = vm.searchOn,
                thinkingOn = vm.thinkingOn,
                uploading = vm.uploading,
                recording = vm.recording,
                canSend = draft.text.trim().isNotEmpty() || vm.attachments.isNotEmpty(),
                onSend = {
                    if (!vm.hasKey()) {
                        onOpenSettings()
                    } else {
                        vm.send(draft.text) { onOpenSettings() }
                        draft = TextFieldValue("")
                        dismissInput()   // 发完就收起键盘，退出输入状态
                    }
                },
                onStop = { vm.stop() },
                onToggleSearch = { vm.toggleSearch() },
                onToggleThinking = { vm.toggleThinking() },
                onAttach = {
                    if (!vm.hasKey()) onOpenSettings() else attachSheet = true
                },
                onVoiceStart = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        vm.startRecording()
                    } else {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVoiceEnd = {
                    if (vm.recording) vm.stopRecordingAndTranscribe { }
                }
            )
        }
    }

    if (attachSheet) {
        ModalBottomSheet(
            onDismissRequest = { attachSheet = false },
            containerColor = AppColor.bg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "添加到对话",
                    fontSize = 13.sp,
                    color = AppColor.textFaint,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                AttachOption("📷", "拍照", "用相机拍一张，直接发给 DeepSeek") {
                    attachSheet = false
                    launchCamera()
                }
                AttachOption("🖼", "相册", "从相册选图片（最多 5 张）") {
                    attachSheet = false
                    pickImages.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                AttachOption("📄", "文件", "PDF / Word / PPT / 文本 / 表格") {
                    attachSheet = false
                    pickFiles.launch(arrayOf("*/*"))
                }
            }
        }
    }

    LaunchedEffect(vm.pendingTranscript) {
        val text = vm.consumeTranscript() ?: return@LaunchedEffect
        val current = draft.text
        val next = if (current.isEmpty()) text else current + text
        draft = TextFieldValue(text = next, selection = TextRange(next.length))
    }

    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length ?: 0) {
        if (vm.messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(vm.messages.lastIndex) }
        }
    }
}

@Composable
private fun AttachOption(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColor.bgGray)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(AppColor.bg),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, color = AppColor.text, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = AppColor.textFaint)
        }
    }
}

@Composable
private fun DrawerPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    conversations: List<Conversation>,
    currentId: String,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DeepSeek", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColor.text)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(AppColor.brandSoft)
                    .clickable { onNewChat() },
                contentAlignment = Alignment.Center
            ) {
                Text("＋", fontSize = 18.sp, color = AppColor.brand)
            }
        }

        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            placeholder = { Text("搜索对话", fontSize = 14.sp, color = AppColor.textFaint) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppColor.bgGray,
                unfocusedContainerColor = AppColor.bgGray,
                disabledContainerColor = AppColor.bgGray,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = AppColor.text,
                unfocusedTextColor = AppColor.text,
                cursorColor = AppColor.brand
            )
        )

        if (conversations.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isBlank()) "还没有对话" else "没有匹配的对话",
                    fontSize = 13.sp,
                    color = AppColor.textFaint
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(items = conversations, key = { it.id }) { conv: Conversation ->
                    DrawerItem(
                        conv = conv,
                        selected = conv.id == currentId,
                        onClick = { onSelect(conv.id) },
                        onDelete = { onDelete(conv.id) }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColor.divider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSettings() }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚙", fontSize = 16.sp, color = AppColor.textSub)
            Spacer(Modifier.width(10.dp))
            Text("设置", fontSize = 14.sp, color = AppColor.textSub)
        }
    }
}

@Composable
private fun DrawerItem(
    conv: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AppColor.brandSoft else Color.Transparent)
            .clickable { onClick() }
            .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = conv.title,
            fontSize = 14.sp,
            color = if (selected) AppColor.brand else AppColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(drawerTime(conv.updatedAt), fontSize = 10.sp, color = AppColor.textFaint)
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Text(
                "⋯",
                fontSize = 15.sp,
                color = AppColor.textFaint,
                modifier = Modifier.clickable { menuOpen = true }
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("删除对话") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(att: Attachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppColor.bgGray)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                att.uploading -> "解析中…"
                att.errorText.isNotEmpty() -> "解析失败"
                else -> att.name
            },
            fontSize = 11.sp,
            color = if (att.errorText.isNotEmpty()) AppColor.danger else AppColor.textSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text("✕", fontSize = 11.sp, color = AppColor.textFaint, modifier = Modifier.clickable { onRemove() })
    }
}

@Composable
private fun WelcomeView(modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val suggestions = listOf(
        "帮我搜一下今天关于 DeepSeek 的新闻",
        "把这个链接里的要点总结成 5 条",
        "快速介绍一下鸿蒙 ArkTS 的流式请求怎么写"
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("DeepSeek", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppColor.brand)
        Spacer(Modifier.height(8.dp))
        Text("今天有什么可以帮到你？", fontSize = 15.sp, color = AppColor.textSub)
        Spacer(Modifier.height(24.dp))
        suggestions.forEach { text ->
            Text(
                text = text,
                fontSize = 13.sp,
                color = AppColor.textSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColor.bgGray)
                    .clickable { onPick(text) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

private fun drawerTime(ts: Long): String {
    if (ts <= 0L) return ""
    val d = Date(ts)
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = d }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
    if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
        return SimpleDateFormat("M/d", Locale.getDefault()).format(d)
    }
    return SimpleDateFormat("yy/M/d", Locale.getDefault()).format(d)
}
