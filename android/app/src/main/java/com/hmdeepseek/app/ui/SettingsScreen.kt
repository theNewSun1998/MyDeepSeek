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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmdeepseek.app.ChatViewModel
import com.hmdeepseek.app.common.Pricing
import com.hmdeepseek.app.model.UsageStats
import kotlinx.coroutines.launch

private val SAFETY_LABELS = listOf("关闭（仅自己用）", "低", "中", "高（推荐）")
private val SAFETY_VALUES = listOf("off", "low", "medium", "high")
private val THEME_LABELS = listOf("跟随系统", "浅色", "深色")
private val THEME_VALUES = listOf("system", "light", "dark")
private val COMPRESS_LABELS = listOf("16k（约 30 轮）", "32k（约 60 轮）", "64k", "128k", "不限")
private val COMPRESS_VALUES = listOf(16_000, 32_000, 64_000, 128_000, 0)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "settings")
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.deepseek.com") }
    var model by remember { mutableStateOf("deepseek-flash") }
    var systemPrompt by remember { mutableStateOf("") }
    var safetyLevel by remember { mutableStateOf("high") }
    var themeMode by remember { mutableStateOf("system") }
    var searchDefault by remember { mutableStateOf(true) }
    var thinkingDefault by remember { mutableStateOf(true) }
    var richLayout by remember { mutableStateOf(false) }
    var compressLine by remember { mutableStateOf("128000") }
    var toolLimit by remember { mutableStateOf("8") }
    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(UsageStats()) }

    LaunchedEffect(Unit) {
        val s = vm.settings()
        apiKey = s.apiKey
        baseUrl = s.baseUrl
        model = s.model
        systemPrompt = s.systemPrompt
        safetyLevel = s.safetyLevel
        themeMode = s.themeMode
        searchDefault = s.searchDefault
        thinkingDefault = s.thinkingDefault
        richLayout = s.parsePurpose == "rich_layout_recognition"
        compressLine = s.compressThresholdTokens.toString()
        toolLimit = s.toolCallLimit.toString()
        usage = vm.usageStats()
    }

    fun persist() {        vm.updateSettings { s ->
            s.apiKey = apiKey.trim()
            s.baseUrl = baseUrl.trim().ifEmpty { "https://api.deepseek.com" }
            s.model = model.trim().ifEmpty { "deepseek-flash" }
            s.systemPrompt = systemPrompt
            s.safetyLevel = safetyLevel
            s.themeMode = themeMode
            s.searchDefault = searchDefault
            s.thinkingDefault = thinkingDefault
            s.parsePurpose = if (richLayout) "rich_layout_recognition" else "text_recognition"
            s.compressThresholdTokens = (compressLine.toIntOrNull() ?: 32_000).coerceAtLeast(0)
            s.toolCallLimit = toolLimit.toIntOrNull() ?: 8
        }
        ThemeSignal.mode = themeMode
    }

    fun compressIndex(): Int {
        val value = compressLine.toIntOrNull() ?: 32_000
        val idx = COMPRESS_VALUES.indexOf(value)
        return if (idx >= 0) idx else 1
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColor.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", fontSize = 26.sp, color = AppColor.text)
            }
            Text(
                "设置",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = AppColor.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(36.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionTitle("账号")
            LabeledField(
                label = "DeepSeek API Key",
                value = apiKey,
                placeholder = "sk-...",
                password = true,
                onChange = {
                    apiKey = it
                    persist()
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (apiKey.trim().isEmpty()) {
                            testResult = "先填 API Key"
                        } else {
                            testing = true
                            testResult = ""
                            scope.launch {
                                testResult = vm.testConnection(apiKey, baseUrl)
                                testing = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColor.brandSoft,
                        contentColor = AppColor.brand
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (testing) "测试中…" else "测试连接 / 查余额", fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        vm.clearAllConversations()
                        testResult = "已清空所有对话"
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColor.bgGray,
                        contentColor = AppColor.danger
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("清空所有对话", fontSize = 13.sp)
                }
            }

            if (testResult.isNotEmpty()) {
                Text(
                    text = testResult,
                    fontSize = 12.sp,
                    color = if (testResult.startsWith("连接成功")) AppColor.ok else AppColor.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColor.bgGray)
                        .padding(10.dp)
                )
            }

            SectionTitle("模型")
            LabeledField(
                label = "模型名",
                value = model,
                placeholder = "deepseek-flash",
                password = false,
                onChange = {
                    model = it
                    persist()
                }
            )
            LabeledField(
                label = "接口地址",
                value = baseUrl,
                placeholder = "https://api.deepseek.com",
                password = false,
                onChange = {
                    baseUrl = it
                    persist()
                }
            )

            SectionTitle("外观")
            OptionRow("主题", THEME_LABELS, THEME_VALUES.indexOf(themeMode).coerceAtLeast(0)) { index ->
                themeMode = THEME_VALUES[index]
                persist()
            }

            SectionTitle("回答设置")
            SwitchRow("新对话默认联网搜索", searchDefault) {
                searchDefault = it
                persist()
            }
            SwitchRow("新对话默认深度思考", thinkingDefault) {
                thinkingDefault = it
                persist()
            }
            OptionRow("内容安全等级", SAFETY_LABELS, SAFETY_VALUES.indexOf(safetyLevel).coerceAtLeast(0)) { index ->
                safetyLevel = SAFETY_VALUES[index]
                persist()
            }
            NumberRow(
                label = "单轮工具调用上限",
                value = toolLimit,
                hint = "搜索 + 抓页累计，0 = 不限（默认 8）",
                onChange = {
                    toolLimit = it
                    persist()
                }
            )
            OptionRow(
                label = "上下文压缩线",
                labels = COMPRESS_LABELS,
                selected = compressIndex(),
                onSelect = { index ->
                    compressLine = COMPRESS_VALUES[index].toString()
                    persist()
                }
            )
            Text(
                text = "超过后一次性丢到 40%，之后前缀稳定、缓存更容易命中；「不限」= 永不压缩（官方上下文 1M）",
                fontSize = 10.sp,
                color = AppColor.textFaint
            )

            SectionTitle("附件解析")
            SwitchRow("带版面解析（能看到文档里的图表）", richLayout) {
                richLayout = it
                persist()
            }

            SectionTitle("用量与花费")
            UsageCard(usage) {
                vm.resetUsage()
                usage = vm.usageStats()
            }

            SectionTitle("高级")
            LabeledField(
                label = "系统提示词（可空）",
                value = systemPrompt,
                placeholder = "例如：回答尽量简洁",
                password = false,
                onChange = {
                    systemPrompt = it
                    persist()
                }
            )

            Text(
                "这个客户端用你自己的 API Key 直连 DeepSeek 官方接口，密钥只存在本机。\n" +
                    "全套工具（联网搜索 / 深度搜索 / 读链接 / 文件解析 / 语音）目前只有 deepseek-flash 支持，" +
                    "V4-Pro 还没跟上，模型名别乱改。\n" +
                    "花费是本地按官网价目表估算的（已计峰谷价），真实扣费以平台账单为准。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = AppColor.textFaint,
                modifier = Modifier.padding(top = 4.dp, bottom = 40.dp)
            )
        }
    }
}

