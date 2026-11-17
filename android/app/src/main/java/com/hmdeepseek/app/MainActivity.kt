package com.hmdeepseek.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hmdeepseek.app.ui.AppColor
import com.hmdeepseek.app.ui.ChatScreen
import com.hmdeepseek.app.ui.HomeScreen
import com.hmdeepseek.app.ui.SettingsScreen

sealed class Screen {
    data object Home : Screen()
    data class Chat(val id: String) : Screen()
    data object Settings : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var refreshKey by remember { mutableIntStateOf(0) }

                Surface(modifier = Modifier.fillMaxSize(), color = AppColor.bg) {
                    when (val current = screen) {
                        Screen.Home -> HomeScreen(
                            refreshKey = refreshKey,
                            onOpenChat = { id -> screen = Screen.Chat(id) },
                            onOpenSettings = { screen = Screen.Settings }
                        )

                        is Screen.Chat -> ChatScreen(
                            convId = current.id,
                            onBack = {
                                refreshKey++
                                screen = Screen.Home
                            },
                            onOpenSettings = { screen = Screen.Settings }
                        )

                        Screen.Settings -> SettingsScreen(
                            onBack = {
                                refreshKey++
                                screen = Screen.Home
                            }
                        )
                    }
                }

                BackHandler(enabled = screen != Screen.Home) {
                    refreshKey++
                    screen = Screen.Home
                }
            }
        }
    }
}
