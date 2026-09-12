package com.unscathed.monitor.analyzer

/**
 * Turns "I saw it once" into "it is really there", and stops the same sighting alerting twice.
 *
 * An in-game message stays on screen for several seconds, so it lands in many consecutive scans;
 * a misread lands in one. Firing needs [confirmScans] sightings inside [confirmWindowMs]. After
 * a fire, the same key is locked out for [cooldownMs], and it must additionally have been absent
 * for [clearMs] before it can ever fire again - so one appearance produces exactly one alert no
 * matter how long the message lingers.
 */
class EventConfirmer(
    private val confirmScans: Int = 2,
    private val confirmWindowMs: Long = 12_000L,
    private val cooldownMs: Long = 10 * 60_000L,
    private val clearMs: Long = 30_000L,
) {
    private class Track(
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var sightings: Int,
        var firedAtMs: Long? = null,
    )

    private val tracks = mutableMapOf<String, Track>()

    sealed interface Outcome {
        /** Seen, but not enough times yet. [remaining] more confirming scans are needed. */
        data class Pending(val sightings: Int, val remaining: Int) : Outcome

        /** Confirmed now - send the alert. [sightings] is how many scans agreed. */
        data class Confirmed(val sightings: Int, val firstSeenMs: Long) : Outcome

        /** Seen, but already alerted for this appearance. */
        data object Suppressed : Outcome
    }

    /** Call on every scan where the thing was on screen. */
    fun sighted(key: String, nowMs: Long): Outcome {
        val track = tracks[key]
        // A long enough gap means the previous appearance ended: start a fresh one.
        if (track == null || nowMs - track.lastSeenMs > clearMs) {
            tracks[key] = Track(firstSeenMs = nowMs, lastSeenMs = nowMs, sightings = 1)
            return pendingOutcome(1)
        }

        track.lastSeenMs = nowMs
        track.firedAtMs?.let { firedAt ->
            if (nowMs - firedAt < cooldownMs) return Outcome.Suppressed
            // Cooldown is over but the message never left the screen, so this is still the
            // same appearance. Only the absence gap above may start a new one.
            return Outcome.Suppressed
        }

        // Sightings spread too far apart are not one message on screen; restart the count.
        if (nowMs - track.firstSeenMs > confirmWindowMs) {
            track.firstSeenMs = nowMs
            track.sightings = 1
            return pendingOutcome(1)
        }

        track.sightings++
        if (track.sightings < confirmScans) return pendingOutcome(track.sightings)
        track.firedAtMs = nowMs
        return Outcome.Confirmed(track.sightings, track.firstSeenMs)
    }

    /** Call on scans where the thing was NOT on screen, so its absence is timed properly. */
    fun absent(key: String, nowMs: Long) {
        val track = tracks[key] ?: return
        if (nowMs - track.lastSeenMs > clearMs) tracks.remove(key)
    }

    /** Tidies up keys nothing has touched in a long time. */
    fun prune(nowMs: Long) {
        tracks.entries.removeAll { nowMs - it.value.lastSeenMs > cooldownMs + clearMs }
    }

    fun cooldownRemainingMs(key: String, nowMs: Long): Long {
        val firedAt = tracks[key]?.firedAtMs ?: return 0
        return (cooldownMs - (nowMs - firedAt)).coerceAtLeast(0)
    }

    fun reset() = tracks.clear()

    private fun pendingOutcome(sightings: Int) =
        Outcome.Pending(sightings, (confirmScans - sightings).coerceAtLeast(0))
}
