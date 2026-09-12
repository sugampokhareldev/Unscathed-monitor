package com.unscathed.monitor.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unscathed.monitor.config.WatchdogSettings
import com.unscathed.monitor.container
import com.unscathed.monitor.games.Feature
import com.unscathed.monitor.service.DashboardStatus
import com.unscathed.monitor.service.Health
import com.unscathed.monitor.service.MonitoringService
import com.unscathed.monitor.service.StatusLine
import com.unscathed.monitor.service.WatchdogRuntime
import com.unscathed.monitor.state.RobloxState
import com.unscathed.monitor.util.formatClock
import com.unscathed.monitor.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one screen that has to be readable at a glance: what the monitor thinks is happening, what
 * it will do about it, and whether Discord will actually reach you. Anything technical lives in
 * the Debug tab instead.
 */
@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val status by WatchdogRuntime.status.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()

    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }

    // Read live so the dashboard is correct before monitoring starts, too.
    val network by app.network.state.collectAsStateWithLifecycle()
    val battery = remember(now / 10_000) { app.battery.read() }

    var setupVersion by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setupVersion++ }
    val setup = remember(setupVersion, settings) { SetupChecks.read(context, settings) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MonitoringService.start(context, result.resultCode, data)
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setupVersion++
    }

    fun requestStart() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        // Android 14+: force whole-screen capture so we can see Roblox and anything covering it.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    var showScreenshot by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "UNSCATHED MONITOR",
            style = MaterialTheme.typography.labelLarge,
            color = Palette.Muted,
            letterSpacing = 3.sp,
        )

        StatusHero(status)

        StatusCard("Game status", gameStatusLines(status, settings, now))
        StatusCard("Automation", automationLines(settings, status))
        status.tapBlockedBy?.let { TapBlockedCard(it) }
        LastEventCard(status, now)
        StatusCard("Discord", discordLines(status, settings))

        Panel {
            ToggleRow(
                title = "Monitoring",
                subtitle = if (status.monitoring) "Watching the screen" else "Asks for screen-capture permission",
                checked = status.monitoring,
                onChange = { on -> if (on) requestStart() else MonitoringService.stop(context) },
            )
            ToggleRow(
                title = "Auto-recovery",
                subtitle = "Reconnect / rejoin ${settings.activeGame.name} (needs Accessibility)",
                checked = settings.autoRecover,
                onChange = { on -> scope.launch { app.settings.update { it.copy(autoRecover = on) } } },
            )
        }

        if (status.screenshotPath != null) {
            OutlinedButton(onClick = { showScreenshot = true }, modifier = Modifier.fillMaxWidth()) {
                Text("View last alert screenshot")
            }
        }

        DeviceStrip(status.copy(network = network, battery = battery ?: status.battery), now)

        SetupPanel(
            items = setup,
            onOpen = { item ->
                when {
                    item.key == "notifications" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    item.intent != null -> try {
                        context.startActivity(item.intent)
                    } catch (e: ActivityNotFoundException) {
                        // Some OEM builds lack a settings screen; nothing sensible to fall back to.
                    }
                }
            },
        )

        status.note?.let {
            Text(it, color = Palette.Amber, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showScreenshot) {
        ScreenshotDialog(status.screenshotPath, status.screenshotVersion) { showScreenshot = false }
    }
}

