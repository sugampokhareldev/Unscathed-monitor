package com.unscathed.monitor.telemetry

enum class TelemetryConnection { NEVER_CONNECTED, CONNECTED, LOST }

/** A change in whether the collector is talking to us. Times are monotonic milliseconds. */
sealed interface TelemetryConnectionChange {
    val atMs: Long

    /** [gapMs] is how long it had been silent, or null for the very first packet. */
    data class Connected(override val atMs: Long, val gapMs: Long?) : TelemetryConnectionChange

    data class Lost(override val atMs: Long, val lastPacketMs: Long) : TelemetryConnectionChange
}

/**
 * Decides whether telemetry is alive from nothing but packet arrival times.
 *
 * Losing telemetry says nothing about Roblox - the collector can stop for many reasons while the
 * game carries on - so this only ever reports on the collector itself. Each transition is
 * reported exactly once. It is Android-free and takes the time as a parameter, so it is tested
 * with a fake clock.
 */
class TelemetryHeartbeatMonitor(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    var timeoutMs: Long = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)
        set(value) {
            field = value.coerceAtLeast(MIN_TIMEOUT_MS)
        }

    var connection: TelemetryConnection = TelemetryConnection.NEVER_CONNECTED
        private set

    var lastPacketMs: Long? = null
        private set

    /**
     * Records a packet. If the silence before it was already long enough to count as lost - and
     * nobody called [check] in time to notice - the loss is reported before the reconnection,
     * so no gap is ever hidden.
     */
    fun onPacket(nowMs: Long): List<TelemetryConnectionChange> {
        val changes = check(nowMs).toMutableList()
        val previous = lastPacketMs
        lastPacketMs = nowMs
        if (connection != TelemetryConnection.CONNECTED) {
            connection = TelemetryConnection.CONNECTED
            changes += TelemetryConnectionChange.Connected(nowMs, previous?.let { nowMs - it })
        }
        return changes
    }

    /** Call periodically: silence cannot announce itself, so loss is noticed here. */
    fun check(nowMs: Long): List<TelemetryConnectionChange> {
        val last = lastPacketMs ?: return emptyList()
        if (connection == TelemetryConnection.CONNECTED && nowMs - last >= timeoutMs) {
            connection = TelemetryConnection.LOST
            return listOf(TelemetryConnectionChange.Lost(nowMs, last))
        }
        return emptyList()
    }

    /** Computed from the clock, so it is never stale between two [check] calls. */
    fun isConnected(nowMs: Long): Boolean {
        val last = lastPacketMs ?: return false
        return connection == TelemetryConnection.CONNECTED && nowMs - last < timeoutMs
    }

    fun lastPacketAgeMs(nowMs: Long): Long? = lastPacketMs?.let { (nowMs - it).coerceAtLeast(0) }

    fun reset() {
        connection = TelemetryConnection.NEVER_CONNECTED
        lastPacketMs = null
    }

    companion object {
        /** Packets arrive every 1-2 s, so this allows several to go missing before calling it. */
        const val DEFAULT_TIMEOUT_MS = 7_000L
        const val MIN_TIMEOUT_MS = 1_000L
    }
}
