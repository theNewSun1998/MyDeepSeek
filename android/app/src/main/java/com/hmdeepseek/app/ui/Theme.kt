package com.hmdeepseek.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 主题色板。
 *
 * 每个颜色都是 Compose 的 state —— 调用 [apply] 切到深色后会触发所有读到这些颜色的
 * 组件重组，所以 UI 代码里继续写 `AppColor.bg` 就行，不用改成 CompositionLocal。
 */
object AppColor {

    var isDark by mutableStateOf(false)
        private set

    var brand by mutableStateOf(Color(0xFF4D6BFE))
        private set
    var brandSoft by mutableStateOf(Color(0xFFEEF2FF))
        private set
    var userBubble by mutableStateOf(Color(0xFF4D6BFE))
        private set
    var userBubbleSoft by mutableStateOf(Color(0xFF3355D6))
        private set
    var userText by mutableStateOf(Color.White)
        private set
    var userSubText by mutableStateOf(Color(0xFFC7D2FE))
        private set

    var bg by mutableStateOf(Color(0xFFFFFFFF))
        private set
    var bgGray by mutableStateOf(Color(0xFFF7F8FA))
        private set
    var card by mutableStateOf(Color(0xFFF4F5F7))
        private set
    var codeBg by mutableStateOf(Color(0xFFF3F4F6))
        private set
    var codeText by mutableStateOf(Color(0xFF111827))
        private set

    var text by mutableStateOf(Color(0xFF1A1A1E))
        private set
    var textSub by mutableStateOf(Color(0xFF6B7280))
        private set
    var textFaint by mutableStateOf(Color(0xFF9CA3AF))
        private set
    var divider by mutableStateOf(Color(0xFFEFF0F2))
        private set

    var danger by mutableStateOf(Color(0xFFE5484D))
        private set
    var dangerBg by mutableStateOf(Color(0xFFFEF2F2))
        private set
    var ok by mutableStateOf(Color(0xFF12A594))
        private set
    var inlineCode by mutableStateOf(Color(0xFFB4261B))
        private set

    // 代码高亮
    var codeKeyword by mutableStateOf(Color(0xFF7C3AED))
        private set
    var codeString by mutableStateOf(Color(0xFF0F7B6C))
        private set
    var codeComment by mutableStateOf(Color(0xFF9CA3AF))
        private set
    var codeNumber by mutableStateOf(Color(0xFFB45309))
        private set

    fun apply(dark: Boolean) {
        isDark = dark
        if (dark) {
            brand = Color(0xFF6D8BFF)
            brandSoft = Color(0xFF1E2740)
            userBubble = Color(0xFF3F5BD9)
            userBubbleSoft = Color(0xFF33489C)
            userText = Color(0xFFF2F4FF)
            userSubText = Color(0xFFB9C4E8)

            bg = Color(0xFF101114)
            bgGray = Color(0xFF1A1C20)
            card = Color(0xFF23262B)
            codeBg = Color(0xFF1C1F24)
            codeText = Color(0xFFD7DBE2)

            text = Color(0xFFE9EAEE)
            textSub = Color(0xFFA2A8B3)
            textFaint = Color(0xFF6E7580)
            divider = Color(0xFF2A2D33)

            danger = Color(0xFFFF7B7B)
            dangerBg = Color(0xFF2B1B1C)
            ok = Color(0xFF3DD6C0)
            inlineCode = Color(0xFFFF9E8A)

            codeKeyword = Color(0xFFC792EA)
            codeString = Color(0xFF7FDBCA)
            codeComment = Color(0xFF6E7580)
            codeNumber = Color(0xFFFFB86C)
        } else {
            brand = Color(0xFF4D6BFE)
            brandSoft = Color(0xFFEEF2FF)
            userBubble = Color(0xFF4D6BFE)
            userBubbleSoft = Color(0xFF3355D6)
            userText = Color.White
            userSubText = Color(0xFFC7D2FE)

            bg = Color(0xFFFFFFFF)
            bgGray = Color(0xFFF7F8FA)
            card = Color(0xFFF4F5F7)
            codeBg = Color(0xFFF3F4F6)
            codeText = Color(0xFF111827)

            text = Color(0xFF1A1A1E)
            textSub = Color(0xFF6B7280)
            textFaint = Color(0xFF9CA3AF)
            divider = Color(0xFFEFF0F2)

            danger = Color(0xFFE5484D)
            dangerBg = Color(0xFFFEF2F2)
            ok = Color(0xFF12A594)
            inlineCode = Color(0xFFB4261B)

            codeKeyword = Color(0xFF7C3AED)
            codeString = Color(0xFF0F7B6C)
            codeComment = Color(0xFF9CA3AF)
            codeNumber = Color(0xFFB45309)
        }
    }
}

/** 主题模式信号：设置页改这里，MainActivity 据此计算明暗并调用 AppColor.apply */
object ThemeSignal {
    /** system | light | dark */
    var mode by mutableStateOf("system")
}
