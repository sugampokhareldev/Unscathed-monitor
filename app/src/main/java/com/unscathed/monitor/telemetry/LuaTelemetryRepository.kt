package com.unscathed.monitor.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What happened to one `/state` body. */
sealed interface StateAcceptance {
    data object Accepted : StateAcceptance

    /** Valid, but older than what we already have: it proves the collector is alive, nothing more. */
    data object Stale : StateAcceptance

    data class Rejected(val reason: RejectReason, val detail: String) : StateAcceptance
}

/**
 * Everything known about telemetry at one moment. Times ending `MonoMs` are monotonic (for ages
 * and timeouts); times ending `WallMs` are wall-clock (for display).
 */
data class TelemetrySnapshot(
    val serverRunning: Boolean = false,
    val listeningPort: Int? = null,
    val serverError: String? = null,
    val connection: TelemetryConnection = TelemetryConnection.NEVER_CONNECTED,
    val timeoutMs: Long = TelemetryHeartbeatMonitor.DEFAULT_TIMEOUT_MS,
    val lastPacketMonoMs: Long? = null,
    val lastSequence: Long? = null,
    val schemaVersion: Int? = null,
    /** The last applied state. Kept after a disconnect as "last known" - check [isConnectedAt]. */
    val state: LuaStatePacket? = null,
    val packetsReceived: Long = 0,
    val stalePackets: Long = 0,
    val rejectedPackets: Long = 0,
    val rateLimited: Long = 0,
    val lastParseError: String? = null,
    val lastParseErrorWallMs: Long? = null,
    val lastConnectedWallMs: Long? = null,
    val lastDisconnectedWallMs: Long? = null,
) {
    /** Judged from the clock rather than the last heartbeat check, so it is never behind. */
    fun isConnectedAt(nowMonoMs: Long): Boolean {
        val last = lastPacketMonoMs ?: return false
        return connection == TelemetryConnection.CONNECTED && nowMonoMs - last < timeoutMs
    }
}

/**
 * The single owner of telemetry state: the server writes to it, the UI and (later) the
 * monitoring engine read from it.
 *
 * It holds data and nothing else. It cannot tap, rejoin or alert; whatever arrives here is at
 * most one more signal for the existing, validated state machine to weigh. Android-free - both
 * clocks are injected - and safe to call from the server's request threads.
 */
