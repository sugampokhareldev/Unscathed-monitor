package com.unscathed.monitor.recovery

import com.unscathed.monitor.state.RobloxState
import kotlin.math.min

enum class RecoveryAction { TAP_RECONNECT, RELAUNCH, SKIP, GIVE_UP }

data class RecoveryPolicy(
    val tapReconnect: Boolean = true,
    val rejoin: Boolean = true,
    val maxReconnects: Int = 3,
    /** Quick rejoins before backoff starts (or before giving up when [persistent] is off). */
    val maxRelaunches: Int = 2,
    /** Keep rejoining forever, with growing gaps, instead of giving up. */
    val persistent: Boolean = false,
    val recoverFrozen: Boolean = false,
    /**
     * True when something is covering the screen and taps cannot reach the game. Tapping
     * Reconnect is then pointless, but relaunching is not: it is an intent, not a touch.
     */
    val tapsBlocked: Boolean = false,
    val noRecoverCodes: Set<Int> = setOf(268, 273),
    val giveUpCooldownMs: Long = 30 * 60_000L,
    /** Roblox must stay healthy this long before the attempt counters reset. */
    val stableResetMs: Long = 5 * 60_000L,
    val retryBackoffBaseMs: Long = 30_000L,
    val retryBackoffMaxMs: Long = 5 * 60_000L,
) {
    /** null = unlimited. */
    val maxAttempts: Int?
        get() = if (persistent && rejoin) null
        else (if (tapReconnect) maxReconnects else 0) + (if (rejoin) maxRelaunches else 0)
}

data class RecoveryStep(
    val action: RecoveryAction,
    val attempt: Int,
    val maxAttempts: Int?,
    val reason: String,
) {
    val progress: String get() = "$attempt/${maxAttempts ?: "∞"}"
}

/**
 * Decides the next recovery action:
 * tap Reconnect (up to N) -> rejoin the game (M quick tries) -> then either give up and cool
 * down, or, with [RecoveryPolicy.persistent], keep rejoining with exponential backoff.
 * While the phone is offline it waits instead of burning attempts.
 * It has no Android dependencies; the service carries out whatever it returns.
 */
class RecoveryPlanner {
    private data class Counters(
        val reconnects: Int = 0,
        val relaunches: Int = 0,
        val gaveUpAtMs: Long? = null,
        val skipped: Boolean = false,
        val notBeforeMs: Long = 0,
    )

    private var counters = Counters()
    private var healthySinceMs: Long? = null

    /** True once we've acted on (or deliberately skipped) the current problem. */
    val inEpisode: Boolean get() = counters != Counters()

    val attemptsUsed: Int get() = counters.reconnects + counters.relaunches

    fun onHealthy(nowMs: Long, policy: RecoveryPolicy) {
        val since = healthySinceMs ?: nowMs.also { healthySinceMs = it }
        if (nowMs - since >= policy.stableResetMs) counters = Counters()
    }

    fun onUnhealthy() {
        healthySinceMs = null
    }

    /** What would happen next, without consuming an attempt. */
    fun preview(state: RobloxState, nowMs: Long, policy: RecoveryPolicy, online: Boolean = true): RecoveryStep? =
        decide(state, nowMs, policy, online, counters).first

    fun next(state: RobloxState, nowMs: Long, policy: RecoveryPolicy, online: Boolean = true): RecoveryStep? {
        val (step, updated) = decide(state, nowMs, policy, online, counters)
        counters = updated
        return step
    }

    /** Call when a step's action (and its wait) is over; schedules the backoff gap before the next one. */
    fun onStepFinished(nowMs: Long, policy: RecoveryPolicy) {
        val beyondQuick = counters.relaunches - policy.maxRelaunches
        val delay = if (beyondQuick <= 0) {
            0L
        } else {
            val exp = policy.retryBackoffBaseMs shl min(beyondQuick - 1, 16)
            min(exp, policy.retryBackoffMaxMs)
        }
        counters = counters.copy(notBeforeMs = nowMs + delay)
    }

    /** Milliseconds until the next attempt is allowed (0 = now). */
    fun waitRemainingMs(nowMs: Long): Long = (counters.notBeforeMs - nowMs).coerceAtLeast(0)

    private fun decide(
        state: RobloxState,
        nowMs: Long,
        p: RecoveryPolicy,
        online: Boolean,
        start: Counters,
    ): Pair<RecoveryStep?, Counters> {
        var c = start
        c.gaveUpAtMs?.let { gaveUpAt ->
            if (nowMs - gaveUpAt < p.giveUpCooldownMs) return null to c
            c = Counters()
        }
        if (c.skipped || (!p.tapReconnect && !p.rejoin)) return null to c
        // Reconnecting without internet can't work; wait rather than use up attempts.
        if (!online || nowMs < c.notBeforeMs) return null to c

        val attempt = c.reconnects + c.relaunches + 1
        val max = p.maxAttempts

        return when (state) {
            is RobloxState.Disconnected -> {
                val code = state.match.errorCode
                when {
                    code != null && code in p.noRecoverCodes -> RecoveryStep(
                        RecoveryAction.SKIP, 0, max,
                        "${state.match.description}. This code is on the no-auto-reconnect list.",
                    ) to c.copy(skipped = true)

                    p.tapReconnect && !p.tapsBlocked && state.match.reconnectButton != null &&
                        c.reconnects < p.maxReconnects ->
                        RecoveryStep(RecoveryAction.TAP_RECONNECT, attempt, max, "Tap Reconnect") to
                            c.copy(reconnects = c.reconnects + 1)

                    else -> rejoinOrGiveUp(c, p, attempt, max, nowMs)
                }
            }
            // Roblox closed, or running but back on its own home screen: both need a rejoin.
            RobloxState.RobloxClosed, RobloxState.RobloxHome -> rejoinOrGiveUp(c, p, attempt, max, nowMs)
            RobloxState.Frozen -> if (p.recoverFrozen) rejoinOrGiveUp(c, p, attempt, max, nowMs) else null to c
            else -> null to c
        }
    }

    private fun rejoinOrGiveUp(
        c: Counters,
        p: RecoveryPolicy,
        attempt: Int,
        max: Int?,
        nowMs: Long,
    ): Pair<RecoveryStep, Counters> {
        val quickLeft = c.relaunches < p.maxRelaunches
        return if (p.rejoin && (quickLeft || p.persistent)) {
            val reason = if (quickLeft) "Rejoin game" else "Rejoin game (retrying with backoff)"
            RecoveryStep(RecoveryAction.RELAUNCH, attempt, max, reason) to c.copy(relaunches = c.relaunches + 1)
        } else {
            val used = attempt - 1
            val reason = if (p.rejoin) "All $used recovery attempts used" else "Reconnect didn't work and rejoin is off"
            RecoveryStep(RecoveryAction.GIVE_UP, used, max, reason) to c.copy(gaveUpAtMs = nowMs)
        }
    }
}
