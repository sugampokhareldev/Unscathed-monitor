package com.unscathed.monitor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.unscathed.monitor.accessibility.WatchdogAccessibilityService
import com.unscathed.monitor.config.WatchdogSettings
import com.unscathed.monitor.games.Feature
import com.unscathed.monitor.roblox.RobloxController
import com.unscathed.monitor.system.ForegroundAppDetector

data class SetupItem(
    val title: String,
    val detail: String,
    val done: Boolean,
    val required: Boolean,
    /** null = handled in-app (e.g. runtime permission) or nothing to open. */
    val intent: Intent? = null,
    val key: String,
)

object SetupChecks {
    fun read(context: Context, settings: WatchdogSettings): List<SetupItem> {
        val pm = context.getSystemService(PowerManager::class.java)
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val accessibilityOk = WatchdogAccessibilityService.instance != null
        val usageOk = ForegroundAppDetector.hasUsageAccess(context)
        val game = settings.activeGame
        val rejoin = settings.autoRecover && game.has(Feature.AUTO_REJOIN)

        return listOf(
            SetupItem(
                "Roblox installed", game.packageName,
                RobloxController(context).isInstalled(game.packageName), required = true, key = "roblox",
            ),
            SetupItem(
                "${game.name} game link",
                if (game.config.placeId.isBlank()) "Set it in the Games tab so rejoin opens the right game" else "Place ID ${game.config.placeId}",
                game.config.placeId.isNotBlank() || game.config.privateServerLink.isNotBlank(),
                required = rejoin, key = "game",
            ),
            SetupItem(
                "Wi-Fi saved for auto-rejoin",
                if (settings.wifi.savedToPhone) settings.wifi.ssid else "Settings → Internet recovery",
                settings.wifi.savedToPhone, required = false, key = "wifi",
            ),
            SetupItem(
                "Discord webhook",
                if (settings.hasWebhook) "${settings.webhooks.count { it.usable }} active" else "Add one in Settings",
                settings.hasWebhook, required = true, key = "webhook",
            ),
            SetupItem(
                "Discord ping for @${settings.pingName}",
                if (settings.pingUserIdParsed != null) "Pings user ${settings.pingUserIdParsed}" else "Add your user ID in Settings; webhooks can't ping by name",
                settings.pingUserIdParsed != null, required = true, key = "ping",
            ),
            SetupItem(
                "Notifications", "Local alerts + the monitoring notification",
                notificationsOk, required = false, key = "notifications",
            ),
            SetupItem(
                "Accessibility", "Detects Roblox on screen; taps Reconnect / rejoins; Wi-Fi toggle",
                accessibilityOk, required = settings.autoRecover || settings.wifi.toggleViaAccessibility,
                intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), key = "accessibility",
            ),
            SetupItem(
                "Usage access", "Fallback Roblox-running detection",
                usageOk, required = false,
                intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), key = "usage",
            ),
            SetupItem(
                "Battery unrestricted", "Stops Android from killing the watchdog",
                pm.isIgnoringBatteryOptimizations(context.packageName), required = true,
                intent = batteryIntent(context), key = "battery",
            ),
        )
    }

    @SuppressLint("BatteryLife")
    private fun batteryIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
