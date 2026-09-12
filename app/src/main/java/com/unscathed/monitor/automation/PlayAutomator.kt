package com.unscathed.monitor.automation

import com.unscathed.monitor.analyzer.NormBox
import com.unscathed.monitor.analyzer.ScreenState

data class PlayPolicy(
    val enabled: Boolean = true,
    /** Wait this long after a click before deciding whether it worked. */
    val verifyWindowMs: Long = 30_000L,
    /** Shortest gap between two clicks, so a failed detection can never spam the button. */
    val cooldownMs: Long = 20_000L,
    /** Consecutive failed clicks before the automator stands down. */
    val maxAttempts: Int = 4,
    /** How long to stand down for after [maxAttempts] failures. */
    val standDownMs: Long = 10 * 60_000L,
)

/** What the automator wants done this tick. */
sealed interface PlayIntent {
    /** Nothing to do. */
    data object Idle : PlayIntent

    /** Tap here. [attempt] is 1-based. */
    data class Click(val target: NormBox, val attempt: Int, val maxAttempts: Int) : PlayIntent {
        val progress: String get() = "$attempt/$maxAttempts"
    }

    /** A click has been made; waiting to see whether the game started loading. */
    data class Verifying(val sinceMs: Long) : PlayIntent

    /** Waiting out a cooldown or a stand-down. */
    data class Waiting(val reason: String, val remainingMs: Long) : PlayIntent

    /** Something is covering the screen, so a tap cannot reach the game at all. */
    data class Blocked(val by: String) : PlayIntent
}

/** How a click turned out, once the verification window closed or the screen moved on. */
sealed interface PlayOutcome {
    data class Entered(val afterMs: Long) : PlayOutcome
    data class Failed(val attempt: Int, val reason: String) : PlayOutcome
    data class StoodDown(val attempts: Int) : PlayOutcome
}

/**
 * Presses Play on the welcome screen, once.
 *
 * The cycle is detect -> verify -> act -> confirm: the caller only gets a [PlayIntent.Click] for
 * a welcome screen the classifier has already confirmed *and* whose Play button was actually
 * located; after the tap the automator refuses to do anything else until it has seen the screen
 * change (to loading, or into the game), or until the verification window runs out. Repeated
 * failures back it off completely rather than letting it hammer the button.
 */
class PlayAutomator(var policy: PlayPolicy = PlayPolicy()) {
    private var attempts = 0
    private var clickedAtMs: Long? = null
    private var lastClickMs: Long? = null
    private var standDownUntilMs: Long? = null

    /** Set while a click is waiting to be judged. */
    val verifying: Boolean get() = clickedAtMs != null
    var lastOutcome: PlayOutcome? = null
        private set
    var lastClickAtMs: Long? = null
        private set

    /**
     * @param state the confirmed screen state, never a raw reading.
     * @param playButton where the Play button was found, or null if it was not.
     * @param blockedBy the app covering the screen, if taps cannot reach the game.
     */
    fun decide(state: ScreenState, playButton: NormBox?, nowMs: Long, blockedBy: String? = null): PlayIntent {
        if (!policy.enabled) return PlayIntent.Idle

        // Confirm an earlier click before considering another one.
        clickedAtMs?.let { clickedAt ->
            when {
                state == ScreenState.LOADING || state == ScreenState.IN_GAME -> {
                    clickedAtMs = null
                    attempts = 0
                    lastOutcome = PlayOutcome.Entered(nowMs - clickedAt)
                    return PlayIntent.Idle
                }
                nowMs - clickedAt < policy.verifyWindowMs -> return PlayIntent.Verifying(clickedAt)
                else -> {
                    clickedAtMs = null
                    lastOutcome = PlayOutcome.Failed(attempts, "Still on the welcome screen after the tap")
                    if (attempts >= policy.maxAttempts) {
                        standDownUntilMs = nowMs + policy.standDownMs
                        lastOutcome = PlayOutcome.StoodDown(attempts)
                    }
                }
            }
        }

        standDownUntilMs?.let { until ->
            if (nowMs < until) {
                return PlayIntent.Waiting("Play automation paused after ${attempts} failed taps", until - nowMs)
            }
            standDownUntilMs = null
            attempts = 0
        }

        if (state != ScreenState.WELCOME) return PlayIntent.Idle
        if (playButton == null) return PlayIntent.Idle
        // A tap that cannot land must not be counted as an attempt, or a touch-lock left on
        // overnight would use up every attempt in the first minute and stand the feature down.
        if (blockedBy != null) return PlayIntent.Blocked(blockedBy)

        lastClickMs?.let { last ->
            val since = nowMs - last
            if (since < policy.cooldownMs) return PlayIntent.Waiting("Play cooldown", policy.cooldownMs - since)
        }
        return PlayIntent.Click(playButton, attempts + 1, policy.maxAttempts)
    }

    /** Call once the tap has actually been dispatched (or failed to dispatch). */
    fun onClicked(nowMs: Long, dispatched: Boolean) {
        lastClickMs = nowMs
        if (!dispatched) {
            attempts++
            lastOutcome = PlayOutcome.Failed(attempts, "The tap could not be sent (Accessibility off?)")
            return
        }
        attempts++
        clickedAtMs = nowMs
        lastClickAtMs = nowMs
    }

    /** Consumes [lastOutcome] so it is reported once, not on every tick. */
    fun clearOutcome() {
        lastOutcome = null
    }

    /** Entering the game by any other route (a rejoin, the player themselves) clears the state. */
    fun onEnteredGame() {
        attempts = 0
        clickedAtMs = null
        standDownUntilMs = null
    }

    fun reset() {
        attempts = 0
        clickedAtMs = null
        lastClickMs = null
        standDownUntilMs = null
        lastOutcome = null
        lastClickAtMs = null
    }
}
