package com.unscathed.monitor.state

import com.unscathed.monitor.analyzer.DisconnectMatch
import com.unscathed.monitor.analyzer.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogStateMachineTest {
    private val match = DisconnectMatch("Disconnected", 277, null, null, "Disconnected")

    private fun obs(
        nowMs: Long,
        screen: ScreenState? = ScreenState.IN_GAME,
        stillForMs: Long? = 0,
        disconnect: DisconnectCheck = DisconnectCheck.Clear,
    ) = Observation(nowMs, screen, stillForMs, disconnect)

    @Test
    fun theGameScreenWithMotionIsHealthy() {
        val m = WatchdogStateMachine()
        m.onObservation(obs(1_000))
        assertEquals(RobloxState.InGame, m.state)
    }

    @Test
    fun aStillGameScreenBecomesSuspectThenFrozen() {
        val m = WatchdogStateMachine(Thresholds(suspectStillMs = 10_000, freezeMs = 60_000))
        m.onObservation(obs(1_000, stillForMs = 0))
        assertEquals(RobloxState.InGame, m.state)

        m.onObservation(obs(12_000, stillForMs = 11_000))
        assertEquals(RobloxState.PossiblyFrozen, m.state)

        m.onObservation(obs(70_000, stillForMs = 69_000))
        assertEquals(RobloxState.Frozen, m.state)
    }

    /** A still welcome screen is not frozen: it is supposed to sit there until Play is pressed. */
    @Test
    fun freezeIsOnlyJudgedInsideTheGame() {
        val m = WatchdogStateMachine(Thresholds(freezeMs = 60_000))
        m.onObservation(obs(100_000, screen = ScreenState.WELCOME, stillForMs = 300_000))
        assertEquals(RobloxState.Welcome, m.state)

        m.onObservation(obs(160_000, screen = ScreenState.LOADING, stillForMs = 300_000))
        assertEquals(RobloxState.Loading, m.state)
    }

    @Test
    fun everyConfirmedScreenMapsToItsState() {
        val m = WatchdogStateMachine()
        m.onObservation(obs(1_000, screen = ScreenState.ROBLOX_CLOSED))
        assertEquals(RobloxState.RobloxClosed, m.state)
        assertTrue(m.state.isProblem)

        m.onObservation(obs(2_000, screen = ScreenState.ROBLOX_HOME))
        assertEquals(RobloxState.RobloxHome, m.state)
        assertTrue(m.state.isProblem)

        m.onObservation(obs(3_000, screen = ScreenState.UNKNOWN))
        assertEquals(RobloxState.Unknown, m.state)
        // Unknown is reported but never acted on.
        assertTrue(!m.state.isProblem)
    }

    @Test
    fun aConfirmedDisconnectCarriesItsMatch() {
        val m = WatchdogStateMachine()
        m.onObservation(obs(1_000))
        m.onObservation(obs(2_000, screen = ScreenState.DISCONNECTED, disconnect = DisconnectCheck.Found(match)))
        val state = m.state
        assertTrue(state is RobloxState.Disconnected)
        assertEquals(277, (state as RobloxState.Disconnected).match.errorCode)
    }

    /** A tick with no confirmed screen must not move anything. */
    @Test
    fun noScreenKeepsTheLastState() {
        val m = WatchdogStateMachine()
        m.onObservation(obs(1_000, screen = ScreenState.ROBLOX_HOME))
        val transition = m.onObservation(obs(2_000, screen = null, stillForMs = null))
        assertNull(transition)
        assertEquals(RobloxState.RobloxHome, m.state)
    }

    @Test
    fun aDisconnectRefreshedWithNewTextIsNotANewTransition() {
        val m = WatchdogStateMachine()
        m.onObservation(obs(1_000, screen = ScreenState.DISCONNECTED, disconnect = DisconnectCheck.Found(match)))
        val second = m.onObservation(
            obs(
                2_000,
                screen = ScreenState.DISCONNECTED,
                disconnect = DisconnectCheck.Found(match.copy(text = "Disconnected | Reconnect")),
            ),
        )
        assertNull(second)
    }

    @Test
    fun recoveringOverridesTheReportedStateButNotHealth() {
        val m = WatchdogStateMachine()
        m.onObservation(obs(1_000, screen = ScreenState.ROBLOX_CLOSED))
        val begin = m.beginRecovery("Rejoin game 1/3")
        assertEquals(RobloxState.Recovering("Rejoin game 1/3"), begin?.to)
        assertEquals(RobloxState.RobloxClosed, m.health)

        m.onObservation(obs(30_000))
        assertEquals(RobloxState.InGame, m.health)
        val end = m.endRecovery()
        assertEquals(RobloxState.InGame, end?.to)
    }

    @Test
    fun ocrIsRequestedWhileDisconnectedOrLost() {
        val m = WatchdogStateMachine()
        m.onObservation(obs(1_000))
        assertTrue(!m.wantsOcr)

        m.onObservation(obs(2_000, screen = ScreenState.DISCONNECTED, disconnect = DisconnectCheck.Found(match)))
        assertTrue(m.wantsOcr)

        m.onObservation(obs(3_000, screen = ScreenState.UNKNOWN))
        assertTrue(m.wantsOcr)
    }
}
