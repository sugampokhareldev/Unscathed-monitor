package com.unscathed.monitor.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.unscathed.monitor.MainActivity
import com.unscathed.monitor.R
import com.unscathed.monitor.WatchdogApp
import com.unscathed.monitor.analyzer.EventConfirmer
import com.unscathed.monitor.analyzer.FrameAnalysis
import com.unscathed.monitor.analyzer.FrameAnalyzer
import com.unscathed.monitor.analyzer.GameEventSighting
import com.unscathed.monitor.analyzer.ScreenReading
import com.unscathed.monitor.analyzer.ScreenStabilizer
import com.unscathed.monitor.analyzer.ScreenState
import com.unscathed.monitor.automation.PlayAutomator
import com.unscathed.monitor.automation.PlayIntent
import com.unscathed.monitor.automation.PlayOutcome
import com.unscathed.monitor.capture.CapturedFrame
import com.unscathed.monitor.capture.ScreenCaptureManager
import com.unscathed.monitor.config.WatchdogSettings
import com.unscathed.monitor.container
import com.unscathed.monitor.data.EventType
import com.unscathed.monitor.games.Feature
import com.unscathed.monitor.network.AlertEvent
import com.unscathed.monitor.network.DiscordAlert
import com.unscathed.monitor.recovery.RecoveryAction
import com.unscathed.monitor.recovery.RecoveryPlanner
import com.unscathed.monitor.recovery.RecoveryStep
import com.unscathed.monitor.roblox.RobloxController
import com.unscathed.monitor.state.DisconnectCheck
import com.unscathed.monitor.state.Observation
import com.unscathed.monitor.state.RobloxState
import com.unscathed.monitor.state.Transition
import com.unscathed.monitor.state.WatchdogStateMachine
import com.unscathed.monitor.system.BatteryInfo
import com.unscathed.monitor.system.ForegroundAppDetector
import com.unscathed.monitor.system.NetworkInfo
import com.unscathed.monitor.system.WifiRecovery
import com.unscathed.monitor.util.formatClock
import com.unscathed.monitor.util.formatDuration
import com.unscathed.monitor.util.toJpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.reflect.KClass

/**
 * Foreground service that owns the screen-capture session and runs the watchdog loop:
 * capture -> classify -> confirm -> act -> verify.
 *
 * Everything runs in one coroutine, so a recovery or a Play tap naturally pauses normal
 * monitoring while it drives its own observations and waits for the result.
 */
class MonitoringService : LifecycleService() {

    private val app by lazy { container }
    private val machine = WatchdogStateMachine()
    private var planner = RecoveryPlanner()
    private var stabilizer = ScreenStabilizer()
    private val play = PlayAutomator()
    private var eventConfirmer = EventConfirmer()
    private lateinit var foreground: ForegroundAppDetector
    private lateinit var roblox: RobloxController
    private lateinit var wifiRecovery: WifiRecovery
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var notifications: NotificationManager
    private var screenLock: PowerManager.WakeLock? = null

    private var capture: ScreenCaptureManager? = null
    private var analyzer: FrameAnalyzer? = null
    private var loopJob: Job? = null
    @Volatile private var stopping = false

    private var latestFrame: CapturedFrame? = null
    private var monitoringSinceMs = 0L
    private var robloxSinceMs: Long? = null
    private var inGameSinceMs: Long? = null
    private var lastScreenChangeMs: Long? = null
    /** When the current disconnect / crash started, for "down for" in the reconnected alert. */
    private var problemSinceMs: Long? = null
    private var reconnects = 0
    private var relaunches = 0
    private var playClicks = 0
    private var merchantSightings = 0
    private var failureCount = 0
    private var lastFailureShotMs = 0L
    /** What is currently swallowing taps, if anything, and when we last said so. */
    private var tapBlockedBy: String? = null
    private var lastBlockedAlertMs = 0L
    private var lastStatusReportMs = 0L
    private val lastProblemAlertMs = mutableMapOf<KClass<out RobloxState>, Long>()
    private var alertNotificationSeq = 0

    // Edge detection for system conditions, so each one alerts once rather than every tick.
    private var lastCharging: Boolean? = null
    private var lowBatteryAlerted = false
    private var hotAlerted = false
    private var wasOnline: Boolean? = null
    private var offlineSinceMs: Long? = null
    private var wasInteractive = true

    private class Sample(
        val nowMs: Long,
        val foreground: Boolean?,
        val analysis: FrameAnalysis?,
        val battery: BatteryInfo?,
        val network: NetworkInfo,
        val interactive: Boolean,
        val transition: Transition?,
    )

