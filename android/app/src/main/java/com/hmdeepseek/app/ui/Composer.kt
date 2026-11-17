package com.hmdeepseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmdeepseek.app.R

/** 底部输入区：联网/深度思考开关 + 附件 + 输入框 + 麦克风（按住说话） + 发送/停止 */
@Composable
fun ComposerBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    streaming: Boolean,
    searchOn: Boolean,
    thinkingOn: Boolean,
    uploading: Boolean,
    recording: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleThinking: () -> Unit,
    onAttach: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColor.bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("联网搜索", searchOn, onToggleSearch)
            ToggleChip("深度思考", thinkingOn, onToggleThinking)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(AppColor.card)
                    .clickable { onAttach() },
                contentAlignment = Alignment.Center
            ) {
                Text("＋", fontSize = 18.sp, color = AppColor.textSub)
            }
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (recording) "正在录音，松开结束…" else "给 DeepSeek 发送消息",
                        fontSize = 15.sp,
                        color = if (recording) AppColor.brand else AppColor.textFaint
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(20.dp),
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

            if (uploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).padding(bottom = 8.dp),
                    color = AppColor.brand,
                    strokeWidth = 2.dp
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (recording) AppColor.brand else AppColor.card)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onVoiceStart()
                                tryAwaitRelease()
                                onVoiceEnd()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mic),
                    contentDescription = if (recording) "松开结束录音" else "按住说话",
                    tint = if (recording) Color.White else AppColor.textSub,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (streaming) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AppColor.text)
                        .clickable { onStop() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("■", fontSize = 14.sp, color = Color.White)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSend) AppColor.brand else AppColor.card)
                        .clickable(enabled = canSend) { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("↑", fontSize = 20.sp, color = if (canSend) Color.White else AppColor.textFaint)
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) AppColor.brandSoft else AppColor.bgGray)
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(if (on) "●" else "○", fontSize = 9.sp, color = if (on) AppColor.brand else AppColor.textFaint)
        Text(label, fontSize = 12.sp, color = if (on) AppColor.brand else AppColor.textSub)
    }
}
