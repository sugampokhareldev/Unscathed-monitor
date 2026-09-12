package com.unscathed.monitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.unscathed.monitor.config.SettingsRepository
import com.unscathed.monitor.data.AppDatabase
import com.unscathed.monitor.data.EventLog
import com.unscathed.monitor.network.AlertDispatcher
import com.unscathed.monitor.network.DiscordWebhook
import com.unscathed.monitor.system.BatteryMonitor
import com.unscathed.monitor.system.NetworkMonitor
import com.unscathed.monitor.service.WatchdogRuntime
import com.unscathed.monitor.web.WatchdogWebServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

class WatchdogApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        createNotificationChannels()
        container = AppContainer(this)
        container.network.start()
        container.alerts.start()
        container.web.start()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Monitoring status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while the watchdog is running"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Disconnects, freezes, crashes and system warnings"
            },
        )
    }

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERTS = "alerts"
    }
}

/** Hand-rolled dependency container; small enough that a DI framework isn't worth it yet. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context, appScope)
    val database = AppDatabase.build(context)
    val events = EventLog(database.eventDao(), appScope)
    val network = NetworkMonitor(context)
    val battery = BatteryMonitor(context)
    val webhook = DiscordWebhook()
    val alerts = AlertDispatcher(appScope, webhook, settings, network)
    val web = WebServerHost(context.applicationContext, this, appScope)
}

/**
 * Keeps the remote-control server in step with the settings: it runs whenever the app is alive
 * and the setting is on, so the page works even when monitoring isn't, and restarts by itself
 * if the port or key changes.
 */
class WebServerHost(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private var server: WatchdogWebServer? = null
    private var running: Triple<Boolean, Int, String>? = null

    fun start() {
        scope.launch {
            container.settings.flow
                .map { Triple(it.webEnabled, it.webPort, it.webKey) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private fun apply(config: Triple<Boolean, Int, String>) {
        if (config == running) return
        stop()
        val (enabled, port, key) = config
        if (!enabled || key.isBlank()) return
        val next = WatchdogWebServer(context, container, port, key)
        runCatching { next.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onSuccess {
                server = next
                running = config
                val url = WatchdogWebServer.localAddress()?.let { "http://$it:$port/?k=$key" }
                WatchdogRuntime.update { it.copy(webUrl = url) }
                Timber.i("Remote control on %s", url)
            }
            .onFailure { Timber.e(it, "Remote control could not start on port %d", port) }
    }

    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        running = null
        WatchdogRuntime.update { it.copy(webUrl = null) }
    }
}

val Context.container: AppContainer get() = (applicationContext as WatchdogApp).container
