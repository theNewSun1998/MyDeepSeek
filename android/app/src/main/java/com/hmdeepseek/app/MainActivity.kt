package com.hmdeepseek.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hmdeepseek.app.data.Store
import com.hmdeepseek.app.ui.AppColor
import com.hmdeepseek.app.ui.ChatScreen
import com.hmdeepseek.app.ui.SettingsScreen
import com.hmdeepseek.app.ui.ThemeSignal

sealed class Screen {
    data object Chat : Screen()
    data object Settings : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 恢复上次选的主题
        ThemeSignal.mode = Store.get(this).settings.themeMode

        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = when (ThemeSignal.mode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            LaunchedEffect(dark) { AppColor.apply(dark) }

            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                var screen by remember { mutableStateOf<Screen>(Screen.Chat) }

                Surface(modifier = Modifier.fillMaxSize(), color = AppColor.bg) {
                    when (screen) {
                        Screen.Chat -> ChatScreen(onOpenSettings = { screen = Screen.Settings })
                        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Chat })
                    }
                }

                BackHandler(enabled = screen == Screen.Settings) {
                    screen = Screen.Chat
                }
            }
        }

        requestNotificationPermission()
    }

    /** 前台服务通知（Android 13+ 需要授权，拒绝也不影响回答生成） */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
