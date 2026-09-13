package com.unscathed.monitor.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryRateLimiterTest {

    @Test
    fun allowsABurstUpToCapacityThenRefuses() {
        val limiter = TelemetryRateLimiter(capacity = 5, refillPerSecond = 20.0)
        repeat(5) { assertTrue("request ${it + 1}", limiter.tryAcquire(0)) }
        assertFalse(limiter.tryAcquire(0))
    }

    @Test
    fun refillsWithTime() {
        val limiter = TelemetryRateLimiter(capacity = 5, refillPerSecond = 20.0)
        repeat(5) { limiter.tryAcquire(0) }
        // 100 ms at 20 per second is exactly two more.
        assertTrue(limiter.tryAcquire(100))
        assertTrue(limiter.tryAcquire(100))
        assertFalse(limiter.tryAcquire(100))
    }

    @Test
    fun aLongIdleNeverBanksMoreThanCapacity() {
        val limiter = TelemetryRateLimiter(capacity = 3, refillPerSecond = 20.0)
        limiter.tryAcquire(0)
        val later = 3_600_000L
        repeat(3) { assertTrue(limiter.tryAcquire(later)) }
        assertFalse(limiter.tryAcquire(later))
    }

    @Test
    fun aClockSteppingBackwardsMintsNothing() {
        val limiter = TelemetryRateLimiter(capacity = 2, refillPerSecond = 20.0)
        repeat(2) { limiter.tryAcquire(10_000) }
        assertFalse(limiter.tryAcquire(0))
        assertFalse(limiter.tryAcquire(5_000))
    }

    /** A collector doing its job - a state a second, an event every few - is never limited. */
    @Test
    fun normalCollectorTrafficIsNeverLimited() {
        val limiter = TelemetryRateLimiter()
        var t = 0L
        while (t <= 10 * 60_000L) {
            assertTrue("state at $t", limiter.tryAcquire(t))
            if (t % 5_000L == 0L) assertTrue("event at $t", limiter.tryAcquire(t))
            t += 1_000
        }
    }
}
