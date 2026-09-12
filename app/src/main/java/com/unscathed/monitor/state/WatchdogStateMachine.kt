package com.unscathed.monitor.state

import com.unscathed.monitor.analyzer.DisconnectMatch
import com.unscathed.monitor.analyzer.ScreenState

data class Thresholds(
    val suspectStillMs: Long = 10_000,
    val freezeMs: Long = 60_000,
)

sealed interface DisconnectCheck {
    /** OCR did not run on this tick; keep whatever we believed before. */
    data object NotChecked : DisconnectCheck
    data object Clear : DisconnectCheck
    data class Found(val match: DisconnectMatch) : DisconnectCheck
}

/**
 * One tick of evidence. [screen] is the state the classifier has already *confirmed* over several
 * consecutive readings - the state machine never sees a raw guess, which is why it needs no
 * grace timers of its own except the freeze clock.
 */
data class Observation(
    val nowMs: Long,
    /** The confirmed screen, or null when nothing has been confirmed yet this tick. */
    val screen: ScreenState?,
    /** null = no frame analysed this tick. */
    val stillForMs: Long?,
    val disconnect: DisconnectCheck,
)

data class Transition(val from: RobloxState, val to: RobloxState, val atMs: Long)

/**
 * The single place that decides what state Roblox is in. Everything else feeds it
 * [Observation]s and reacts to the [Transition]s it returns.
 *
 * Screen recognition happens upstream, in the classifier and its stabilizer; this class turns a
 * confirmed screen into a watchdog state and owns the only thing a single frame cannot tell you:
 * how long the picture has been standing still. Freeze is judged only while in the game, because
 * a welcome screen or a loading screen is *supposed* to sit motionless.
 *
 * [health] is the state computed from observations alone. [state] is what gets reported, which is
 * [RobloxState.Recovering] while a recovery action is in progress.
 */
class WatchdogStateMachine(var thresholds: Thresholds = Thresholds()) {
    var state: RobloxState = RobloxState.Starting
        private set
    var health: RobloxState = RobloxState.Starting
        private set

    private var recoveringStep: String? = null
    private var lastMatch: DisconnectMatch? = null
    private var lastObservationMs = 0L

    /** True while a disconnect is showing; the analyzer should OCR every tick to watch it. */
    val wantsOcr: Boolean
        get() = health is RobloxState.Disconnected || health == RobloxState.Unknown

    fun onObservation(o: Observation): Transition? {
        lastObservationMs = o.nowMs
        when (val d = o.disconnect) {
            is DisconnectCheck.Found -> lastMatch = d.match
            DisconnectCheck.Clear -> lastMatch = null
            DisconnectCheck.NotChecked -> Unit
        }
        health = evaluate(o)
        return publish(o.nowMs)
    }

    private fun evaluate(o: Observation): RobloxState = when (o.screen) {
        null -> health
        ScreenState.ROBLOX_CLOSED -> RobloxState.RobloxClosed
        ScreenState.ROBLOX_HOME -> RobloxState.RobloxHome
        ScreenState.LOADING -> RobloxState.Loading
        ScreenState.WELCOME -> RobloxState.Welcome
        ScreenState.UNKNOWN -> RobloxState.Unknown
        ScreenState.DISCONNECTED ->
            lastMatch?.let { RobloxState.Disconnected(it) } ?: health
        ScreenState.IN_GAME -> {
            val still = o.stillForMs
            when {
                still == null -> RobloxState.InGame
                still >= thresholds.freezeMs -> RobloxState.Frozen
                still >= thresholds.suspectStillMs -> RobloxState.PossiblyFrozen
                else -> RobloxState.InGame
            }
        }
    }

    fun beginRecovery(step: String): Transition? {
        recoveringStep = step
        return publish(lastObservationMs)
    }

    fun endRecovery(): Transition? {
        recoveringStep = null
        return publish(lastObservationMs)
    }

    fun reset() {
        state = RobloxState.Starting
        health = RobloxState.Starting
        recoveringStep = null
        lastMatch = null
    }

    private fun publish(nowMs: Long): Transition? {
        val next = recoveringStep?.let { RobloxState.Recovering(it) } ?: health
        val prev = state
        state = next
        return if (sameKind(prev, next)) null else Transition(prev, next, nowMs)
    }

    // Disconnected(match) is re-created with fresh OCR text every check; that is not a new event.
    private fun sameKind(a: RobloxState, b: RobloxState): Boolean =
        if (a is RobloxState.Recovering && b is RobloxState.Recovering) a.step == b.step
        else a::class == b::class
}
