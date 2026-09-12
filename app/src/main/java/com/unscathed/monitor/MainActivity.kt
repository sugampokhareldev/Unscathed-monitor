package com.unscathed.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.unscathed.monitor.ui.DashboardScreen
import com.unscathed.monitor.ui.DebugScreen
import com.unscathed.monitor.ui.DiscordScreen
import com.unscathed.monitor.ui.GamesScreen
import com.unscathed.monitor.ui.LogScreen
import com.unscathed.monitor.ui.SettingsScreen
import com.unscathed.monitor.ui.WatchdogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchdogTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { index, (icon, label) ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { Text(icon) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val modifier = Modifier.padding(padding)
                    when (tab) {
                        0 -> DashboardScreen(modifier)
                        1 -> GamesScreen(modifier)
                        2 -> DiscordScreen(modifier)
                        3 -> SettingsScreen(modifier)
                        4 -> DebugScreen(modifier)
                        else -> LogScreen(modifier)
                    }
                }
            }
        }
    }

    private companion object {
        val TABS = listOf(
            "🛡" to "Status",
            "🎮" to "Games",
            "💬" to "Discord",
            "⚙" to "Settings",
            "🔍" to "Debug",
            "📜" to "Log",
        )
    }
}