@Composable
private fun UsageCard(usage: UsageStats, onReset: () -> Unit) {
    val peak = Pricing.isPeakNow()
    val cost = Pricing.estimate(usage, peak)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColor.bgGray)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row {
            Text("累计花费（估算）", fontSize = 13.sp, color = AppColor.textSub, modifier = Modifier.weight(1f))
            Text(Pricing.formatUsd(cost), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColor.brand)
        }
        Text(
            text = "输入 ${Pricing.formatTokens(Pricing.totalInputTokens(usage))}" +
                "（缓存命中 ${Pricing.percent(Pricing.cacheHitRatio(usage))}）" +
                " · 输出 ${Pricing.formatTokens(usage.outputTokens)}",
            fontSize = 11.sp,
            color = AppColor.textSub
        )
        Text(
            text = "搜索 ${usage.searchCount} 次 · 抓页 ${usage.fetchCount} 次 · 回答 ${usage.answers} 条",
            fontSize = 11.sp,
            color = AppColor.textSub
        )
        Text(
            text = if (peak) "当前按峰时价估算（UTC 01-04 / 06-10，工作日）" else "当前按谷时价估算（峰时的半价）",
            fontSize = 10.sp,
            color = AppColor.textFaint
        )
        Text(
            text = "重置统计",
            fontSize = 11.sp,
            color = AppColor.danger,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onReset() }
                .padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        color = AppColor.textFaint,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    password: Boolean,
    onChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = AppColor.textSub)
        androidx.compose.material3.TextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, fontSize = 14.sp, color = AppColor.textFaint) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = AppColor.bgGray,
                unfocusedContainerColor = AppColor.bgGray,
                disabledContainerColor = AppColor.bgGray,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = AppColor.text,
                unfocusedTextColor = AppColor.text,
                cursorColor = AppColor.brand
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun NumberRow(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = AppColor.text, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextField(
                value = value,
                onValueChange = { input -> onChange(input.filter { it.isDigit() }.take(7)) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = AppColor.bgGray,
                    unfocusedContainerColor = AppColor.bgGray,
                    disabledContainerColor = AppColor.bgGray,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = AppColor.text,
                    unfocusedTextColor = AppColor.text,
                    cursorColor = AppColor.brand
                ),
                modifier = Modifier.width(110.dp)
            )
        }
        Text(hint, fontSize = 10.sp, color = AppColor.textFaint)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = AppColor.text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppColor.brand)
        )
    }
}

@Composable
private fun OptionRow(label: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = AppColor.text, modifier = Modifier.weight(1f))
        Box {
            Text(
                text = labels.getOrElse(selected) { labels.first() },
                fontSize = 13.sp,
                color = AppColor.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColor.brandSoft)
                    .clickable { open = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                labels.forEachIndexed { i, text ->
                    DropdownMenuItem(
                        text = { Text(text, fontSize = 13.sp) },
                        onClick = {
                            open = false
                            onSelect(i)
                        }
                    )
                }
            }
        }
    }
}
