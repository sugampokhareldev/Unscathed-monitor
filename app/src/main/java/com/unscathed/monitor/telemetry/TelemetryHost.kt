package com.unscathed.monitor.telemetry

import android.os.SystemClock
import com.unscathed.monitor.config.SettingsRepository
import com.unscathed.monitor.data.EventLog
import com.unscathed.monitor.data.EventType
import com.unscathed.monitor.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the telemetry server for exactly as long as monitoring runs, in step with the settings.
 *
 * Modelled on the remote-control host, with one difference: it is armed and disarmed by the
 * monitoring service rather than living as long as the app, so the port is only open while it
 * can actually be useful. Starting and stopping happen on one thread under one lock, so a quick
 * stop-then-start can never race two servers onto the same port.
 */
class TelemetryHost(
    private val settings: SettingsRepository,
    private val repository: LuaTelemetryRepository,
    private val events: EventLog,
    private val scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serverThread = Dispatchers.IO.limitedParallelism(1)
    private val lock = Mutex()
    private var server: LuaTelemetryServer? = null
    private var running: Config? = null
    private var armed: Job? = null

    private data class Config(val enabled: Boolean, val port: Int, val timeoutMs: Long, val webPort: Int)

    init {
        repository.listener = { change ->
            when (change) {
                is TelemetryConnectionChange.Connected -> events.log(
                    EventType.SYSTEM,
                    "Telemetry connected",
                    change.gapMs?.let { "Back after ${formatDuration(it)} without packets" } ?: "Collector is sending state",
                )
                is TelemetryConnectionChange.Lost -> events.log(
                    EventType.SYSTEM,
                    "Telemetry lost",
                    "No packet for ${formatDuration(change.atMs - change.lastPacketMs)}. " +
                        "Screen monitoring carries on as normal.",
                )
            }
        }
    }

    /** Called when monitoring starts. Safe to call more than once. */
    fun arm() {
        if (armed?.isActive == true) return
        armed = scope.launch(serverThread) {
            launch { heartbeatLoop() }
            settings.flow
                .map { Config(it.telemetryEnabled, it.telemetryPort, it.telemetryTimeoutSec * 1000L, it.webPort) }
                .distinctUntilChanged()
                .collect { config -> lock.withLock { apply(config) } }
        }
    }

    /** Called when monitoring stops, for any reason. Safe to call when not armed. */
    fun disarm() {
        armed?.cancel()
        armed = null
        scope.launch(serverThread) { lock.withLock { stopServer() } }
    }

    private suspend fun heartbeatLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(HEARTBEAT_CHECK_MS)
            if (server != null) repository.tick()
        }
    }

    private suspend fun apply(config: Config) {
        repository.setTimeoutMs(config.timeoutMs)
        val current = running
        // Only the timeout changed: no need to drop a working connection.
        if (current != null && current.enabled == config.enabled && current.port == config.port) {
            running = config
            return
        }
        stopServer()
        if (!config.enabled) return

        if (config.port == config.webPort) {
            val message = "Port ${config.port} is already used by remote control"
            repository.onServerError(message)
            events.log(EventType.ERROR, "Telemetry could not start", message)
            return
        }

        repeat(BIND_ATTEMPTS) { attempt ->
            val next = LuaTelemetryServer(repository, config.port, SystemClock::elapsedRealtime)
            val started = runCatching { next.start(LuaTelemetryServer.READ_TIMEOUT_MS, true) }
            if (started.isSuccess) {
                server = next
                running = config
                repository.onServerStarted(config.port)
                events.log(EventType.SYSTEM, "Telemetry listening", "${LuaTelemetryServer.LOOPBACK_HOST}:${config.port}")
                return
            }
            runCatching { next.stop() }
            // The previous server's socket can take a moment to be released after a restart.
            if (attempt < BIND_ATTEMPTS - 1) {
                delay(BIND_RETRY_MS)
            } else {
                val message = started.exceptionOrNull()?.message ?: "Could not open port ${config.port}"
                repository.onServerError(message)
                events.log(EventType.ERROR, "Telemetry could not start", message)
            }
        }
    }

    private fun stopServer() {
        val current = server
        running = null
        if (current == null) return
        server = null
        runCatching { current.stop() }
        repository.onServerStopped()
        events.log(EventType.SYSTEM, "Telemetry stopped")
    }

    private companion object {
        const val HEARTBEAT_CHECK_MS = 1_000L
        const val BIND_ATTEMPTS = 3
        const val BIND_RETRY_MS = 500L
    }
}