class LuaTelemetryRepository(
    private val monoClock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    timeoutMs: Long = TelemetryHeartbeatMonitor.DEFAULT_TIMEOUT_MS,
) {
    private val lock = Any()
    private val heartbeat = TelemetryHeartbeatMonitor(timeoutMs)
    private val _snapshot = MutableStateFlow(TelemetrySnapshot(timeoutMs = heartbeat.timeoutMs))
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    /** Told about every connect and loss, outside the lock. */
    @Volatile
    var listener: ((TelemetryConnectionChange) -> Unit)? = null

    // Out-of-order packets that keep counting upwards mean the collector restarted, not a retry.
    private var staleRun = 0
    private var lastStaleSequence: Long? = null

    fun acceptState(body: String): StateAcceptance {
        val parsed = LuaTelemetryParser.parseState(body)
        val (outcome, changes) = synchronized(lock) {
            when (parsed) {
                is TelemetryParseResult.Rejected -> {
                    recordRejectionLocked("${parsed.reason.code}: ${parsed.detail}")
                    StateAcceptance.Rejected(parsed.reason, parsed.detail) to emptyList()
                }
                is TelemetryParseResult.Ok -> applyPacketLocked(parsed.value)
            }
        }
        notify(changes)
        return outcome
    }

    /** A request refused before parsing - too big, no length, cut short. */
    fun onTransportRejected(detail: String) = synchronized(lock) { recordRejectionLocked(detail) }

    fun onRateLimited() = synchronized(lock) {
        _snapshot.value = _snapshot.value.let { it.copy(rateLimited = it.rateLimited + 1) }
    }

    /** Notices silence. Call about once a second while the server runs. */
    fun tick(): List<TelemetryConnectionChange> {
        val changes = synchronized(lock) {
            val found = heartbeat.check(monoClock())
            if (found.isNotEmpty()) {
                _snapshot.value = withChanges(_snapshot.value.copy(connection = heartbeat.connection), found)
            }
            found
        }
        notify(changes)
        return changes
    }

    fun setTimeoutMs(timeoutMs: Long) = synchronized(lock) {
        heartbeat.timeoutMs = timeoutMs
        _snapshot.value = _snapshot.value.copy(timeoutMs = heartbeat.timeoutMs)
    }

    fun onServerStarted(port: Int) = synchronized(lock) {
        _snapshot.value = _snapshot.value.copy(serverRunning = true, listeningPort = port, serverError = null)
    }

    fun onServerError(message: String) = synchronized(lock) {
        _snapshot.value = _snapshot.value.copy(serverRunning = false, listeningPort = null, serverError = message)
    }

    /**
     * The server has gone away, so an established connection has ended. The next session starts
     * from a clean heartbeat and sequence, because a new collector run counts from the start.
     */
    fun onServerStopped() {
        val changes = synchronized(lock) {
            val current = _snapshot.value
            val wasConnected = heartbeat.connection == TelemetryConnection.CONNECTED
            val lastPacket = heartbeat.lastPacketMs
            heartbeat.reset()
            staleRun = 0
            lastStaleSequence = null
            val lost = if (wasConnected && lastPacket != null) {
                listOf(TelemetryConnectionChange.Lost(monoClock(), lastPacket))
            } else {
                emptyList()
            }
            _snapshot.value = withChanges(
                current.copy(
                    serverRunning = false,
                    listeningPort = null,
                    connection = if (wasConnected) TelemetryConnection.LOST else current.connection,
                    lastSequence = null,
                ),
                lost,
            )
            lost
        }
        notify(changes)
    }

    private fun applyPacketLocked(packet: LuaStatePacket): Pair<StateAcceptance, List<TelemetryConnectionChange>> {
        val now = monoClock()
        val changes = heartbeat.onPacket(now)
        val reconnected = changes.any { it is TelemetryConnectionChange.Connected }
        val before = _snapshot.value
        // Any valid packet - even a stale one - proves the collector is alive.
        val alive = withChanges(
            before.copy(
                packetsReceived = before.packetsReceived + 1,
                lastPacketMonoMs = now,
                connection = heartbeat.connection,
            ),
            changes,
        )

        val sequence = packet.sequence
        val previous = before.lastSequence
        val outOfOrder = !reconnected && sequence != null && previous != null && sequence <= previous
        if (outOfOrder && !confirmsRestartLocked(sequence!!)) {
            _snapshot.value = alive.copy(stalePackets = alive.stalePackets + 1)
            return StateAcceptance.Stale to changes
        }

        staleRun = 0
        lastStaleSequence = null
        _snapshot.value = alive.copy(state = packet, lastSequence = sequence, schemaVersion = packet.schemaVersion)
        return StateAcceptance.Accepted to changes
    }

    /**
     * A single out-of-order packet is a retry and is ignored. A collector restarted within the
     * timeout counts up from a low number again, which looks the same packet by packet - so a
     * run of [RESTART_CONFIRM_PACKETS] increasing ones is taken as a new run instead.
     */
    private fun confirmsRestartLocked(sequence: Long): Boolean {
        val previous = lastStaleSequence
        staleRun = if (previous != null && sequence > previous) staleRun + 1 else 1
        lastStaleSequence = sequence
        return staleRun >= RESTART_CONFIRM_PACKETS
    }

    private fun recordRejectionLocked(detail: String) {
        _snapshot.value = _snapshot.value.let {
            it.copy(rejectedPackets = it.rejectedPackets + 1, lastParseError = detail, lastParseErrorWallMs = wallClock())
        }
    }

    private fun withChanges(snapshot: TelemetrySnapshot, changes: List<TelemetryConnectionChange>): TelemetrySnapshot =
        changes.fold(snapshot) { acc, change ->
            when (change) {
                is TelemetryConnectionChange.Connected -> acc.copy(lastConnectedWallMs = wallClock())
                is TelemetryConnectionChange.Lost -> acc.copy(lastDisconnectedWallMs = wallClock())
            }
        }

    private fun notify(changes: List<TelemetryConnectionChange>) {
        val target = listener ?: return
        changes.forEach { runCatching { target(it) } }
    }

    companion object {
        const val RESTART_CONFIRM_PACKETS = 3
    }
}