    override fun onCreate() {
        super.onCreate()
        foreground = ForegroundAppDetector(this)
        roblox = RobloxController(this)
        wifiRecovery = WifiRecovery(this, app.network)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (data == null) {
                    stopSelf()
                } else {
                    // Android 14+: must be foreground with type mediaProjection before getMediaProjection().
                    startInForeground()
                    startMonitoring(resultCode, data)
                }
            }
            ACTION_STOP -> stopMonitoring("Stopped by user", notifyDiscord = true)
            // A system restart cannot reuse the screen-capture grant, so there is nothing to resume.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            loopJob?.cancel()
            capture?.release()
            releaseScreenLock()
            app.events.log(EventType.ERROR, "Service destroyed unexpectedly")
            WatchdogRuntime.update { it.copy(monitoring = false, note = "Service was stopped by Android") }
        }
        releaseScreenLock()
        analyzer?.close()
        analyzer = null
        super.onDestroy()
    }

    // region Lifecycle

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Starting..."), type)
    }

    private fun startMonitoring(resultCode: Int, data: Intent) {
        loopJob?.cancel()
        capture?.release()
        capture = null
        stopping = false

        lifecycleScope.launch {
            val s = app.settings.current()
            // A broken webhook found an hour into an AFK session is a wasted session, so it is
            // checked before anything else starts.
            if (!checkWebhook(s)) return@launch

            val cap = ScreenCaptureManager(this@MonitoringService, s.captureScalePercent / 100f) { onCaptureStopped() }
            try {
                withContext(Dispatchers.Default) { cap.start(resultCode, data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Screen capture failed to start")
                stopMonitoring("Screen capture failed to start: ${e.message}", notifyDiscord = false)
                return@launch
            }
            capture = cap
            if (analyzer == null) analyzer = FrameAnalyzer()
            if (s.keepScreenAwake) acquireScreenLock()
            resetSession(s)
            loopJob = launch { runLoop() }
        }
    }

    /**
     * Sends a silent probe to every enabled webhook before monitoring starts. A failure is
     * reported on the dashboard and in the log rather than swallowed; with
     * [WatchdogSettings.requireWebhookCheck] on, it also stops the session from starting.
     */
    private suspend fun checkWebhook(s: WatchdogSettings): Boolean {
        if (!s.discordEnabled) {
            WatchdogRuntime.update { it.copy(webhookOk = null, webhookNote = "Discord alerts are off") }
            return true
        }
        val targets = s.webhooks.filter { it.usable }
        if (targets.isEmpty()) {
            val note = "No webhook set, so nothing will be sent to Discord"
            WatchdogRuntime.update { it.copy(webhookOk = false, webhookNote = note) }
            app.events.log(EventType.ERROR, "Webhook check failed", note)
            if (s.requireWebhookCheck) {
                stopMonitoring(note + ". Add one in the Discord tab, or turn the check off.", notifyDiscord = false)
                return false
            }
            return true
        }

        val failures = targets.mapNotNull { hook ->
            when (val r = app.webhook.check(hook.url)) {
                null -> null
                else -> "${hook.name}: $r"
            }
        }
        if (failures.isEmpty()) {
            val note = "${targets.size} webhook(s) reachable"
            WatchdogRuntime.update { it.copy(webhookOk = true, webhookNote = note) }
            app.events.log(EventType.SYSTEM, "Webhook check passed", note)
            return true
        }

        val note = failures.joinToString("; ")
        WatchdogRuntime.update { it.copy(webhookOk = false, webhookNote = note) }
        app.events.log(EventType.ERROR, "Webhook check failed", note)
        if (s.requireWebhookCheck) {
            stopMonitoring("Discord webhook is not working - $note", notifyDiscord = false)
            return false
        }
        return true
    }

    private fun resetSession(s: WatchdogSettings) {
        val now = SystemClock.elapsedRealtime()
        val game = s.activeGame
        machine.reset()
        planner = RecoveryPlanner()
        stabilizer = ScreenStabilizer(defaultStreak = game.config.confirmScans, perState = game.confirmStreaks)
        play.reset()
        play.policy = game.playPolicy
        eventConfirmer = EventConfirmer(
            confirmScans = game.config.eventConfirmScans,
            cooldownMs = game.config.eventCooldownMinutes * 60_000L,
        )
        analyzer?.reset()
        monitoringSinceMs = now
        lastStatusReportMs = now
        robloxSinceMs = null
        inGameSinceMs = null
        lastScreenChangeMs = null
        problemSinceMs = null
        reconnects = 0
        relaunches = 0
        playClicks = 0
        merchantSightings = 0
        failureCount = 0
        lastFailureShotMs = 0
        tapBlockedBy = null
        lastBlockedAlertMs = 0
        lastProblemAlertMs.clear()
        lastCharging = null
        lowBatteryAlerted = false
        hotAlerted = false
        wasOnline = null
        offlineSinceMs = null
        wasInteractive = true
        wifiRecovery.reset()
        WatchdogRuntime.update {
            it.copy(
                monitoring = true, monitoringSinceMs = now, reconnects = 0, relaunches = 0, note = null,
                internetRetries = 0, lastInternetAction = null, recoveryNote = null, playClicks = 0,
                merchantSightings = 0, lastEvent = null, lastEventWallMs = null,
            )
        }
    }

    /**
     * Holds the screen on for as long as monitoring runs.
     *
     * This is the fix for the whole class of problem where the phone is left alone: once the
     * screen times out, capture stops seeing anything, and the moment it wakes the lock screen
     * is in front of the game owning all input, so every tap the watchdog sends is swallowed.
     * Keeping the screen up means the watchdog can always both see and act.
     *
     * SCREEN_BRIGHT_WAKE_LOCK is deprecated but it is the only thing a service (which owns no
     * window) can use to do this, and it still works. If the platform refuses it, monitoring
     * carries on without it and the log says so.
     */
    private fun acquireScreenLock() {
        if (screenLock?.isHeld == true) return
        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        val lock = runCatching { power.newWakeLock(flags, WAKE_LOCK_TAG) }.getOrNull()
        if (lock == null) {
            app.events.log(EventType.ERROR, "Could not keep the screen awake", "The platform refused the wake lock")
            return
        }
        runCatching { lock.acquire() }
            .onSuccess {
                screenLock = lock
                app.events.log(EventType.SYSTEM, "Holding the screen awake", "The lock screen cannot cover the game")
            }
            .onFailure { app.events.log(EventType.ERROR, "Could not keep the screen awake", it.toString()) }
    }

    private fun releaseScreenLock() {
        screenLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        screenLock = null
    }

    private fun onCaptureStopped() {
        if (stopping) return
        lifecycleScope.launch {
            postAlert(
                app.settings.state.value, AlertEvent.WATCHDOG,
                "SCREEN CAPTURE STOPPED", DiscordAlert.RED,
                "Android ended the screen-capture session (sharing stopped, phone locked, or another app took over). " +
                    "Open Unscathed Monitor and tap Start to resume monitoring.",
            )
            stopMonitoring("Screen capture was ended by Android", notifyDiscord = false)
        }
    }

    private fun stopMonitoring(reason: String, notifyDiscord: Boolean) {
        if (notifyDiscord && loopJob != null) {
            postAlert(app.settings.state.value, AlertEvent.WATCHDOG, "WATCHDOG STOPPED", DiscordAlert.GRAY, reason)
        }
        stopping = true
        releaseScreenLock()
        loopJob?.cancel()
        loopJob = null
        capture?.release()
        capture = null
        latestFrame = null
        app.events.log(EventType.SYSTEM, "Monitoring stopped", reason)
        WatchdogRuntime.update { it.copy(monitoring = false, note = reason) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // endregion

    // region Main loop

    private suspend fun runLoop() {
        app.events.log(EventType.SYSTEM, "Monitoring started")
        var first = true
        while (currentCoroutineContext().isActive) {
            val s = app.settings.state.value
            val game = s.activeGame
            machine.thresholds = game.thresholds
            play.policy = game.playPolicy
            if (s.keepScreenAwake) acquireScreenLock() else releaseScreenLock()
            try {
                val sample = sample(s, forceOcr = needsFreshReading())
                if (first) {
                    first = false
                    val features = game.profile.supportedFeatures.filter(game::has).joinToString(", ") { it.label }
                    sendAlert(
                        s, AlertEvent.WATCHDOG, "WATCHDOG STARTED", DiscordAlert.GREEN,
                        "Watching ${game.name} (${game.profile.category.label}). " +
                            "Auto-recovery is ${if (s.autoRecover) "ON" else "OFF"}.",
                        extra = listOf(
                            "App detection" to foreground.source.label,
                            "Features" to features.ifBlank { "none" },
                            "Rejoin target" to (game.launchUris.firstOrNull() ?: "Roblox home (no game link set)"),
                        ),
                    )
                }
                sample.transition?.let { handleTransition(it, s) }
                maybePressPlay(sample, s)
                checkSystem(sample, s)
                maybeSendStatusReport(sample, s)
                maybeRecover(sample, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Monitor tick failed")
                recordError("Monitor tick failed", e.toString())
            }
            delay(s.captureIntervalSec * 1000L)
        }
    }

    /**
     * Whether this tick has to read the screen even though the picture has not changed.
     *
     * The stabilizer needs several consecutive readings before it will confirm anything, and a
     * screen that is sitting still produces no new frames to trigger one - so OCR is forced
     * whenever the answer still matters: something is waiting to be confirmed, a disconnect is
     * showing, or Auto Play needs to see the welcome screen to find its button and judge its tap.
     * Once everything has settled it goes quiet again and the cheap checks carry the load.
     */
    private fun needsFreshReading(): Boolean =
        machine.wantsOcr ||
            stabilizer.pendingRemaining > 0 ||
            stabilizer.confirmed == ScreenState.WELCOME ||
            play.verifying

    /** One capture -> analyse -> classify -> confirm pass. */
    private suspend fun sample(s: WatchdogSettings, forceOcr: Boolean): Sample {
        val game = s.activeGame
        val now = SystemClock.elapsedRealtime()
        val interactive = power.isInteractive
        val battery = app.battery.read()
        val network = app.network.state.value

        val frame = if (interactive) capture?.grabFrame() else null
        if (frame != null) latestFrame = frame
        if (frame != null && WatchdogRuntime.consumeFrameRequest()) saveCalibrationFrame(frame.bitmap)

        val fg = if (interactive && game.has(Feature.CRASH_DETECTION)) foreground.isForeground(game.packageName) else null
        val analysis = if (frame != null) analyzer?.analyze(frame, now, s, game, fg, forceOcr) else null
        analysis?.let { lastScreenChangeMs = it.freeze.lastChangeAtMs }

        // Stage 2 -> 3: only a state the stabilizer has confirmed ever reaches the state machine.
        val reading = analysis?.reading
        val confirmed = reading?.let { stabilizer.offer(it, now) }
        val screen = if (reading == null) null else stabilizer.confirmed

        // With freeze detection off, an analysed frame always counts as "moving".
        val stillFor = analysis?.freeze?.stillForMs?.let { if (game.has(Feature.FREEZE_DETECTION)) it else 0L }
        val transition = machine.onObservation(
            Observation(
                nowMs = now,
                screen = screen,
                stillForMs = stillFor,
                disconnect = analysis?.disconnect ?: DisconnectCheck.NotChecked,
            ),
        )
        if (confirmed != null) onScreenConfirmed(confirmed, now, s, analysis)

        // The lock screen is by far the most common thing in the way, and it can be asked
        // directly rather than inferred from the window list.
        tapBlockedBy = when {
            keyguard.isKeyguardLocked -> LOCK_SCREEN
            // Otherwise only worth the window scan on screens where a tap is about to matter.
            stabilizer.confirmed == ScreenState.WELCOME || machine.health is RobloxState.Disconnected ->
                roblox.tapBlocker(game.packageName)?.let { it.packageName + " (covers " + it.coversPercent + "%)" }
            else -> null
        }
        tapBlockedBy?.let { maybeAlertBlocked(it, now, s) }

        val detectorBlind = foreground.source == ForegroundAppDetector.Source.NONE
        if (robloxSinceMs == null && (fg == true || (detectorBlind && analysis != null))) robloxSinceMs = now
        if (machine.health == RobloxState.RobloxClosed) robloxSinceMs = null
        if (stabilizer.confirmed == ScreenState.IN_GAME) {
            if (inGameSinceMs == null) inGameSinceMs = now
        } else {
            inGameSinceMs = null
        }

        publishStatus(s, game.name, now, analysis, reading, network, battery, interactive)
        analysis?.events?.let { handleGameEvents(it, now, s) }
        if (s.debugLogOcr) {
            analysis?.ocrText?.takeIf { it.isNotBlank() }?.let {
                app.events.log(EventType.SYSTEM, "Screen text", it.take(600))
            }
        }
        return Sample(now, fg, analysis, battery, network, interactive, transition)
    }

    private fun publishStatus(
        s: WatchdogSettings,
        gameName: String,
        now: Long,
        analysis: FrameAnalysis?,
        reading: ScreenReading?,
        network: NetworkInfo,
        battery: BatteryInfo?,
        interactive: Boolean,
    ) {
        val pending = stabilizer.pending?.takeIf { stabilizer.pendingRemaining > 0 }
            ?.let { "${it.label} (${stabilizer.pendingRemaining} more reading(s) needed)" }
        WatchdogRuntime.update {
            it.copy(
                monitoring = true,
                state = machine.state,
                robloxSinceMs = robloxSinceMs,
                inGameSinceMs = inGameSinceMs,
                lastScreenChangeMs = lastScreenChangeMs,
                network = network,
                battery = battery,
                reconnects = reconnects,
                relaunches = relaunches,
                playClicks = playClicks,
                foregroundSource = foreground.source.label,
                note = if (!interactive) "Screen is off: capture paused" else null,
                gameName = "${gameName} - ${s.activeGame.profile.category.label}",
                offlineSinceMs = wifiRecovery.offlineSinceMs,
                internetRetries = wifiRecovery.attempts,
                lastInternetAction = if (wifiRecovery.offlineSinceMs == null) null else it.lastInternetAction,
                tapBlockedBy = tapBlockedBy,
                debug = it.debug.copy(
                    screen = stabilizer.confirmed,
                    confidence = reading?.confidencePercent ?: it.debug.confidence,
                    reasons = reading?.reasons ?: it.debug.reasons,
                    runnerUp = reading?.runnerUp?.let { (st, c) -> "${st.label} (${(c * 100).toInt()}%)" },
                    pending = pending,
                    lastScanWallMs = if (analysis?.ocrRan == true) System.currentTimeMillis() else it.debug.lastScanWallMs,
                    lastOcrText = analysis?.ocrText ?: it.debug.lastOcrText,
                ),
            )
        }
    }

    /**
     * Says once, clearly, that the watchdog has been muzzled.
     *
     * This is the difference between "the app is broken" and "something is covering the screen":
     * without it, a touch-lock left on overnight looks exactly like a bug.
     */
    private suspend fun maybeAlertBlocked(blockedBy: String, now: Long, s: WatchdogSettings) {
        if (lastBlockedAlertMs != 0L && now - lastBlockedAlertMs < BLOCKED_ALERT_THROTTLE_MS) return
        lastBlockedAlertMs = now
        val isLockScreen = blockedBy == LOCK_SCREEN
        app.events.log(EventType.ERROR, "Taps are being blocked", blockedBy)
        noteAutomation("Taps blocked by " + blockedBy)
        sendAlert(
            s, AlertEvent.TAPS_BLOCKED, "CANNOT TAP THE SCREEN", DiscordAlert.ORANGE,
            if (isLockScreen) {
                "The phone locked itself, so the lock screen is over the game and takes every tap. " +
                    "Auto Play and Tap Reconnect cannot reach ${s.activeGame.name} until it is unlocked."
            } else {
                "$blockedBy is drawing over the game and receiving every tap, so Auto Play and " +
                    "Tap Reconnect cannot reach ${s.activeGame.name}."
            },
            extra = listOf(
                "In the way" to blockedBy,
                "Fix" to if (isLockScreen) {
                    "Turn on Keep the screen awake in Settings, and set the screen lock to None"
                } else {
                    "Turn that overlay off while the watchdog is running"
                },
            ),
        )
    }

    /** Runs once each time a *new* screen is confirmed, which is where failures get captured. */
    private suspend fun onScreenConfirmed(state: ScreenState, now: Long, s: WatchdogSettings, analysis: FrameAnalysis?) {
        app.events.log(EventType.STATE, "Screen: ${state.label}", stabilizer.lastReading?.reasons?.joinToString("; ").orEmpty())
        WatchdogRuntime.updateDebug { it.copy(lastStateChangeWallMs = System.currentTimeMillis()) }
        if (state == ScreenState.IN_GAME) play.onEnteredGame()
        if (state == ScreenState.UNKNOWN && s.saveFailureScreenshots) saveFailureShot(now, analysis)
    }

    /** Keeps a picture of whatever the app could not recognise, so it can be tuned later. */
    private suspend fun saveFailureShot(now: Long, analysis: FrameAnalysis?) {
        if (now - lastFailureShotMs < FAILURE_SHOT_GAP_MS) return
        lastFailureShotMs = now
        failureCount++
        val bitmap = latestFrame?.bitmap ?: return
        val jpeg = withContext(Dispatchers.Default) { runCatching { bitmap.toJpeg() }.getOrNull() } ?: return
        val file = File(filesDir, FAILURE_FILE)
        val saved = withContext(Dispatchers.IO) { runCatching { file.writeBytes(jpeg) }.isSuccess }
        app.events.log(
            EventType.ERROR,
            "Screen not recognised",
            (analysis?.ocrText ?: stabilizer.lastReading?.reasons?.joinToString("; ")).orEmpty().take(400),
        )
        if (saved) {
            WatchdogRuntime.updateDebug {
                it.copy(
                    failurePath = file.absolutePath,
                    failureVersion = it.failureVersion + 1,
                    failureCount = failureCount,
                )
            }
        }
    }

    // endregion

    // region Auto Play

    /**
     * Detect -> verify -> act -> confirm. The automator only ever hands back a click for a
     * confirmed welcome screen whose Play button was actually located this tick; after the tap
     * it waits for the screen to change before it will consider another.
     */
    private suspend fun maybePressPlay(sample: Sample, s: WatchdogSettings) {
        val game = s.activeGame
        if (!game.has(Feature.AUTO_PLAY)) return
        val button = sample.analysis?.playButton
        val intent = play.decide(stabilizer.confirmed, button, sample.nowMs, tapBlockedBy)

        when (intent) {
            is PlayIntent.Click -> {
                app.events.log(EventType.RECOVERY, "Pressing Play", "Attempt ${intent.progress}")
                val tapped = roblox.tap(intent.target)
                play.onClicked(SystemClock.elapsedRealtime(), tapped)
                if (tapped) playClicks++
                noteAutomation(if (tapped) "Play tapped (${intent.progress})" else "Play tap failed to dispatch")
                app.events.log(
                    if (tapped) EventType.RECOVERY else EventType.ERROR,
                    if (tapped) "Play clicked" else "Could not tap Play",
                    if (tapped) "Waiting up to ${game.config.playVerifySec}s to see the game load" else "Is Accessibility on?",
                )
            }
            is PlayIntent.Blocked -> WatchdogRuntime.update {
                it.copy(recoveryNote = "Cannot press Play: ${intent.by} is covering the screen")
            }
            is PlayIntent.Waiting -> WatchdogRuntime.update {
                it.copy(recoveryNote = "${intent.reason}: ${formatDuration(intent.remainingMs)} left")
            }
            is PlayIntent.Verifying, PlayIntent.Idle -> Unit
        }

        // Stage 4: report how the previous tap actually turned out.
        when (val outcome = play.lastOutcome) {
            is PlayOutcome.Entered -> {
                play.clearOutcome()
                noteAutomation("Play worked - entered the game in ${formatDuration(outcome.afterMs)}")
                app.events.log(EventType.RECOVERY, "Entered the game after Play", formatDuration(outcome.afterMs))
                sendAlert(
                    s, AlertEvent.AUTO_PLAY, "AUTO PLAY: BACK IN ${game.name.uppercase()}", DiscordAlert.GREEN,
                    "Pressed Play on the welcome screen and the game started.",
                    extra = listOf("Took" to formatDuration(outcome.afterMs)),
                    screenshot = false,
                )
            }
            is PlayOutcome.Failed -> {
                play.clearOutcome()
                noteAutomation("Play tap ${outcome.attempt} did not start the game")
                app.events.log(EventType.ERROR, "Play tap did not work", outcome.reason)
            }
            is PlayOutcome.StoodDown -> {
                play.clearOutcome()
                noteAutomation("Auto Play paused after ${outcome.attempts} failed taps")
                sendAlert(
                    s, AlertEvent.AUTO_PLAY, "AUTO PLAY PAUSED", DiscordAlert.ORANGE,
                    "Pressed Play ${outcome.attempts} times and the game never started. " +
                        "Auto Play is standing down for a while so it does not keep tapping.",
                )
            }
            null -> Unit
        }
    }

    private fun noteAutomation(what: String) {
        WatchdogRuntime.updateDebug {
            it.copy(lastAutomationAction = what, lastAutomationWallMs = System.currentTimeMillis())
        }
    }

    // endregion

    // region In-game events

    /**
     * An announcement has to be read on more than one scan before anyone is pinged, and each
     * appearance alerts exactly once - see [EventConfirmer].
     */
    private suspend fun handleGameEvents(sightings: List<GameEventSighting>, now: Long, s: WatchdogSettings) {
        val rules = s.activeGame.eventRules
        val seenIds = sightings.map { it.rule.id }.toSet()
        rules.filter { it.id !in seenIds }.forEach { eventConfirmer.absent(it.id, now) }
        eventConfirmer.prune(now)

        for (sighting in sightings) {
            when (val outcome = eventConfirmer.sighted(sighting.rule.id, now)) {
                is EventConfirmer.Outcome.Pending -> app.events.log(
                    EventType.SYSTEM,
                    "Possible ${sighting.rule.title}",
                    "Seen ${outcome.sightings}x, need ${outcome.remaining} more: ${sighting.text}",
                )
                EventConfirmer.Outcome.Suppressed -> Unit
                is EventConfirmer.Outcome.Confirmed -> sendMerchantAlert(sighting, outcome, s)
            }
        }
    }

    private suspend fun sendMerchantAlert(
        sighting: GameEventSighting,
        outcome: EventConfirmer.Outcome.Confirmed,
        s: WatchdogSettings,
    ) {
        merchantSightings++
        val wallMs = System.currentTimeMillis()
        val clock = formatClock(wallMs)
        app.events.log(EventType.ALERT, "${sighting.rule.title} detected", sighting.text)
        WatchdogRuntime.update {
            it.copy(
                merchantSightings = merchantSightings,
                lastMerchantWallMs = wallMs,
                lastEvent = "${sighting.rule.title} detected",
                lastEventWallMs = wallMs,
            )
        }
        sendAlert(
            s, AlertEvent.MERCHANT, "${sighting.rule.title.uppercase()} DETECTED", DiscordAlert.MAGENTA,
            "The ${sighting.rule.title} has appeared in ${s.activeGame.name}.",
            extra = listOf(
                "Detected at" to clock,
                "Confirmed by" to "${outcome.sightings} screen reads",
                "Screen text" to sighting.text.take(200),
            ),
        )
    }

    // endregion

    // region Reacting to state changes

    private suspend fun handleTransition(t: Transition, s: WatchdogSettings) {
        val to = t.to
        val from = t.from
        val now = SystemClock.elapsedRealtime()
        app.events.log(
            EventType.STATE,
            "${from.label} -> ${to.label}",
            (to as? RobloxState.Disconnected)?.match?.text.orEmpty(),
        )
        updateNotification(to.label)
        WatchdogRuntime.update {
            it.copy(
                state = machine.state,
                lastEvent = to.label,
                lastEventWallMs = System.currentTimeMillis(),
            )
        }

        if (to is RobloxState.Disconnected) {
            WatchdogRuntime.update {
                it.copy(lastError = to.match.description, lastErrorWallMs = System.currentTimeMillis())
            }
        }
        if (to.isProblem && problemSinceMs == null) problemSinceMs = now

        val recovered = to == RobloxState.InGame && (from.isProblem || from is RobloxState.Recovering ||
            from == RobloxState.Welcome || from == RobloxState.Loading)
        when {
            to.isProblem -> maybeSendProblemAlert(to, s)
            recovered && problemSinceMs != null -> sendReconnected(from, s, now)
        }
    }

    private suspend fun sendReconnected(from: RobloxState, s: WatchdogSettings, now: Long) {
        val game = s.activeGame
        val downFor = problemSinceMs?.let { formatDuration(now - it) } ?: "-"
        problemSinceMs = null
        val how = if (from is RobloxState.Recovering) {
            "Reconnected automatically (${from.step})."
        } else {
            "Back in the game (was: ${from.label})."
        }
        sendAlert(
            s, AlertEvent.RECONNECTED, "BACK IN ${game.name.uppercase()}", DiscordAlert.GREEN, how,
            extra = listOf("Down for" to downFor, "Attempts" to planner.attemptsUsed.toString()),
        )
    }

    private suspend fun maybeSendProblemAlert(state: RobloxState, s: WatchdogSettings) {
        val game = s.activeGame
        // Once recovery has started on this problem, each attempt reports its own outcome.
        if (planner.inEpisode) return

        val now = SystemClock.elapsedRealtime()
        val last = lastProblemAlertMs[state::class]
        if (last != null && now - last < PROBLEM_ALERT_THROTTLE_MS) return
        lastProblemAlertMs[state::class] = now

        val stillFor = lastScreenChangeMs?.let { formatDuration(now - it) } ?: "a while"
        val gameName = game.name.uppercase()
        val event: AlertEvent
        val title: String
        val color: Int
        val description: String
        when (state) {
            is RobloxState.Disconnected -> {
                event = AlertEvent.DISCONNECTED
                title = "DISCONNECTED FROM $gameName"
                color = DiscordAlert.RED
                description = state.match.description
            }
            RobloxState.Frozen -> {
                event = AlertEvent.FROZEN
                title = "$gameName FROZEN"
                color = DiscordAlert.ORANGE
                description = "The screen has not changed for $stillFor."
            }
            RobloxState.RobloxClosed -> {
                event = AlertEvent.CRASHED
                title = "ROBLOX CLOSED"
                color = DiscordAlert.PURPLE
                description = "Roblox is no longer on screen (crashed or was closed)."
            }
            RobloxState.RobloxHome -> {
                event = AlertEvent.LEFT_GAME
                title = "LEFT $gameName"
                color = DiscordAlert.PURPLE
                description = "Roblox is on its own home screen instead of in the game."
            }
            else -> return
        }

        val recovery = if (!s.autoRecover) {
            "Off"
        } else {
            val plan = planner.preview(state, now, game.recoveryPolicy)?.let { step ->
                when (step.action) {
                    RecoveryAction.SKIP -> "Skipped: ${step.reason}"
                    RecoveryAction.GIVE_UP -> "Out of attempts"
                    else -> "${step.reason} (attempt ${step.progress})"
                }
            } ?: "Not enabled for this state"
            if (isOnline()) plan else "Waiting for internet, then: $plan"
        }

        val extra = buildList {
            if (state is RobloxState.Disconnected) state.match.errorCode?.let { add("Error" to it.toString()) }
            add("Recovery" to recovery)
        }
        sendAlert(s, event, title, color, description, extra = extra)
    }

    private suspend fun checkSystem(sample: Sample, s: WatchdogSettings) {
        sample.battery?.let { b ->
            if (lastCharging != null && lastCharging != b.charging) {
                sendAlert(
                    s, AlertEvent.DEVICE,
                    if (b.charging) "CHARGER CONNECTED" else "CHARGER UNPLUGGED",
                    if (b.charging) DiscordAlert.GREEN else DiscordAlert.YELLOW,
                    "Battery is at ${b.percent}%.", screenshot = false,
                )
            }
            lastCharging = b.charging

            if (!b.charging && b.percent <= s.lowBatteryPercent) {
                if (!lowBatteryAlerted) {
                    lowBatteryAlerted = true
                    sendAlert(
                        s, AlertEvent.DEVICE, "LOW BATTERY: ${b.percent}%", DiscordAlert.ORANGE,
                        "The phone is not charging. Monitoring stops when it dies.", screenshot = false,
                    )
                }
            } else if (b.percent > s.lowBatteryPercent + 5 || b.charging) {
                lowBatteryAlerted = false
            }

            if (b.temperatureC >= s.highTempC) {
                if (!hotAlerted) {
                    hotAlerted = true
                    sendAlert(
                        s, AlertEvent.DEVICE, "PHONE OVERHEATING", DiscordAlert.ORANGE,
                        "Battery is at ${b.temperatureLabel} (limit ${s.highTempC} C).", screenshot = false,
                    )
                }
            } else if (b.temperatureC < s.highTempC - 3) {
                hotAlerted = false
            }
        }

        val online = sample.network.connected && sample.network.validated
        if (wasOnline == true && !online) {
            offlineSinceMs = sample.nowMs
            app.events.log(EventType.SYSTEM, "Internet lost", sample.network.label)
            postLocalAlert("Internet lost", "Retrying until the phone is back online.")
        } else if (wasOnline == false && online) {
            val downMs = offlineSinceMs?.let { sample.nowMs - it }
            val retries = wifiRecovery.attempts
            offlineSinceMs = null
            app.events.log(EventType.SYSTEM, "Internet restored", "${sample.network.label} after $retries retries")
            if (downMs != null && downMs >= 30_000) {
                sendAlert(
                    s, AlertEvent.INTERNET, "INTERNET RESTORED", DiscordAlert.BLUE,
                    "The internet was down for ${formatDuration(downMs)}.",
                    extra = listOf("Reconnect attempts" to retries.toString()),
                )
            }
        }
        wasOnline = online

        // Runs after the restore check above so it can still report how many retries it took.
        wifiRecovery.tick(sample.nowMs, s.wifi)?.let { action ->
            app.events.log(EventType.SYSTEM, "Internet retry #${wifiRecovery.attempts}", action)
            WatchdogRuntime.update { it.copy(internetRetries = wifiRecovery.attempts, lastInternetAction = action) }
        }

        if (wasInteractive && !sample.interactive) {
            sendAlert(
                s, AlertEvent.DEVICE, "SCREEN TURNED OFF", DiscordAlert.YELLOW,
                "Capture is paused until the screen comes back on. Roblox usually disconnects when the phone sleeps.",
                screenshot = false,
            )
        } else if (!wasInteractive && sample.interactive) {
            app.events.log(EventType.SYSTEM, "Screen turned on")
        }
        wasInteractive = sample.interactive
    }

    private suspend fun maybeSendStatusReport(sample: Sample, s: WatchdogSettings) {
        if (s.statusReportMinutes <= 0) return
        if (sample.nowMs - lastStatusReportMs < s.statusReportMinutes * 60_000L) return
        lastStatusReportMs = sample.nowMs
        sendAlert(
            s, AlertEvent.STATUS, "STATUS: ${machine.state.label.uppercase()}", DiscordAlert.BLUE, null,
            extra = listOf(
                "Watchdog uptime" to formatDuration(sample.nowMs - monitoringSinceMs),
                "In game for" to (inGameSinceMs?.let { formatDuration(sample.nowMs - it) } ?: "-"),
                "Reconnects" to reconnects.toString(),
                "Relaunches" to relaunches.toString(),
                "Play taps" to playClicks.toString(),
                "Merchant sightings" to merchantSightings.toString(),
            ),
        )
    }

    // endregion

    // region Recovery

    private fun isOnline(): Boolean = app.network.state.value.let { it.connected && it.validated }

    private suspend fun maybeRecover(sample: Sample, s: WatchdogSettings) {
        // Tapping Reconnect is pointless while something is over the screen, but relaunching
        // is an intent rather than a touch, so recovery skips straight to that instead.
        val policy = s.activeGame.recoveryPolicy.copy(tapsBlocked = tapBlockedBy != null)
        val health = machine.health
        if (!health.isProblem && health != RobloxState.Starting) {
            planner.onHealthy(sample.nowMs, policy)
        } else {
            planner.onUnhealthy()
        }

        // The welcome screen is Auto Play's job, not the rejoin planner's.
        if (health == RobloxState.RobloxHome && !s.activeGame.config.rejoinWhenNotInGame) return

        val waiting = when {
            !s.autoRecover || !machine.state.isProblem -> null
            !isOnline() -> "Waiting for internet before rejoining"
            planner.waitRemainingMs(sample.nowMs) > 0 ->
                "Next rejoin in ${formatDuration(planner.waitRemainingMs(sample.nowMs))}"
            else -> null
        }
        WatchdogRuntime.update { it.copy(recoveryNote = waiting) }

        if (!s.autoRecover || !machine.state.isProblem) return
        val step = planner.next(machine.state, sample.nowMs, policy, online = isOnline()) ?: return
        val ok = executeStep(step, s)
        planner.onStepFinished(SystemClock.elapsedRealtime(), policy)
        if (!ok && machine.state.isProblem &&
            (step.action == RecoveryAction.TAP_RECONNECT || step.action == RecoveryAction.RELAUNCH)
        ) {
            sendReconnectFailed(step, s)
        }
    }

    /** Returns true when the action got Roblox healthy again. */
    private suspend fun executeStep(step: RecoveryStep, s: WatchdogSettings): Boolean {
        val game = s.activeGame
        val label = "${step.reason} ${step.progress}"
        return when (step.action) {
            RecoveryAction.SKIP -> {
                app.events.log(EventType.RECOVERY, "Auto-recovery skipped", step.reason)
                false
            }

            RecoveryAction.GIVE_UP -> {
                app.events.log(EventType.RECOVERY, "Recovery gave up", step.reason)
                sendAlert(
                    s, AlertEvent.RECONNECT_FAILED, "GAVE UP RECONNECTING TO ${game.name.uppercase()}",
                    DiscordAlert.RED,
                    "${step.reason}. Will try again in 30 minutes. Needs manual attention.",
                    extra = listOf("State" to machine.state.label),
                )
                false
            }

            RecoveryAction.TAP_RECONNECT -> {
                val button = (machine.state as? RobloxState.Disconnected)?.match?.reconnectButton
                machine.beginRecovery(label)?.let { handleTransition(it, s) }
                val tapped = button != null && roblox.tap(button)
                if (tapped) reconnects++
                noteAutomation(if (tapped) "Tapped Reconnect" else "Could not tap Reconnect")
                app.events.log(EventType.RECOVERY, if (tapped) "Tapped Reconnect" else "Could not tap Reconnect", label)
                val ok = tapped && awaitInGame(s, RECONNECT_TIMEOUT_MS)
                app.events.log(EventType.RECOVERY, if (ok) "Reconnect worked" else "Reconnect did not recover", label)
                machine.endRecovery()?.let { handleTransition(it, s) }
                ok
            }

            RecoveryAction.RELAUNCH -> {
                machine.beginRecovery(label)?.let { handleTransition(it, s) }
                val launched = roblox.relaunch(game.packageName, game.launchUris)
                if (launched) relaunches++
                noteAutomation(if (launched) "Relaunched Roblox" else "Relaunch failed")
                app.events.log(
                    EventType.RECOVERY,
                    if (launched) "Rejoining ${game.name}" else "Relaunch failed",
                    "$label -> ${game.launchUris.firstOrNull() ?: "Roblox home"}",
                )
                val ok = launched && awaitInGame(s, RELAUNCH_TIMEOUT_MS)
                app.events.log(EventType.RECOVERY, if (ok) "Rejoin worked" else "Rejoin did not recover", label)
                machine.endRecovery()?.let { handleTransition(it, s) }
                ok
            }
        }
    }

    private suspend fun sendReconnectFailed(step: RecoveryStep, s: WatchdogSettings) {
        val game = s.activeGame
        val now = SystemClock.elapsedRealtime()
        val wait = planner.waitRemainingMs(now)
        val next = when {
            wait > 0 -> "Next attempt in ${formatDuration(wait)}"
            !isOnline() -> "Waiting for internet"
            else -> planner.preview(machine.state, now, game.recoveryPolicy)?.let { "${it.reason} (${it.progress})" }
                ?: "No more attempts"
        }
        val what = if (step.action == RecoveryAction.TAP_RECONNECT) "Tapping Reconnect" else "Rejoining the game"
        sendAlert(
            s, AlertEvent.RECONNECT_FAILED, "RECONNECT FAILED (${step.progress})", DiscordAlert.RED,
            "$what did not get back into ${game.name}.",
            extra = listOf(
                "State" to machine.state.label,
                "Down for" to (problemSinceMs?.let { formatDuration(now - it) } ?: "-"),
                "Next" to next,
            ),
        )
    }

    /**
     * Stage 4 of a recovery: the action only counts as having worked once the classifier has
     * confirmed the game screen again and it has held for [STABLE_AFTER_RECOVERY_MS].
     *
     * The welcome screen counts as progress but not as success - Auto Play takes it from there,
     * so this keeps waiting rather than declaring victory on a screen that is not the game.
     */
    private suspend fun awaitInGame(s: WatchdogSettings, timeoutMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        var healthySince: Long? = null

        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            delay(RECOVERY_POLL_MS)
            val smp = sample(s, forceOcr = true)
            maybePressPlay(smp, s)

            val inGame = stabilizer.confirmed == ScreenState.IN_GAME &&
                (machine.health == RobloxState.InGame || machine.health == RobloxState.PossiblyFrozen)
            if (!inGame) {
                healthySince = null
                continue
            }
            val since = healthySince ?: smp.nowMs.also { healthySince = it }
            if (smp.nowMs - since >= STABLE_AFTER_RECOVERY_MS) return true
        }
        return false
    }

    // endregion

    // region Alerts & notifications

    private suspend fun sendAlert(
        s: WatchdogSettings,
        event: AlertEvent,
        title: String,
        color: Int,
        description: String?,
        extra: List<Pair<String, String>> = emptyList(),
        screenshot: Boolean = true,
    ) {
        val bitmap = if (screenshot) latestFrame?.bitmap else null
        val jpeg = bitmap?.let {
            withContext(Dispatchers.Default) { runCatching { it.toJpeg() }.getOrNull() }
        }
        if (jpeg != null) saveScreenshot(jpeg)
        postAlert(s, event, title, color, description, extra, jpeg)
    }

    private fun postAlert(
        s: WatchdogSettings,
        event: AlertEvent,
        title: String,
        color: Int,
        description: String?,
        extra: List<Pair<String, String>> = emptyList(),
        jpeg: ByteArray? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        val fields = buildList {
            add("Device" to s.deviceName)
            add("Game" to s.activeGame.name)
            addAll(extra)
            if (monitoringSinceMs > 0) add("Uptime" to formatDuration(now - monitoringSinceMs))
            app.battery.read()?.let { add("Battery" to it.summary) }
            add("Network" to app.network.state.value.label)
        }
        app.alerts.enqueue(DiscordAlert(event, title, description, color, fields, jpeg, mention = s.mentionFor(event)))
        app.events.log(EventType.ALERT, title, description.orEmpty())
        WatchdogRuntime.updateDebug {
            it.copy(lastDiscordAlert = title, lastDiscordWallMs = System.currentTimeMillis())
        }
        postLocalAlert(title, description)
    }

    private fun recordError(what: String, detail: String) {
        app.events.log(EventType.ERROR, what, detail)
        WatchdogRuntime.updateDebug {
            it.copy(lastError = "$what: $detail".take(300), lastErrorWallMs = System.currentTimeMillis())
        }
    }

    /** Full-quality frame for the region-marking screen. */
    private suspend fun saveCalibrationFrame(bitmap: android.graphics.Bitmap) {
        val file = File(filesDir, CALIBRATION_FILE)
        val ok = withContext(Dispatchers.Default) {
            runCatching { file.writeBytes(bitmap.toJpeg(maxWidth = 1080, quality = 90)) }.isSuccess
        }
        if (ok) {
            WatchdogRuntime.update {
                it.copy(calibrationPath = file.absolutePath, calibrationVersion = it.calibrationVersion + 1)
            }
        }
    }

    private suspend fun saveScreenshot(jpeg: ByteArray) {
        val file = File(filesDir, SCREENSHOT_FILE)
        val saved = withContext(Dispatchers.IO) { runCatching { file.writeBytes(jpeg) }.isSuccess }
        if (saved) {
            WatchdogRuntime.update {
                it.copy(screenshotPath = file.absolutePath, screenshotVersion = it.screenshotVersion + 1)
            }
        }
    }

    private fun postLocalAlert(title: String, text: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(this, WatchdogApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        notifications.notify(ALERT_NOTIFICATION_BASE + (alertNotificationSeq++ % 10), notification)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, WatchdogApp.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle("Unscathed Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(
                0, "Stop",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun updateNotification(text: String) {
        if (!stopping) notifications.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // endregion

    companion object {
        private const val ACTION_START = "com.unscathed.monitor.action.START"
        private const val ACTION_STOP = "com.unscathed.monitor.action.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"

        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_BASE = 100
        private const val SCREENSHOT_FILE = "last_alert.jpg"
        private const val CALIBRATION_FILE = "calibration.jpg"
        const val FAILURE_FILE = "detection_failure.jpg"

        private const val PROBLEM_ALERT_THROTTLE_MS = 5 * 60_000L
        private const val RECOVERY_POLL_MS = 3_000L
        private const val RECONNECT_TIMEOUT_MS = 75_000L
        private const val RELAUNCH_TIMEOUT_MS = 150_000L
        private const val STABLE_AFTER_RECOVERY_MS = 20_000L
        /** Do not fill storage with one screenshot per tick while a screen stays unrecognised. */
        private const val FAILURE_SHOT_GAP_MS = 60_000L
        private const val BLOCKED_ALERT_THROTTLE_MS = 15 * 60_000L
        private const val WAKE_LOCK_TAG = "UnscathedMonitor::screen"
        private const val LOCK_SCREEN = "the phone lock screen"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MonitoringService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }
    }
}
