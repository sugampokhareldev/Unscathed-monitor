package com.unscathed.monitor.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unscathed.monitor.analyzer.ScreenState
import com.unscathed.monitor.container
import com.unscathed.monitor.service.WatchdogRuntime
import com.unscathed.monitor.telemetry.TelemetryConnection
import com.unscathed.monitor.telemetry.TelemetrySnapshot
import com.unscathed.monitor.util.formatAgo
import com.unscathed.monitor.util.formatDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Everything needed to work out why detection did the wrong thing, kept off the main dashboard
 * so that screen stays readable.
 */
@Composable
fun DebugScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val status by WatchdogRuntime.status.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val d = status.debug
    val telemetry by app.telemetry.snapshot.collectAsStateWithLifecycle()
    // Ticks on its own so packet age counts up between packets.
    val nowMono by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(500)
            value = SystemClock.elapsedRealtime()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel {
            Text("DETECTED STATE", fontSize = 11.sp, color = Palette.Muted, letterSpacing = 1.sp)
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(screenColor(d.screen), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(d.screen.label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(6.dp))
            ConfidenceBar(d.confidence)
            d.runnerUp?.let { Text("Next best: $it", fontSize = 12.sp, color = Palette.Muted) }
            d.pending?.let { Text("Waiting to confirm: $it", fontSize = 13.sp, color = Palette.Amber) }
            if (d.reasons.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text("Why:", fontSize = 12.sp, color = Palette.Muted)
                d.reasons.forEach { Text("- $it", fontSize = 13.sp) }
            }
        }

        TelemetryPanel(telemetry, settings.telemetryEnabled, nowMono)

        Panel {
            Text("TIMELINE", fontSize = 11.sp, color = Palette.Muted, letterSpacing = 1.sp)
            Spacer(Modifier.size(6.dp))
            DebugRow("Last state change", d.lastStateChangeWallMs?.let(::formatDateTime))
            DebugRow("Last screen scan", d.lastScanWallMs?.let(::formatDateTime))
            DebugRow("Taps blocked by", status.tapBlockedBy, color = Palette.Red)
            DebugRow("Last automation action", d.lastAutomationAction, d.lastAutomationWallMs)
            DebugRow("Last Discord alert", d.lastDiscordAlert, d.lastDiscordWallMs)
            DebugRow("Last detection error", d.lastError, d.lastErrorWallMs, Palette.Red)
        }

        Panel {
            Text("SCREEN TEXT", fontSize = 11.sp, color = Palette.Muted, letterSpacing = 1.sp)
            Spacer(Modifier.size(6.dp))
            if (!settings.debugLogOcr) {
                Text(
                    "Turn on \"Log screen text\" in Settings to capture what the app is reading.",
                    fontSize = 13.sp,
                    color = Palette.Amber,
                )
            }
            Text(
                d.lastOcrText?.takeIf { it.isNotBlank() } ?: "Nothing captured yet.",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Palette.Muted,
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = { scope.launch { app.settings.update { it.copy(debugLogOcr = !it.debugLogOcr) } } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (settings.debugLogOcr) "Stop logging screen text" else "Start logging screen text") }
        }

        Panel {
            Text("DETECTION FAILURES", fontSize = 11.sp, color = Palette.Muted, letterSpacing = 1.sp)
            Spacer(Modifier.size(6.dp))
            Text(
                if (d.failureCount == 0) "None this session" else "${d.failureCount} unrecognised screen(s)",
                color = if (d.failureCount == 0) Palette.Green else Palette.Amber,
            )
            val bitmap = remember(d.failurePath, d.failureVersion) {
                d.failurePath?.let { BitmapFactory.decodeFile(it) }
            }
            if (bitmap != null) {
                Spacer(Modifier.size(8.dp))
                Text("Last screen the app could not place:", fontSize = 12.sp, color = Palette.Muted)
                Spacer(Modifier.size(6.dp))
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "The screen that could not be recognised",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(8.dp))
            SwitchSetting(
                "Save a screenshot when detection fails",
                settings.saveFailureScreenshots,
                "Keeps the most recent unrecognised screen, at most one a minute",
            ) { on -> scope.launch { app.settings.update { it.copy(saveFailureScreenshots = on) } } }
        }
    }
}

