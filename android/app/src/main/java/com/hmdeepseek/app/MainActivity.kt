package com.hmdeepseek.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hmdeepseek.app.ui.AppColor
import com.hmdeepseek.app.ui.ChatScreen
import com.hmdeepseek.app.ui.SettingsScreen

sealed class Screen {
    data object Chat : Screen()
    data object Settings : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
    }
}
