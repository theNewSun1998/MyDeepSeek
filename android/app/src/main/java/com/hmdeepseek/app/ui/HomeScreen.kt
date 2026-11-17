package com.hmdeepseek.app.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmdeepseek.app.ChatViewModel
import com.hmdeepseek.app.model.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 首页：会话列表 + 新建对话 + 设置入口 */
@Composable
fun HomeScreen(
    refreshKey: Int,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val vm: ChatViewModel = viewModel(key = "home")
    var convs by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var hasKey by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        convs = vm.conversations()
        hasKey = vm.hasKey()
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColor.bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DeepSeek", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppColor.text)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).clickable { onOpenSettings() },
                contentAlignment = Alignment.Center
            ) {
                Text("⚙", fontSize = 18.sp, color = AppColor.textSub)
            }
        }

        if (!hasKey) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColor.brandSoft)
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "还没填 API Key，点这里去设置",
                    fontSize = 13.sp,
                    color = AppColor.brand,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "去设置",
                    fontSize = 13.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColor.brand)
                        .clickable { onOpenSettings() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        if (convs.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🐳", fontSize = 44.sp)
                Spacer(Modifier.height(10.dp))
                Text("还没有对话", fontSize = 15.sp, color = AppColor.textSub)
                Spacer(Modifier.height(4.dp))
                Text("点下面的输入框开始第一次对话", fontSize = 12.sp, color = AppColor.textFaint)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(items = convs, key = { it.id }) { conv: Conversation ->
                    ConversationRow(
                        conv = conv,
                        onClick = { onOpenChat(conv.id) },
                        onDelete = {
                            vm.deleteConversation(conv.id)
                            convs = vm.conversations()
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AppColor.bgGray)
                .clickable {
                    if (vm.hasKey()) {
                        onOpenChat(vm.createConversation())
                    } else {
                        onOpenSettings()
                    }
                }
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("给 DeepSeek 发送消息", fontSize = 15.sp, color = AppColor.textFaint, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).background(AppColor.brand),
                contentAlignment = Alignment.Center
            ) {
                Text("↑", fontSize = 18.sp, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
private fun ConversationRow(conv: Conversation, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = conv.title,
            fontSize = 15.sp,
            color = AppColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(listTime(conv.updatedAt), fontSize = 11.sp, color = AppColor.textFaint)
        Spacer(Modifier.width(4.dp))
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Text(
                "⋯",
                fontSize = 16.sp,
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

private fun listTime(ts: Long): String {
    if (ts <= 0L) return ""
    val d = Date(ts)
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = d }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
    if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
        return SimpleDateFormat("M月d日", Locale.getDefault()).format(d)
    }
    return SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(d)
}