private fun gameStatusLines(status: DashboardStatus, settings: WatchdogSettings, now: Long): List<StatusLine> {
    val state = status.state
    val robloxUp = status.monitoring && state != RobloxState.RobloxClosed && state != RobloxState.Starting
    return listOf(
        StatusLine(
            "Roblox",
            when {
                !status.monitoring -> "Not being watched"
                state == RobloxState.RobloxClosed -> "Not running"
                state == RobloxState.Starting -> "Checking"
                else -> "Running" + (status.robloxSinceMs?.let { " - ${formatDuration(now - it)}" } ?: "")
            },
            when {
                !status.monitoring -> Health.OFF
                state == RobloxState.RobloxClosed -> Health.BAD
                robloxUp -> Health.GOOD
                else -> Health.WAITING
            },
        ),
        StatusLine("Game", settings.activeGame.name, Health.GOOD),
        StatusLine(
            "State",
            if (status.monitoring) state.label else "-",
            when {
                !status.monitoring -> Health.OFF
                state.isProblem -> Health.BAD
                state == RobloxState.InGame -> Health.GOOD
                else -> Health.WAITING
            },
        ),
        StatusLine(
            "Monitoring",
            if (status.monitoring) {
                "Active" + (status.monitoringSinceMs?.let { " - ${formatDuration(now - it)}" } ?: "")
            } else {
                "Off"
            },
            if (status.monitoring) Health.GOOD else Health.OFF,
        ),
    )
}

private fun automationLines(settings: WatchdogSettings, status: DashboardStatus): List<StatusLine> {
    val game = settings.activeGame
    val blocked = status.tapBlockedBy != null
    fun line(label: String, on: Boolean) = StatusLine(
        label,
        if (on) "Enabled" else "Disabled",
        if (on) Health.GOOD else Health.OFF,
    )
    // Anything that works by tapping is not really enabled while something covers the screen.
    fun tapLine(label: String, on: Boolean) = when {
        !on -> line(label, false)
        blocked -> StatusLine(label, "Blocked", Health.BAD)
        else -> line(label, true)
    }
    return listOf(
        tapLine("Auto Play", game.has(Feature.AUTO_PLAY)),
        line("Reconnect monitor", settings.autoRecover && game.has(Feature.AUTO_REJOIN)),
        line("Merchant detection", game.has(Feature.EVENT_ALERTS) && game.eventRules.isNotEmpty()),
    )
}

/**
 * Shown only when taps cannot reach the game. It names the culprit, because otherwise a
 * touch-lock left switched on is indistinguishable from the app being broken.
 */
