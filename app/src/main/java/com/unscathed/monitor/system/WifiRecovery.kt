package com.unscathed.monitor.system

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.unscathed.monitor.accessibility.WatchdogAccessibilityService
import com.unscathed.monitor.config.WifiSettings
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Keeps trying to get the phone back online for as long as it's offline.
 *
 * Android 10+ doesn't let ordinary apps switch Wi-Fi or pick a network, so each attempt does
 * what's allowed:
 *  - make Android re-test a Wi-Fi network that's connected but has no internet
 *  - on Android 8-9, turn Wi-Fi on and call reconnect()
 *  - optionally (user setting) open the system Wi-Fi panel and flip the switch off/on through
 *    Accessibility. That kicks the radio when a router drops us or Wi-Fi was switched off.
 * Rejoining *your* network is done by Android itself once it's saved to the phone
 * ([WifiRegistration]), so this loop doesn't need the password.
 */
class WifiRecovery(private val context: Context, private val network: NetworkMonitor) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    var attempts = 0
        private set
    var offlineSinceMs: Long? = null
        private set
    private var nextAttemptAtMs = 0L

    fun reset() {
        attempts = 0
        offlineSinceMs = null
        nextAttemptAtMs = 0
    }

    /** Call every tick. Returns what this attempt did, or null if it didn't run. */
    suspend fun tick(nowMs: Long, cfg: WifiSettings): String? {
        val info = network.state.value
        if (info.connected && info.validated) {
            reset()
            return null
        }
        val since = offlineSinceMs ?: nowMs.also { offlineSinceMs = it }
        if (!cfg.autoRecover || nowMs - since < GRACE_MS || nowMs < nextAttemptAtMs) return null

        attempts++
        val actions = mutableListOf<String>()
        try {
            if (!wifi.isWifiEnabled) {
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                        @Suppress("DEPRECATION")
                        if (wifi.setWifiEnabled(true)) actions += "Turned Wi-Fi on"
                    }
                    cfg.toggleViaAccessibility && setWifiViaPanel(on = true) -> actions += "Switched Wi-Fi on via panel"
                    else -> actions += "Wi-Fi is off. Enable 'Toggle Wi-Fi via Accessibility' so the app can turn it back on"
                }
            } else {
                val active = cm.activeNetwork
                if (active != null && info.connected && !info.validated) {
                    cm.reportNetworkConnectivity(active, false)
                    actions += "Asked Android to re-test the connection"
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    wifi.reconnect()
                    actions += "Requested Wi-Fi reconnect"
                }
                if (cfg.toggleViaAccessibility && attempts % CYCLE_EVERY_ATTEMPTS == 0 && cycleWifiViaPanel()) {
                    actions += "Cycled Wi-Fi off/on"
                }
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Wi-Fi recovery action refused")
            actions += "Android refused: ${e.message}"
        }
        if (actions.isEmpty()) actions += "Waiting for Android to rejoin a saved network"

        nextAttemptAtMs = nowMs + minOf(BACKOFF_STEP_MS * attempts, BACKOFF_MAX_MS)
        return actions.joinToString("; ")
    }

    private suspend fun cycleWifiViaPanel(): Boolean {
        if (!setWifiViaPanel(on = false)) return false
        delay(4_000)
        return setWifiViaPanel(on = true)
    }

    /** Opens the Wi-Fi panel over whatever is on screen, sets the switch, and closes the panel. */
    private suspend fun setWifiViaPanel(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val service = WatchdogAccessibilityService.instance ?: return false
        if (!service.launch(Intent(Settings.Panel.ACTION_WIFI))) return false
        delay(2_000)
        return try {
            val toggle = service.findSettingsToggle() ?: return false
            if (toggle.isChecked == on) true else service.click(toggle).also { delay(1_000) }
        } finally {
            service.back()
            delay(500)
        }
    }

    private companion object {
        /** Brief Wi-Fi handovers shouldn't trigger anything. */
        const val GRACE_MS = 10_000L
        const val BACKOFF_STEP_MS = 15_000L
        const val BACKOFF_MAX_MS = 60_000L
        const val CYCLE_EVERY_ATTEMPTS = 3
    }
}
