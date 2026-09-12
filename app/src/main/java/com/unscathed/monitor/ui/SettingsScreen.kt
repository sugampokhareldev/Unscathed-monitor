package com.unscathed.monitor.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unscathed.monitor.config.WatchdogSettings
import com.unscathed.monitor.container
import com.unscathed.monitor.system.WifiRegistration
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val saved by app.settings.state.collectAsStateWithLifecycle()
    val status by com.unscathed.monitor.service.WatchdogRuntime.status.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiResult by remember { mutableStateOf<String?>(null) }
    val dirty = draft != saved

    fun markWifiSaved(message: String) {
        wifiResult = message
        wifiPassword = ""
        val ssid = draft.wifi.ssid
        scope.launch { app.settings.update { it.copy(wifi = it.wifi.copy(ssid = ssid, savedToPhone = true)) } }
    }

    val addNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (val r = WifiRegistration.parseAddNetworksResult(result.data.takeIf { result.resultCode == Activity.RESULT_OK })) {
                is WifiRegistration.Result.Done -> markWifiSaved(r.message)
                is WifiRegistration.Result.Failed -> wifiResult = r.message
                is WifiRegistration.Result.NeedsConfirmation -> Unit
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Hint("Webhooks, tagging and the merchant alert live in the Discord tab.")

        Section("Internet recovery") {
            SwitchSetting(
                "Keep retrying until back online", draft.wifi.autoRecover,
                "Retries every 15-60 s for as long as the phone is offline",
            ) { draft = draft.copy(wifi = draft.wifi.copy(autoRecover = it)) }
            SwitchSetting(
                "Toggle Wi-Fi via Accessibility", draft.wifi.toggleViaAccessibility,
                "Android 10+ blocks apps from switching Wi-Fi. This opens the Wi-Fi panel and flips the " +
                    "switch: turns Wi-Fi back on if it's off, and cycles it off/on every 3rd retry.",
            ) { draft = draft.copy(wifi = draft.wifi.copy(toggleViaAccessibility = it)) }

            Hint("Save your Wi-Fi to the phone once so Android always rejoins it on its own:")
            TextSetting("Wi-Fi name (SSID)", draft.wifi.ssid) {
                draft = draft.copy(wifi = draft.wifi.copy(ssid = it, savedToPhone = false))
            }
            TextSetting("Wi-Fi password (not stored)", wifiPassword, "blank for an open network", password = true) {
                wifiPassword = it
            }
            OutlinedButton(
                onClick = {
                    when (val r = WifiRegistration.register(context, draft.wifi.ssid, wifiPassword)) {
                        is WifiRegistration.Result.NeedsConfirmation -> addNetworkLauncher.launch(r.intent)
                        is WifiRegistration.Result.Done -> markWifiSaved(r.message)
                        is WifiRegistration.Result.Failed -> {
                            wifiResult = r.message
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                } catch (e: ActivityNotFoundException) {
                                    wifiResult = r.message
                                }
                            }
                        }
                    }
                },
                enabled = draft.wifi.ssid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved.wifi.savedToPhone && saved.wifi.ssid == draft.wifi.ssid) "Saved ✓ (save again)" else "Save this Wi-Fi to the phone") }
            wifiResult?.let { Text(it, fontSize = 13.sp, color = Palette.Muted) }
        }

        Section("Capture") {
            NumberSetting("Capture every (seconds)", draft.captureIntervalSec) { draft = draft.copy(captureIntervalSec = it) }
            NumberSetting("OCR heartbeat (seconds)", draft.ocrHeartbeatSec) { draft = draft.copy(ocrHeartbeatSec = it) }
            NumberSetting("Capture resolution (% of screen)", draft.captureScalePercent) {
                draft = draft.copy(captureScalePercent = it)
            }
            Hint(
                "Capture resolution applies the next time monitoring starts. Full-screen text reading only happens " +
                    "when the picture has actually changed, so a lower interval costs less than it looks.",
            )
            SwitchSetting(
                "Keep the screen awake", draft.keepScreenAwake,
                "Stops the phone sleeping while monitoring. Without this the screen times out, " +
                    "capture goes blind, and the lock screen ends up over the game swallowing every tap.",
            ) { draft = draft.copy(keepScreenAwake = it) }
            if (!draft.keepScreenAwake) {
                Hint(
                    "With this off, set the screen lock to None as well, or taps will not reach the game " +
                        "after the phone wakes.",
                    Palette.Amber,
                )
            }
            SwitchSetting(
                "Log screen text (for tuning)", draft.debugLogOcr,
                "Writes every line the app reads to the Log and Debug tabs, so a wrong reading can be traced. " +
                    "Uses more battery.",
            ) { draft = draft.copy(debugLogOcr = it) }
            SwitchSetting(
                "Save a screenshot when detection fails", draft.saveFailureScreenshots,
                "Keeps the most recent screen the app could not recognise, for the Debug tab",
            ) { draft = draft.copy(saveFailureScreenshots = it) }
        }

        Section("Remote control (same Wi-Fi)") {
            SwitchSetting(
                "Control from a browser", draft.webEnabled,
                "Runs a page on the phone for adjusting settings and watching what the monitor is doing",
            ) { draft = draft.copy(webEnabled = it) }
            NumberSetting("Port", draft.webPort) { draft = draft.copy(webPort = it) }
            val url = status.webUrl
            if (url != null) {
                Text("Open on your PC or other phone:", fontSize = 13.sp, color = Palette.Muted)
                Text(url, fontSize = 15.sp, color = Palette.Green)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Unscathed Monitor", url))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy link") }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, url),
                                        "Send the link",
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Send to PC") }
                }
                Hint("Must start with http:// — if the browser changes it to https:// it won't connect.")
            } else {
                Hint(
                    if (draft.webEnabled) "The address appears here once monitoring is running."
                    else "Turned off.",
                )
            }
            Hint(
                "Anyone on your Wi-Fi who has this exact link can change settings and see your screen, " +
                    "so don't share it. It stops when monitoring stops.",
                Palette.Amber,
            )
        }

        Section("System") {
            NumberSetting("Status report every (minutes, 0 = off)", draft.statusReportMinutes) {
                draft = draft.copy(statusReportMinutes = it)
            }
            NumberSetting("Low battery alert (%)", draft.lowBatteryPercent) { draft = draft.copy(lowBatteryPercent = it) }
            NumberSetting("Overheat alert (°C)", draft.highTempC) { draft = draft.copy(highTempC = it) }
        }

        PrimaryButton(if (dirty) "Save" else "Saved", enabled = dirty) {
            val toSave = draft
            scope.launch { app.settings.update { toSave } }
        }
        OutlinedButton(
            onClick = {
                draft = WatchdogSettings(
                    webhooks = draft.webhooks,
                    pingUserId = draft.pingUserId,
                    pingName = draft.pingName,
                    wifi = draft.wifi,
                    games = draft.games,
                    activeGameId = draft.activeGameId,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to defaults (keeps webhooks, tagging, Wi-Fi and games)")
        }
    }
}
