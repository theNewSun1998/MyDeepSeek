package com.hmdeepseek.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmdeepseek.app.common.Pricing
import com.hmdeepseek.app.model.Attachment
import com.hmdeepseek.app.model.ChatMessage
import com.hmdeepseek.app.model.Citation
import com.hmdeepseek.app.model.SearchStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条消息。助手消息按 App 的顺序堆：
 * 深度思考（可折叠）→ 搜索/读页步骤（可折叠）→ 正文 → 参考来源 → 操作条。
 */
@Composable
fun MessageRow(
    msg: ChatMessage,
    isLast: Boolean,
    streaming: Boolean,
    onRegenerate: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (msg.role == "user") {
            UserBubble(msg)
        } else {
            AssistantBody(msg, isLast, streaming, onRegenerate, onOpenUrl)
        }
    }
}

@Composable
private fun UserBubble(msg: ChatMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AppColor.userBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            msg.attachments.forEach { att ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColor.userBubbleSoft)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = att.name,
                        fontSize = 12.sp,
                        color = AppColor.userText,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatSize(att.sizeBytes),
                        fontSize = 11.sp,
                        color = AppColor.userSubText
                    )
                }
            }
            if (msg.text.isNotEmpty()) {
                Text(text = msg.text, fontSize = 16.sp, lineHeight = 24.sp, color = AppColor.userText)
            }
        }
    }
}

@Composable
private fun AssistantBody(
    msg: ChatMessage,
    isLast: Boolean,
    streaming: Boolean,
    onRegenerate: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // 只有真的收到 thinking 内容才显示「已深度思考」；
        // 关掉深度思考时不能空刷这张卡
        if (msg.thinking.isNotEmpty()) {
            ThinkingCard(msg)
        }

        if (msg.steps.isNotEmpty()) {
            StepsCard(msg, onOpenUrl)
        }

        // 正在联网但还没有正文：显示实时进度（对应原版「正在搜索 / 正在阅读」）
        if (streaming && msg.text.isEmpty() && msg.steps.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AppColor.brand, strokeWidth = 2.dp)
                Text(liveStatus(msg), fontSize = 13.sp, color = AppColor.textSub)
            }
        }

        if (msg.text.isNotEmpty()) {
            MarkdownView(
                content = injectCitationMarkers(msg.text, msg.citations),
                citations = msg.citations,
                onOpenUrl = onOpenUrl
            )
        } else if (streaming && msg.steps.isEmpty() && msg.thinking.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AppColor.brand, strokeWidth = 2.dp)
                Text("正在回答…", fontSize = 14.sp, color = AppColor.textSub)
            }
        }

        if (msg.compressedNote.isNotEmpty()) {
            Notice(msg.compressedNote, AppColor.textFaint)
        }

        when (msg.status) {
            "refused" -> Notice("这条回答被模型的内容安全策略拒绝了，换个问法试试。", AppColor.danger)
            "stopped" -> Notice("已停止生成。", AppColor.textFaint)
            "error" -> ErrorBox(msg.errorText.ifEmpty { "请求失败" }, onRegenerate)
        }

        if (msg.citations.isNotEmpty()) {
            Citations(key = msg.id, citations = msg.citations, onOpenUrl = onOpenUrl)
        }

        if (msg.status != "streaming" && msg.text.isNotEmpty()) {
            ActionRow(msg, isLast, onRegenerate)
        }
    }
}

@Composable
private fun ThinkingCard(msg: ChatMessage) {
    var open by rememberSaveable(msg.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColor.card)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (open) "▾" else "▸", fontSize = 10.sp, color = AppColor.textFaint)
            Spacer(Modifier.width(6.dp))
            Text(if (open) "深度思考" else "已深度思考", fontSize = 13.sp, color = AppColor.textSub)
            if (msg.thinkingMs > 1000) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "用时 " + String.format(Locale.US, "%.1f", msg.thinkingMs / 1000.0) + " 秒",
                    fontSize = 12.sp,
                    color = AppColor.textFaint
                )
            }
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = msg.thinking.ifEmpty { "思考中…" },
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = AppColor.textSub
            )
        }
    }
}

@Composable
private fun StepsCard(msg: ChatMessage, onOpenUrl: (String) -> Unit) {
    var open by rememberSaveable(msg.id) { mutableStateOf(false) }
    val hits = msg.steps.sumOf { it.hits.size }
    val fetches = msg.steps.count { it.action == "read_link" || it.action == "open_page" }
    val title = when {
        hits > 0 -> "已搜索 $hits 个网页"
        fetches > 0 -> "已阅读 $fetches 个网页"
        else -> "工具调用"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColor.card)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 13.sp, color = AppColor.textSub)
            Spacer(Modifier.weight(1f))
            Text(if (open) "收起" else "展开", fontSize = 12.sp, color = AppColor.brand)
        }
        if (open) {
            msg.steps.forEach { step -> StepDetail(step, onOpenUrl) }
        }
    }
}