@Composable
private fun ConfidenceBar(percent: Int) {
    val color = when {
        percent >= 70 -> Palette.Green
        percent >= 45 -> Palette.Amber
        else -> Palette.Red
    }
    Column {
        Text("Confidence: $percent%", fontSize = 13.sp, color = color)
        Spacer(Modifier.size(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.SurfaceHigh)) {
            Box(
                Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .height(6.dp)
                    .background(color),
            )
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String?, atWallMs: Long? = null, color: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(150.dp), fontSize = 12.sp, color = Palette.Muted)
        Column(Modifier.weight(1f)) {
            Text(value ?: "-", fontSize = 13.sp, color = if (value == null) Palette.Muted else color)
            atWallMs?.let { Text(formatDateTime(it), fontSize = 11.sp, color = Palette.Muted) }
        }
    }
}

/**
 * Enhanced telemetry at a glance. The status line is judged from the clock rather than the last
 * heartbeat check, so it never shows "Connected" for a collector that has already gone quiet.
 */
@Composable
private fun TelemetryPanel(t: TelemetrySnapshot, enabled: Boolean, nowMono: Long) {
    val connected = t.isConnectedAt(nowMono)
    val (statusText, statusColor) = when {
        !enabled -> "Off" to Palette.Muted
        t.serverError != null && !t.serverRunning -> "Could not start" to Palette.Red
        !t.serverRunning -> "Starts with monitoring" to Palette.Muted
        connected -> "Connected" to Palette.Green
        t.connection == TelemetryConnection.NEVER_CONNECTED -> "Waiting for collector" to Palette.Amber
        else -> "Disconnected" to Palette.Red
    }
    Panel {
        Text("ENHANCED TELEMETRY", fontSize = 11.sp, color = Palette.Muted, letterSpacing = 1.sp)
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(statusText, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = statusColor)
        }
        Text(
            if (connected) {
                "Receiving game state alongside screen monitoring."
            } else {
                "Optional. Screen monitoring works the same without it."
            },
            fontSize = 12.sp,
            color = Palette.Muted,
        )
        Spacer(Modifier.size(6.dp))
        DebugRow("Listening", t.listeningPort?.let { "127.0.0.1:$it" })
        DebugRow("Last packet", t.lastPacketMonoMs?.let { formatPacketAge(nowMono - it) })
        DebugRow("Sequence", t.lastSequence?.let { "%,d".format(it) })
        DebugRow("Schema", t.schemaVersion?.let { "v$it" })
        DebugRow(
            "Player",
            t.state?.player?.inGame?.let { inGame ->
                (if (inGame) "In game" else "Not in game") + if (connected) "" else " (last known)"
            },
        )
        DebugRow("Packets received", "%,d".format(t.packetsReceived))
        DebugRow("Out-of-order ignored", "%,d".format(t.stalePackets))
        DebugRow(
            "Rejected packets",
            "%,d".format(t.rejectedPackets),
            color = if (t.rejectedPackets > 0) Palette.Amber else Palette.Text,
        )
        DebugRow("Rate limited", "%,d".format(t.rateLimited))
        DebugRow("Last parse error", t.lastParseError, t.lastParseErrorWallMs, Palette.Red)
        t.serverError?.let { DebugRow("Server error", it, color = Palette.Red) }
        DebugRow("Last connected", t.lastConnectedWallMs?.let(::formatDateTime))
        DebugRow("Last disconnected", t.lastDisconnectedWallMs?.let(::formatDateTime))
    }
}

/** Sub-second precision while it matters, so a 1-2 s heartbeat is visibly ticking. */
private fun formatPacketAge(ms: Long): String {
    val age = ms.coerceAtLeast(0)
    return if (age < 10_000) String.format(Locale.US, "%.1fs ago", age / 1000.0) else formatAgo(age)
}

private fun screenColor(state: ScreenState): Color = when (state) {
    ScreenState.IN_GAME -> Palette.Green
    ScreenState.LOADING -> Palette.Blue
    ScreenState.WELCOME -> Palette.Amber
    ScreenState.ROBLOX_HOME -> Palette.Purple
    ScreenState.DISCONNECTED, ScreenState.ROBLOX_CLOSED -> Palette.Red
    ScreenState.UNKNOWN -> Palette.Muted
}
