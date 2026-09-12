package com.unscathed.monitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.unscathed.monitor.state.RobloxState

object Palette {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF161B22)
    val SurfaceHigh = Color(0xFF1F2630)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B949E)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Orange = Color(0xFFFB923C)
    val Red = Color(0xFFF87171)
    val Purple = Color(0xFFC084FC)
    val Blue = Color(0xFF60A5FA)
}

fun RobloxState.color(): Color = when (this) {
    RobloxState.InGame -> Palette.Green
    RobloxState.PossiblyFrozen -> Palette.Amber
    RobloxState.Frozen -> Palette.Orange
    is RobloxState.Disconnected -> Palette.Red
    RobloxState.RobloxClosed -> Palette.Red
    RobloxState.RobloxHome -> Palette.Purple
    RobloxState.Welcome -> Palette.Amber
    RobloxState.Loading -> Palette.Blue
    RobloxState.Unknown -> Palette.Muted
    is RobloxState.Recovering -> Palette.Blue
    RobloxState.Starting -> Palette.Muted
}

@Composable
fun WatchdogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Green,
            onPrimary = Palette.Background,
            secondary = Palette.Blue,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceHigh,
            onSurfaceVariant = Palette.Muted,
            surfaceContainer = Palette.Surface,
            error = Palette.Red,
        ),
        content = content,
    )
}