@Composable
private fun StepDetail(step: SearchStep, onOpenUrl: (String) -> Unit) {
    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (step.action) {
            "search" -> if (step.queries.isNotEmpty()) {
                Text("搜索：" + step.queries.joinToString(" / "), fontSize = 12.sp, color = AppColor.textFaint)
            }

            "open_page" -> Text("打开：${step.url}", fontSize = 12.sp, color = AppColor.textFaint, maxLines = 2)
            "find_in_page" -> Text("页内查找：${step.pattern}", fontSize = 12.sp, color = AppColor.textFaint, maxLines = 2)
            "read_link" -> Text(
                "读取链接：" + step.fetchTitle.ifEmpty { step.url },
                fontSize = 12.sp,
                color = AppColor.textFaint,
                maxLines = 2
            )
        }
        if (step.errorCode.isNotEmpty()) {
            Text("失败：${step.errorCode}", fontSize = 12.sp, color = AppColor.danger)
        }
        step.hits.forEach { hit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenUrl(hit.url) }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = hit.title.ifEmpty { hit.url },
                    fontSize = 13.sp,
                    color = AppColor.text,
                    maxLines = 2
                )
                if (hit.snippet.isNotEmpty()) {
                    Text(hit.snippet, fontSize = 11.sp, color = AppColor.textFaint, maxLines = 2)
                }
                if (hit.siteName.isNotEmpty()) {
                    Text(hit.siteName, fontSize = 11.sp, color = AppColor.brand)
                }
            }
        }
    }
}

@Composable
private fun Citations(key: String, citations: List<Citation>, onOpenUrl: (String) -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColor.bgGray)
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(AppColor.brandSoft),
                contentAlignment = Alignment.Center
            ) {
                Text("${citations.size}", fontSize = 10.sp, color = AppColor.brand)
            }
            Spacer(Modifier.width(6.dp))
            Text("参考来源", fontSize = 13.sp, color = AppColor.textSub)
            Spacer(Modifier.weight(1f))
            Text(if (open) "收起" else "展开", fontSize = 12.sp, color = AppColor.brand)
        }
        if (open) {
            citations.forEachIndexed { index, c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { onOpenUrl(c.url) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        fontSize = 10.sp,
                        color = AppColor.brand,
                        modifier = Modifier.width(16.dp)
                    )
                    Text(
                        text = c.title.ifEmpty { c.url },
                        fontSize = 12.sp,
                        color = AppColor.textSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(msg: ChatMessage, isLast: Boolean, onRegenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "复制",
            fontSize = 12.sp,
            color = AppColor.textFaint,
            modifier = Modifier.clickable {
                clipboard.setText(AnnotatedString(msg.text))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
        )
        if (isLast) {
            Text(
                text = "重新生成",
                fontSize = 12.sp,
                color = AppColor.textFaint,
                modifier = Modifier.clickable { onRegenerate() }
            )
        }
        Spacer(Modifier.weight(1f))
        if (!msg.usage.isEmpty) {
            Text(usageLine(msg), fontSize = 10.sp, color = AppColor.textFaint)
            Spacer(Modifier.width(10.dp))
        }
        Text(formatClock(msg.createdAt), fontSize = 11.sp, color = AppColor.textFaint)
    }
}

/** 单条回答的用量：↑输入总量 ↓输出 · 缓存命中率 · 工具次数 */
private fun usageLine(msg: ChatMessage): String {
    val u = msg.usage
    val totalInput = (u.inputTokens + u.cacheHitTokens).toLong()
    val sb = StringBuilder()
    if (totalInput > 0) sb.append("↑").append(Pricing.formatTokens(totalInput))
    if (u.outputTokens > 0) {
        if (sb.isNotEmpty()) sb.append(" ")
        sb.append("↓").append(Pricing.formatTokens(u.outputTokens.toLong()))
    }
    if (totalInput > 0) {
        if (sb.isNotEmpty()) sb.append(" · ")
        sb.append("缓存 ").append(Pricing.percent(Pricing.cacheHitRatio(u)))
    }
    val tools = u.searchCount + u.fetchCount
    if (tools > 0) {
        if (sb.isNotEmpty()) sb.append(" · ")
        sb.append("工具 ").append(tools)
    }
    return sb.toString()
}

/** 流式中的实时状态文案 */
private fun liveStatus(msg: ChatMessage): String {
    val searches = msg.steps.count { it.action == "search" }
    val fetches = msg.steps.count { it.action == "read_link" || it.action == "open_page" }
    val hits = msg.steps.sumOf { it.hits.size }
    return when {
        hits > 0 -> "正在阅读 $hits 个网页…"
        fetches > 0 -> "正在打开网页…"
        searches > 0 -> "正在联网搜索…"
        else -> "正在处理…"
    }
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColor.bgGray)
            .padding(10.dp)
    )
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColor.dangerBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(message, fontSize = 13.sp, color = AppColor.danger)
        Text(
            text = "重试",
            fontSize = 13.sp,
            color = AppColor.brand,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(AppColor.brandSoft)
                .clickable { onRetry() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun formatClock(ts: Long): String {
    if (ts <= 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
}
