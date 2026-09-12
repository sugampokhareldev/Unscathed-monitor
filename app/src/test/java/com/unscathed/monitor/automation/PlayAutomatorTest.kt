package com.unscathed.monitor.automation

import com.unscathed.monitor.analyzer.NormBox
import com.unscathed.monitor.analyzer.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayAutomatorTest {
    private val button = NormBox(0.4f, 0.90f, 0.6f, 0.96f)

    private fun automator(
        verifyWindowMs: Long = 30_000,
        cooldownMs: Long = 20_000,
        maxAttempts: Int = 3,
        standDownMs: Long = 600_000,
    ) = PlayAutomator(PlayPolicy(true, verifyWindowMs, cooldownMs, maxAttempts, standDownMs))

    @Test
    fun theWelcomeScreenWithAButtonIsClicked() {
        val p = automator()
        val intent = p.decide(ScreenState.WELCOME, button, 1_000)
        assertTrue(intent is PlayIntent.Click)
        assertEquals(1, (intent as PlayIntent.Click).attempt)
    }

    /** Without a located button there is nothing to verify, so nothing is tapped. */
    @Test
    fun nothingIsClickedWithoutALocatedButton() {
        val p = automator()
        assertEquals(PlayIntent.Idle, p.decide(ScreenState.WELCOME, null, 1_000))
    }

    @Test
    fun otherScreensAreLeftAlone() {
        val p = automator()
        listOf(ScreenState.IN_GAME, ScreenState.LOADING, ScreenState.ROBLOX_HOME, ScreenState.UNKNOWN)
            .forEach { assertEquals(PlayIntent.Idle, p.decide(it, button, 1_000)) }
    }

    /** The rule that stops the button being hammered: after a tap, wait and watch. */
    @Test
    fun oneTapThenWaitingForTheResult() {
        val p = automator(verifyWindowMs = 30_000)
        p.decide(ScreenState.WELCOME, button, 1_000)
        p.onClicked(1_000, dispatched = true)

        // Still on the welcome screen a few seconds later: verify, do not tap again.
        assertTrue(p.decide(ScreenState.WELCOME, button, 5_000) is PlayIntent.Verifying)
        assertTrue(p.decide(ScreenState.WELCOME, button, 20_000) is PlayIntent.Verifying)
    }

    @Test
    fun theGameLoadingCountsAsSuccess() {
        val p = automator()
        p.decide(ScreenState.WELCOME, button, 1_000)
        p.onClicked(1_000, dispatched = true)
        assertEquals(PlayIntent.Idle, p.decide(ScreenState.LOADING, null, 6_000))

        val outcome = p.lastOutcome
        assertTrue(outcome is PlayOutcome.Entered)
        assertEquals(5_000, (outcome as PlayOutcome.Entered).afterMs)
    }

    @Test
    fun aTapThatChangesNothingIsAFailureAndThenTheCooldownApplies() {
        val p = automator(verifyWindowMs = 30_000, cooldownMs = 20_000)
        p.decide(ScreenState.WELCOME, button, 1_000)
        p.onClicked(1_000, dispatched = true)

        // Window closed, still the welcome screen.
        val after = p.decide(ScreenState.WELCOME, button, 40_000)
        assertTrue(p.lastOutcome is PlayOutcome.Failed)
        // The cooldown since the previous tap has long passed, so it may try again.
        assertTrue(after is PlayIntent.Click)
        assertEquals(2, (after as PlayIntent.Click).attempt)
    }

    @Test
    fun theCooldownBlocksAQuickSecondTap() {
        val p = automator(verifyWindowMs = 5_000, cooldownMs = 20_000)
        p.decide(ScreenState.WELCOME, button, 1_000)
        p.onClicked(1_000, dispatched = true)
        val soon = p.decide(ScreenState.WELCOME, button, 8_000)
        assertTrue(soon is PlayIntent.Waiting)
        assertEquals(13_000, (soon as PlayIntent.Waiting).remainingMs)
    }

    /** Repeated failure must stand the automation down rather than tap forever. */
    @Test
    fun repeatedFailuresPauseAutoPlay() {
        val p = automator(verifyWindowMs = 10_000, cooldownMs = 0, maxAttempts = 3, standDownMs = 600_000)
        var now = 1_000L
        repeat(3) {
            val intent = p.decide(ScreenState.WELCOME, button, now)
            assertTrue("attempt ${it + 1} should have clicked, got $intent", intent is PlayIntent.Click)
            p.onClicked(now, dispatched = true)
            now += 15_000
        }
        val standDown = p.decide(ScreenState.WELCOME, button, now)
        assertTrue(p.lastOutcome is PlayOutcome.StoodDown)
        assertTrue(standDown is PlayIntent.Waiting)
    }

    @Test
    fun aTapThatCouldNotBeSentIsRecordedAsAFailure() {
        val p = automator()
        p.decide(ScreenState.WELCOME, button, 1_000)
        p.onClicked(1_000, dispatched = false)
        val outcome = p.lastOutcome
        assertTrue(outcome is PlayOutcome.Failed)
        assertTrue((outcome as PlayOutcome.Failed).reason.contains("Accessibility"))
    }

    @Test
    fun gettingIntoTheGameAnyOtherWayClearsEverything() {
        val p = automator(cooldownMs = 20_000)
        p.decide(ScreenState.WELCOME, button, 1_000)
        p.onClicked(1_000, dispatched = true)
        p.onEnteredGame()
        assertTrue(!p.verifying)
        // The cooldown since the tap still applies; it is about the button, not the state.
        assertTrue(p.decide(ScreenState.WELCOME, button, 5_000) is PlayIntent.Waiting)
        assertTrue(p.decide(ScreenState.WELCOME, button, 30_000) is PlayIntent.Click)
    }

    /**
     * A touch-lock overlay swallows taps silently, so a blocked tap must not be spent as an
     * attempt - otherwise a lock left on overnight burns every attempt in the first minute.
     */
    @Test
    fun aBlockedTapIsNotSpentAsAnAttempt() {
        val p = automator(cooldownMs = 0, maxAttempts = 3)
        repeat(10) {
            val intent = p.decide(ScreenState.WELCOME, button, 1_000 + it * 1_000L, blockedBy = "com.touch.lock")
            assertTrue(intent is PlayIntent.Blocked)
            assertEquals("com.touch.lock", (intent as PlayIntent.Blocked).by)
        }
        // Nothing was counted, so the very next unblocked tick is still attempt 1.
        val free = p.decide(ScreenState.WELCOME, button, 20_000)
        assertTrue(free is PlayIntent.Click)
        assertEquals(1, (free as PlayIntent.Click).attempt)
    }

    @Test
    fun switchingAutoPlayOffStopsEverything() {
        val p = PlayAutomator(PlayPolicy(enabled = false))
        assertEquals(PlayIntent.Idle, p.decide(ScreenState.WELCOME, button, 1_000))
    }
}
