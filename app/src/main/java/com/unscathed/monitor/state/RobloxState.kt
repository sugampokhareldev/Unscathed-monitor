package com.unscathed.monitor.state

import com.unscathed.monitor.analyzer.DisconnectMatch
import com.unscathed.monitor.analyzer.ScreenState

/**
 * What the watchdog believes is happening. This mirrors [ScreenState] one-for-one for the screens
 * it can recognise, and adds the states that only exist over time - frozen, recovering, starting.
 */
sealed interface RobloxState {
    val label: String

    /** True when something needs fixing. Drives alerts and recovery. */
    val isProblem: Boolean get() = false

    data object Starting : RobloxState {
        override val label = "Starting"
    }

    /** In the game and playing: the only fully healthy state. */
    data object InGame : RobloxState {
        override val label = "In game"
    }

    /** Joining an experience: transient, so nothing is wrong. */
    data object Loading : RobloxState {
        override val label = "Loading"
    }

    /** On the experience's welcome screen. A problem only in that Play still has to be pressed. */
    data object Welcome : RobloxState {
        override val label = "Welcome screen"
    }

    /** The screen has been still for a while but not long enough to call it frozen. */
    data object PossiblyFrozen : RobloxState {
        override val label = "Screen still"
    }

    data object Frozen : RobloxState {
        override val label = "Frozen"
        override val isProblem = true
    }

    data class Disconnected(val match: DisconnectMatch) : RobloxState {
        override val label get() = "Disconnected" + (match.errorCode?.let { " ($it)" } ?: "")
        override val isProblem = true
    }

    data object RobloxClosed : RobloxState {
        override val label = "Roblox closed"
        override val isProblem = true
    }

    /** Roblox is running but sitting on its own home screen instead of in the experience. */
    data object RobloxHome : RobloxState {
        override val label = "Left the game"
        override val isProblem = true
    }

    /** Roblox is on screen but nothing recognisable is. Reported, never acted on. */
    data object Unknown : RobloxState {
        override val label = "Unrecognised screen"
    }

    data class Recovering(val step: String) : RobloxState {
        override val label get() = "Recovering: $step"
    }
}
