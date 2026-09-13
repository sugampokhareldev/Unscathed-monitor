package com.unscathed.monitor.telemetry

/**
 * Token bucket for the telemetry server.
 *
 * A well-behaved collector sends about one packet a second, so the defaults leave an order of
 * magnitude of headroom and only a runaway loop - or something else on the phone hammering the
 * port - ever hits them. The clock is a parameter so it can be tested deterministically.
 */
class TelemetryRateLimiter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val refillPerSecond: Double = DEFAULT_REFILL_PER_SECOND,
) {
    private var tokens = capacity.toDouble()
    private var lastMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastMs
        if (last == null) {
            lastMs = nowMs
        } else if (nowMs > last) {
            tokens = minOf(capacity.toDouble(), tokens + (nowMs - last) * refillPerSecond / 1000.0)
            lastMs = nowMs
        }
        // A clock that steps backwards mints nothing.
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    companion object {
        const val DEFAULT_CAPACITY = 40
        const val DEFAULT_REFILL_PER_SECOND = 20.0
    }
}
