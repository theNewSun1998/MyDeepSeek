package com.hmdeepseek.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmdeepseek.app.ChatViewModel
import com.hmdeepseek.app.model.Attachment
import com.hmdeepseek.app.model.ChatMessage

@Composable
fun ChatScreen(
    convId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val vm: ChatViewModel = viewModel(key = "chat-$convId")
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(convId) { vm.load(convId) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.attach(uri)
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        if (granted) vm.startRecording()
    }

    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length ?: 0) {
        if (vm.messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(vm.messages.lastIndex) }
        }
    }

    LaunchedEffect(vm.pendingTranscript) {
        val text = vm.consumeTranscript() ?: return@LaunchedEffect
        val current = draft.text
        val next = if (current.isEmpty()) text else current + text
        // 光标放到文字末尾，否则语音/建议词插入后光标会停在开头
        draft = TextFieldValue(text = next, selection = TextRange(next.length))
    }

    fun openUrl(url: String) {
        if (url.isEmpty()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun needsKeyAndGoSettings(): Boolean {
        return !vm.hasKey()
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColor.bg)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", fontSize = 26.sp, color = AppColor.text)
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
                    modifier = Modifier.clickable { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("新建对话") },
                        onClick = {
                            menuOpen = false
                            draft = TextFieldValue("")
                            vm.load(null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除这个对话") },
                        onClick = {
                            menuOpen = false
                            vm.deleteConversation(vm.convId)
                            onBack()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = {
                            menuOpen = false
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
                if (needsKeyAndGoSettings()) onOpenSettings() else {
                    vm.send(draft.text) { onOpenSettings() }
                    draft = TextFieldValue("")
                }
            },
            onStop = { vm.stop() },
            onToggleSearch = { vm.toggleSearch() },
            onToggleThinking = { vm.toggleThinking() },
            onAttach = {
                if (needsKeyAndGoSettings()) onOpenSettings() else pickFile.launch(arrayOf("*/*"))
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
                if (vm.recording) {
                    vm.stopRecordingAndTranscribe { }
                }
            }
        )
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
            modifier = Modifier.width(120.dp)
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
