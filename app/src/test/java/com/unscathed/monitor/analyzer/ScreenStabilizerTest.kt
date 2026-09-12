package com.unscathed.monitor.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenStabilizerTest {
    private fun reading(state: ScreenState, confidence: Double = 0.9) = ScreenReading(state, confidence)

    @Test
    fun aStateIsOnlyConfirmedAfterEnoughAgreeingReadings() {
        val s = ScreenStabilizer(defaultStreak = 3, perState = emptyMap())
        assertNull(s.offer(reading(ScreenState.IN_GAME), 1_000))
        assertNull(s.offer(reading(ScreenState.IN_GAME), 2_000))
        assertEquals(ScreenState.IN_GAME, s.offer(reading(ScreenState.IN_GAME), 3_000))
        assertEquals(ScreenState.IN_GAME, s.confirmed)
        assertEquals(3_000, s.confirmedAtMs)
    }

    /** The whole point: one bad frame in the middle must not move anything. */
    @Test
    fun oneStrayReadingNeverFlipsTheState() {
        val s = ScreenStabilizer(defaultStreak = 3, perState = emptyMap())
        repeat(3) { s.offer(reading(ScreenState.IN_GAME), it * 1_000L) }
        assertEquals(ScreenState.IN_GAME, s.confirmed)

        assertNull(s.offer(reading(ScreenState.WELCOME), 4_000))
        assertNull(s.offer(reading(ScreenState.IN_GAME), 5_000))
        assertNull(s.offer(reading(ScreenState.WELCOME), 6_000))
        assertEquals(ScreenState.IN_GAME, s.confirmed)
    }

    @Test
    fun aStreakThatIsBrokenStartsOver() {
        val s = ScreenStabilizer(defaultStreak = 3, perState = emptyMap())
        repeat(3) { s.offer(reading(ScreenState.IN_GAME), it * 1_000L) }
        s.offer(reading(ScreenState.WELCOME), 4_000)
        s.offer(reading(ScreenState.WELCOME), 5_000)
        s.offer(reading(ScreenState.LOADING), 6_000) // breaks it
        s.offer(reading(ScreenState.WELCOME), 7_000)
        s.offer(reading(ScreenState.WELCOME), 8_000)
        assertEquals(ScreenState.IN_GAME, s.confirmed)
        assertEquals(ScreenState.WELCOME, s.offer(reading(ScreenState.WELCOME), 9_000))
    }

    /** Disconnects confirm sooner so recovery is not slow; welcome screens take longer. */
    @Test
    fun perStateStreaksAreHonoured() {
        val s = ScreenStabilizer(
            defaultStreak = 3,
            perState = mapOf(ScreenState.DISCONNECTED to 2, ScreenState.WELCOME to 4),
        )
        s.offer(reading(ScreenState.DISCONNECTED), 1_000)
        assertEquals(ScreenState.DISCONNECTED, s.offer(reading(ScreenState.DISCONNECTED), 2_000))

        repeat(3) { s.offer(reading(ScreenState.WELCOME), 3_000 + it * 1_000L) }
        assertEquals(ScreenState.DISCONNECTED, s.confirmed)
        assertEquals(ScreenState.WELCOME, s.offer(reading(ScreenState.WELCOME), 7_000))
    }

    @Test
    fun theStateBeingWaitedOnIsVisible() {
        val s = ScreenStabilizer(defaultStreak = 3, perState = emptyMap())
        repeat(3) { s.offer(reading(ScreenState.IN_GAME), it * 1_000L) }
        s.offer(reading(ScreenState.WELCOME), 4_000)
        assertEquals(ScreenState.WELCOME, s.pending)
        assertEquals(2, s.pendingRemaining)
        s.offer(reading(ScreenState.WELCOME), 5_000)
        assertEquals(1, s.pendingRemaining)
    }

    @Test
    fun repeatingTheConfirmedStateReportsNothingNew() {
        val s = ScreenStabilizer(defaultStreak = 2, perState = emptyMap())
        s.offer(reading(ScreenState.IN_GAME), 1_000)
        assertEquals(ScreenState.IN_GAME, s.offer(reading(ScreenState.IN_GAME), 2_000))
        assertNull(s.offer(reading(ScreenState.IN_GAME), 3_000))
        assertNull(s.offer(reading(ScreenState.IN_GAME), 4_000))
    }
}