@Composable
private fun TapBlockedCard(blockedBy: String) {
    Panel {
        Text("CANNOT TAP THE SCREEN", fontSize = 11.sp, color = Palette.Red, letterSpacing = 1.sp)
        Spacer(Modifier.size(6.dp))
        Text(blockedBy, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Red)
        Spacer(Modifier.size(4.dp))
        Text(
            "This app is drawing over the game and receiving every tap, so Play and Reconnect " +
                "cannot be pressed. Android does not allow tapping past it. Rejoining still works.",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
    }
}

private fun discordLines(status: DashboardStatus, settings: WatchdogSettings): List<StatusLine> {
    val usable = settings.webhooks.count { it.usable }
    val webhook = when {
        !settings.discordEnabled -> StatusLine("Webhook", "Turned off", Health.OFF)
        usable == 0 -> StatusLine("Webhook", "Not set", Health.BAD)
        status.webhookOk == true -> StatusLine("Webhook", "Connected ($usable)", Health.GOOD)
        status.webhookOk == false -> StatusLine("Webhook", status.webhookNote ?: "Not working", Health.BAD)
        else -> StatusLine("Webhook", "$usable set - not checked yet", Health.WAITING)
    }
    val mention = when {
        settings.pingUserIdParsed != null -> StatusLine("User mention", "Configured", Health.GOOD)
        settings.pingName.isNotBlank() -> StatusLine("User mention", "Name only - will not ping", Health.WAITING)
        else -> StatusLine("User mention", "Not set", Health.BAD)
    }
    return listOf(webhook, mention)
}

@Composable
private fun LastEventCard(status: DashboardStatus, now: Long) {
    val text = status.lastEvent
    Panel {
        Text("LAST EVENT", fontSize = 11.sp, color = Palette.Muted, letterSpacing = 1.sp)
        Spacer(Modifier.size(8.dp))
        if (text == null) {
            Text("Nothing yet", color = Palette.Muted)
        } else {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            status.lastEventWallMs?.let {
                Text(formatClock(it), fontSize = 13.sp, color = Palette.Muted)
            }
        }
        if (status.merchantSightings > 0) {
            Spacer(Modifier.size(6.dp))
            Text(
                "Dark Arts Merchant seen ${status.merchantSightings}x this session" +
                    (status.lastMerchantWallMs?.let { ", last at ${formatClock(it)}" } ?: ""),
                fontSize = 13.sp,
                color = Palette.Purple,
            )
        }
        status.recoveryNote?.let {
            Spacer(Modifier.size(6.dp))
            Text(it, fontSize = 13.sp, color = Palette.Blue)
        }
    }
}

@Composable
private fun StatusHero(status: DashboardStatus) {
    val color = if (status.monitoring) status.state.color() else Palette.Muted
    val label = if (status.monitoring) status.state.label.uppercase() else "MONITORING OFF"
    Panel {
        if (status.gameName.isNotBlank()) {
            Text(status.gameName, fontSize = 13.sp, color = Palette.Muted)
            Spacer(Modifier.size(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** A titled block of label / value rows, each with its own traffic light. */
@Composable
private fun StatusCard(title: String, lines: List<StatusLine>) {
    Panel {
        Text(title.uppercase(), fontSize = 11.sp, color = Palette.Muted, letterSpacing = 1.sp)
        Spacer(Modifier.size(6.dp))
        lines.forEach { line ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).background(line.health.color(), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(line.label, Modifier.weight(1f), fontSize = 15.sp)
                Text(line.value, fontSize = 15.sp, color = line.health.color(), fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun Health.color(): Color = when (this) {
    Health.GOOD -> Palette.Green
    Health.WAITING -> Palette.Amber
    Health.BAD -> Palette.Red
    Health.OFF -> Palette.Muted
}

/** Battery, heat and network: useful, but not what the monitor is about. */
@Composable
private fun DeviceStrip(status: DashboardStatus, now: Long) {
    Panel {
        status.offlineSinceMs?.let { since ->
            Text(
                "OFFLINE - retrying for ${formatDuration(now - since)} (${status.internetRetries} tries)",
                color = Palette.Amber,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Mini("Network", status.network.label, Modifier.weight(1f))
            Mini("Battery", status.battery?.summary ?: "-", Modifier.weight(1f))
            Mini("Temp", status.battery?.temperatureLabel ?: "-", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Mini("Reconnects", status.reconnects.toString(), Modifier.weight(1f))
            Mini("Rejoins", status.relaunches.toString(), Modifier.weight(1f))
            Mini("Play taps", status.playClicks.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Mini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), fontSize = 10.sp, color = Palette.Muted, letterSpacing = 1.sp)
        Text(value, fontSize = 15.sp)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = Palette.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SetupPanel(items: List<SetupItem>, onOpen: (SetupItem) -> Unit) {
    val missing = items.count { !it.done && it.required }
    Panel {
        Text(
            if (missing == 0) "Setup OK" else "Setup: $missing required item(s) left",
            fontWeight = FontWeight.SemiBold,
            color = if (missing == 0) Palette.Green else Palette.Amber,
        )
        Spacer(Modifier.size(6.dp))
        items.forEach { item ->
            val canOpen = !item.done && (item.intent != null || item.key == "notifications")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canOpen) { onOpen(item) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (item.done) "OK" else if (item.required) "X" else "-",
                    color = if (item.done) Palette.Green else if (item.required) Palette.Red else Palette.Muted,
                    modifier = Modifier.width(28.dp),
                    fontSize = 12.sp,
                )
                Column(Modifier.weight(1f)) {
                    Text(item.title)
                    Text(item.detail, fontSize = 12.sp, color = Palette.Muted)
                }
                if (canOpen) Text("Open", color = Palette.Blue, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ScreenshotDialog(path: String?, version: Long, onDismiss: () -> Unit) {
    val bitmap = remember(path, version) { path?.let { BitmapFactory.decodeFile(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Last alert screenshot") },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Screenshot attached to the last alert",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("No screenshot yet.")
            }
        },
    )
}

@Composable
fun Panel(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
