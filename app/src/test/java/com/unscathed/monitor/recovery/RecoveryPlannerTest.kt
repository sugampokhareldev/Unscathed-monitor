package com.unscathed.monitor.recovery

import com.unscathed.monitor.analyzer.DisconnectMatch
import com.unscathed.monitor.analyzer.NormBox
import com.unscathed.monitor.state.RobloxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPlannerTest {
    private val button = NormBox(0.5f, 0.6f, 0.6f, 0.65f)
    private fun disconnected(code: Int? = 277, withButton: Boolean = true) =
        RobloxState.Disconnected(DisconnectMatch("x", code, if (withButton) button else null, null, "x"))

    private val policy = RecoveryPolicy(maxReconnects = 3, maxRelaunches = 2, giveUpCooldownMs = 1_000_000)

    @Test
    fun escalatesFromReconnectToRelaunchToGiveUp() {
        val p = RecoveryPlanner()
        val actions = (0 until 6).map { p.next(disconnected(), it * 1000L, policy)?.action }
        assertEquals(
            listOf(
                RecoveryAction.TAP_RECONNECT, RecoveryAction.TAP_RECONNECT, RecoveryAction.TAP_RECONNECT,
                RecoveryAction.RELAUNCH, RecoveryAction.RELAUNCH, RecoveryAction.GIVE_UP,
            ),
            actions,
        )
        // Cooling down: nothing happens.
        assertNull(p.next(disconnected(), 10_000, policy))
        // After the cooldown the budget is fresh.
        assertEquals(RecoveryAction.TAP_RECONNECT, p.next(disconnected(), 2_000_000, policy)?.action)
    }

    @Test
    fun attemptNumbersCountUp() {
        val p = RecoveryPlanner()
        val first = p.next(disconnected(), 0, policy)!!
        val second = p.next(disconnected(), 1, policy)!!
        assertEquals(1, first.attempt)
        assertEquals(2, second.attempt)
        assertEquals(5, second.maxAttempts)
    }

    @Test
    fun noButtonGoesStraightToRelaunch() {
        val p = RecoveryPlanner()
        assertEquals(RecoveryAction.RELAUNCH, p.next(disconnected(withButton = false), 0, policy)?.action)
    }

    @Test
    fun blockedErrorCodeIsSkippedOnce() {
        val p = RecoveryPlanner()
        val step = p.next(disconnected(code = 273), 0, policy.copy(noRecoverCodes = setOf(273)))
        assertEquals(RecoveryAction.SKIP, step?.action)
        assertNull(p.next(disconnected(code = 273), 5_000, policy.copy(noRecoverCodes = setOf(273))))
    }

    @Test
    fun frozenIsOnlyRecoveredWhenEnabled() {
        assertNull(RecoveryPlanner().next(RobloxState.Frozen, 0, policy))
        assertEquals(
            RecoveryAction.RELAUNCH,
            RecoveryPlanner().next(RobloxState.Frozen, 0, policy.copy(recoverFrozen = true))?.action,
        )
    }

    @Test
    fun previewDoesNotConsumeAttempts() {
        val p = RecoveryPlanner()
        p.preview(disconnected(), 0, policy)
        p.preview(disconnected(), 0, policy)
        assertFalse(p.inEpisode)
        assertEquals(1, p.next(disconnected(), 0, policy)?.attempt)
    }

    @Test
    fun sustainedHealthResetsCounters() {
        val p = RecoveryPlanner()
        p.next(disconnected(), 0, policy)
        assertTrue(p.inEpisode)
        p.onHealthy(1_000, policy)
        p.onHealthy(1_000 + policy.stableResetMs, policy)
        assertFalse(p.inEpisode)
    }

    @Test
    fun persistentModeNeverGivesUpAndBacksOff() {
        val p = RecoveryPlanner()
        val persistent = policy.copy(persistent = true, maxRelaunches = 2)
        val closed = RobloxState.RobloxClosed

        // Two quick rejoins with no gap.
        assertEquals(RecoveryAction.RELAUNCH, p.next(closed, 0, persistent)?.action)
        p.onStepFinished(0, persistent)
        assertEquals(RecoveryAction.RELAUNCH, p.next(closed, 0, persistent)?.action)
        p.onStepFinished(0, persistent)

        // Third rejoin is still immediate; after it the backoff starts at 30 s, then 60 s.
        val third = p.next(closed, 0, persistent)!!
        assertEquals(RecoveryAction.RELAUNCH, third.action)
        assertNull(third.maxAttempts)
        assertEquals("3/∞", third.progress)
        p.onStepFinished(1_000, persistent)
        assertNull(p.next(closed, 30_000, persistent))
        assertEquals(RecoveryAction.RELAUNCH, p.next(closed, 31_000, persistent)?.action)
        p.onStepFinished(31_000, persistent)
        assertEquals(60_000, p.waitRemainingMs(31_000))

        // Way past the cap: still rejoining, never GIVE_UP, gap capped at 5 min.
        var t = 100_000L
        repeat(20) {
            t += p.waitRemainingMs(t)
            assertEquals(RecoveryAction.RELAUNCH, p.next(closed, t, persistent)?.action)
            p.onStepFinished(t, persistent)
        }
        assertEquals(persistent.retryBackoffMaxMs, p.waitRemainingMs(t))
    }

    @Test
    fun waitsWhileOfflineWithoutUsingAttempts() {
        val p = RecoveryPlanner()
        assertNull(p.next(disconnected(), 0, policy, online = false))
        assertNull(p.next(disconnected(), 60_000, policy, online = false))
        assertFalse(p.inEpisode)
        assertEquals(1, p.next(disconnected(), 120_000, policy, online = true)?.attempt)
    }

    @Test
    fun rejoinOffGivesUpAfterTaps() {
        val p = RecoveryPlanner()
        val tapsOnly = policy.copy(rejoin = false, maxReconnects = 1)
        assertEquals(RecoveryAction.TAP_RECONNECT, p.next(disconnected(), 0, tapsOnly)?.action)
        assertEquals(RecoveryAction.GIVE_UP, p.next(disconnected(), 1, tapsOnly)?.action)
    }

    @Test
    fun nothingEnabledDoesNothing() {
        assertNull(RecoveryPlanner().next(disconnected(), 0, policy.copy(tapReconnect = false, rejoin = false)))
    }

    @Test
    fun briefHealthDoesNotReset() {
        val p = RecoveryPlanner()
        p.next(disconnected(), 0, policy)
        p.onHealthy(1_000, policy)
        p.onUnhealthy()
        p.onHealthy(1_000 + policy.stableResetMs, policy)
        assertTrue(p.inEpisode)
    }

    /**
     * With the screen covered, tapping Reconnect cannot work - but relaunching is an intent
     * rather than a touch, so recovery should skip straight to that instead of wasting taps.
     */
    @Test
    fun blockedTapsSkipStraightToRejoining() {
        val planner = RecoveryPlanner()
        val policy = RecoveryPolicy(tapReconnect = true, rejoin = true, tapsBlocked = true)
        val step = planner.next(disconnected(), 1_000, policy)
        assertEquals(RecoveryAction.RELAUNCH, step?.action)
    }

    @Test
    fun unblockedTapsStillTapReconnectFirst() {
        val planner = RecoveryPlanner()
        val policy = RecoveryPolicy(tapReconnect = true, rejoin = true, tapsBlocked = false)
        val step = planner.next(disconnected(), 1_000, policy)
        assertEquals(RecoveryAction.TAP_RECONNECT, step?.action)
    }
}
