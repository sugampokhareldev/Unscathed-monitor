package com.unscathed.monitor.service

import com.unscathed.monitor.analyzer.ScreenState
import com.unscathed.monitor.state.RobloxState
import com.unscathed.monitor.system.BatteryInfo
import com.unscathed.monitor.system.NetworkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** Traffic-light colour for one line of the dashboard. */
enum class Health { GOOD, WAITING, BAD, OFF }

/** One labelled row of the dashboard: "Roblox: Running" in green. */
data class StatusLine(val label: String, val value: String, val health: Health)

/**
 * Everything the detection debug panel shows. Kept separate from the dashboard so the normal
 * screen stays free of technical detail.
 */
data class DebugSnapshot(
    val screen: ScreenState = ScreenState.UNKNOWN,
    val confidence: Int = 0,
    val reasons: List<String> = emptyList(),
    val runnerUp: String? = null,
    /** The state waiting for more agreeing readings, and how many more it needs. */
    val pending: String? = null,
    val lastStateChangeWallMs: Long? = null,
    val lastScanWallMs: Long? = null,
    val lastOcrText: String? = null,
    val lastAutomationAction: String? = null,
    val lastAutomationWallMs: Long? = null,
    val lastDiscordAlert: String? = null,
    val lastDiscordWallMs: Long? = null,
    val lastError: String? = null,
    val lastErrorWallMs: Long? = null,
    /** Screenshot kept from the last time the screen could not be recognised. */
    val failurePath: String? = null,
    val failureVersion: Long = 0,
    val failureCount: Int = 0,
)

/** Times ending in `Ms` without `Wall` are SystemClock.elapsedRealtime(). */
data class DashboardStatus(
    val monitoring: Boolean = false,
    val state: RobloxState = RobloxState.Starting,
    val monitoringSinceMs: Long? = null,
    val robloxSinceMs: Long? = null,
    val inGameSinceMs: Long? = null,
    val lastScreenChangeMs: Long? = null,
    val network: NetworkInfo = NetworkInfo(),
    val battery: BatteryInfo? = null,
    val reconnects: Int = 0,
    val relaunches: Int = 0,
    val playClicks: Int = 0,
    val lastError: String? = null,
    val lastErrorWallMs: Long? = null,
    val screenshotPath: String? = null,
    val screenshotVersion: Long = 0,
    val foregroundSource: String = "-",
    val note: String? = null,
    val gameName: String = "",
    /** Set while the phone is offline. */
    val offlineSinceMs: Long? = null,
    val internetRetries: Int = 0,
    val lastInternetAction: String? = null,
    /** Package of an app covering the screen and swallowing taps, if there is one. */
    val tapBlockedBy: String? = null,
    /** e.g. "Next rejoin in 2m 00s" while backing off. */
    val recoveryNote: String? = null,
    /** The most recent notable thing that happened, for the "Last event" card. */
    val lastEvent: String? = null,
    val lastEventWallMs: Long? = null,
    val merchantSightings: Int = 0,
    val lastMerchantWallMs: Long? = null,
    /** Whether the configured webhook has been checked, and what came back. */
    val webhookOk: Boolean? = null,
    val webhookNote: String? = null,
    /** Screenshot saved for the region-marking screen. */
    val calibrationPath: String? = null,
    val calibrationVersion: Long = 0,
    /** Address of the remote-control page while the app is running. */
    val webUrl: String? = null,
    val debug: DebugSnapshot = DebugSnapshot(),
)

/** In-process bridge between the service and the UI. */
object WatchdogRuntime {
    private val _status = MutableStateFlow(DashboardStatus())
    val status: StateFlow<DashboardStatus> = _status.asStateFlow()

    private val frameRequested = AtomicBoolean(false)

    fun update(transform: (DashboardStatus) -> DashboardStatus) = _status.update(transform)

    fun updateDebug(transform: (DebugSnapshot) -> DebugSnapshot) =
        _status.update { it.copy(debug = transform(it.debug)) }

    /** Asks the service to save the next captured frame for region marking. */
    fun requestFrame() = frameRequested.set(true)

    fun consumeFrameRequest(): Boolean = frameRequested.getAndSet(false)
}
