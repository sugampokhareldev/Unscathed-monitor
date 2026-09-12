package com.unscathed.monitor.analyzer

/**
 * Which screen the phone is showing. This is the single fact the whole watchdog is built on,
 * so it is deliberately coarse: a handful of outcomes, each backed by several independent
 * indicators rather than by one pixel or one OCR line.
 */
enum class ScreenState(val label: String) {
    /** Roblox is not on screen at all (closed, crashed, or another app is in front). */
    ROBLOX_CLOSED("Roblox not running"),

    /** Roblox is up but joining: the loading / teleport screen. */
    LOADING("Loading"),

    /** The Roblox app's own home screen - running, but not in any experience. */
    ROBLOX_HOME("Roblox home"),

    /** Inside the experience, on its welcome screen with the Play button. */
    WELCOME("Welcome screen"),

    /** Actually playing. */
    IN_GAME("In game"),

    /** The disconnect / error dialog is up. */
    DISCONNECTED("Disconnected"),

    /** Roblox is on screen but nothing recognisable is; never acted on. */
    UNKNOWN("Unknown screen"),
}

/**
 * The words and places that identify each screen for one game. Everything is per-game so a
 * second game can be added without touching the classifier.
 */
data class ScreenMarkers(
    /** Words from the Roblox app's own home screen; several show at once in its nav bar. */
    val lobby: List<String> = listOf(
        "home", "moments", "chat", "more", "charts", "for you", "avatar shop", "discover", "my feed", "friends",
    ),
    /** Words shown while joining an experience. */
    val loading: List<String> = listOf(
        "joining server", "connecting to server", "connecting", "loading", "teleporting", "reconnecting",
        "please wait",
    ),
    /** The label on the experience's own Play button; the anchor for the welcome screen. */
    val welcome: List<String> = listOf("play"),
    /** Extra welcome-screen wording that supports, but never alone proves, the welcome screen. */
    val welcomeSupporting: List<String> = emptyList(),
    /** HUD wording that only exists once you are actually playing. */
    val inGame: List<String> = emptyList(),
    /** How many lobby words have to be on screen at once. */
    val lobbyMinMatches: Int = 3,
    /** Where the Play button sits, as a fraction of the screen. Used to verify before tapping. */
    val playButtonArea: NormBox = NormBox(0.20f, 0.78f, 0.80f, 1.0f),
)

/** Everything gathered about one frame. Any field may be absent when that check did not run. */
data class ScreenEvidence(
    /** Is the game package the app on screen? null = could not be determined. */
    val robloxForeground: Boolean? = null,
    /** Full-frame OCR lines. Empty with [ocrRan] true means the screen really had no text. */
    val lines: List<OcrLine> = emptyList(),
    val ocrRan: Boolean = false,
    /** A disconnect dialog found by [DisconnectTextMatcher], if any. */
    val disconnect: DisconnectMatch? = null,
    /** How long the picture has been unchanged, if freeze tracking ran. */
    val stillForMs: Long? = null,
)

/**
 * One classification of one frame. [confidence] is 0..1; [reasons] says which indicators fired,
 * which is what the debug panel shows and what makes a bad reading diagnosable after the fact.
 */
data class ScreenReading(
    val state: ScreenState,
    val confidence: Double,
    val reasons: List<String> = emptyList(),
    /** The next best state, when something else nearly claimed the frame. */
    val runnerUp: Pair<ScreenState, Double>? = null,
) {
    val confidencePercent: Int get() = (confidence * 100).toInt()
    val summary: String get() = "${state.label} ($confidencePercent%)"
}

/**
 * Turns the evidence from one frame into a [ScreenReading].
 *
 * No single indicator decides anything: each state accumulates weight from independent signals
 * (which app is in front, which words are on screen, where those words are, whether a dialog
 * matched), and the highest total wins. A frame nobody claims strongly enough comes back as
 * [ScreenState.UNKNOWN] rather than as a guess - the caller only acts on confirmed states, and
 * UNKNOWN never becomes an action.
 */
object ScreenClassifier {
    /** Below this the reading is reported as UNKNOWN rather than as its best guess. */
    const val MIN_CONFIDENCE = 0.45

    /** A screen with fewer lines than this is too empty to fall back on. */
    private const val MIN_LINES_FOR_FALLBACK = 3

    fun classify(evidence: ScreenEvidence, markers: ScreenMarkers = ScreenMarkers()): ScreenReading {
        // Roblox not being on screen outranks everything: there is nothing to read.
        if (evidence.robloxForeground == false) {
            return ScreenReading(ScreenState.ROBLOX_CLOSED, 1.0, listOf("Roblox is not the app on screen"))
        }
        if (!evidence.ocrRan) {
            return ScreenReading(ScreenState.UNKNOWN, 0.0, listOf("No screen text read this tick"))
        }

        val scores = mutableMapOf<ScreenState, Double>()
        val reasons = mutableMapOf<ScreenState, MutableList<String>>()
        fun add(state: ScreenState, weight: Double, why: String) {
            scores[state] = (scores[state] ?: 0.0) + weight
            reasons.getOrPut(state) { mutableListOf() }.add(why)
        }

        val text = TextMatch.joinLines(evidence.lines)

        evidence.disconnect?.let {
            add(ScreenState.DISCONNECTED, 0.85, "Disconnect dialog: ${it.reason}")
            if (it.reconnectButton != null) add(ScreenState.DISCONNECTED, 0.15, "Reconnect button on screen")
        }

        TextMatch.firstPresent(markers.loading, text)?.let {
            add(ScreenState.LOADING, 0.7, "Loading wording: \"$it\"")
        }

        val lobbyHits = TextMatch.countPresent(markers.lobby, text)
        if (lobbyHits >= markers.lobbyMinMatches) {
            val extra = 0.1 * (lobbyHits - markers.lobbyMinMatches)
            add(ScreenState.ROBLOX_HOME, 0.55 + extra, "$lobbyHits Roblox home words on screen")
        }

        // The welcome screen is proved by a Play button being where a Play button belongs,
        // not by the word "play" appearing somewhere on screen.
        val play = ScreenClassifier.findPlayButton(evidence.lines, markers)
        if (play != null) {
            add(ScreenState.WELCOME, if (play.inExpectedArea) 0.75 else 0.4, "Play button ${play.where}")
            val supporting = TextMatch.countPresent(markers.welcomeSupporting, text)
            if (supporting > 0) add(ScreenState.WELCOME, 0.1 * supporting, "$supporting welcome-screen words")
        }

        val inGameHits = TextMatch.countPresent(markers.inGame, text)
        if (inGameHits > 0) {
            add(ScreenState.IN_GAME, minOf(0.45 + 0.2 * inGameHits, 0.9), "$inGameHits in-game HUD words")
        }
        // Supporting only: enough to make a recognised game screen certain, never enough on its own.
        if (evidence.robloxForeground == true) add(ScreenState.IN_GAME, 0.25, "Roblox is the app on screen")

        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull()
            ?: return fallback(evidence, "Nothing recognisable on screen")
        val confidence = best.value.coerceAtMost(1.0)
        val runnerUp = ranked.getOrNull(1)?.let { it.key to it.value.coerceAtMost(1.0) }

        if (confidence < MIN_CONFIDENCE) {
            val why = "Best guess ${best.key.label} was only ${(confidence * 100).toInt()}% sure"
            return fallback(evidence, why, reasons[best.key].orEmpty(), confidence, runnerUp)
        }
        return ScreenReading(best.key, confidence, reasons[best.key].orEmpty(), runnerUp)
    }

    /**
     * Nothing scored highly enough. Being inside Roblox on a screen full of text that matches none
     * of the known non-game screens is, in practice, being in the game - the HUD is mostly art, so
     * plenty of real game screens carry no marker words. That reading is deliberately low
     * confidence, and a screen with almost no text on it stays UNKNOWN rather than being guessed.
     */
    private fun fallback(
        evidence: ScreenEvidence,
        why: String,
        extraReasons: List<String> = emptyList(),
        confidence: Double = 0.0,
        runnerUp: Pair<ScreenState, Double>? = null,
    ): ScreenReading {
        if (evidence.robloxForeground == true && evidence.lines.size >= MIN_LINES_FOR_FALLBACK) {
            return ScreenReading(
                ScreenState.IN_GAME,
                0.5,
                listOf("Roblox is in front and no other screen matched", why) + extraReasons,
                runnerUp,
            )
        }
        return ScreenReading(ScreenState.UNKNOWN, confidence, listOf(why) + extraReasons, runnerUp)
    }

    /** Where the Play button is, if an OCR line looks like one. */
    data class PlayButton(val box: NormBox, val inExpectedArea: Boolean, val text: String) {
        val where: String
            get() = "\"$text\" at ${(box.centerX * 100).toInt()},${(box.centerY * 100).toInt()}%" +
                if (inExpectedArea) "" else " (outside the expected area)"
    }

    /**
     * Finds the Play button. A line counts only if it *is* the word, not a line containing it,
     * which keeps "played", "player" and chat messages out.
     */
    fun findPlayButton(lines: List<OcrLine>, markers: ScreenMarkers = ScreenMarkers()): PlayButton? {
        val wanted = markers.welcome.map(TextMatch::normalize).filter { it.isNotEmpty() }
        val candidates = lines.mapNotNull { line ->
            val norm = TextMatch.normalize(line.text)
            if (norm.isEmpty() || wanted.none { it == norm }) return@mapNotNull null
            val box = line.box ?: return@mapNotNull null
            PlayButton(box, markers.playButtonArea.contains(box.centerX, box.centerY), line.text.trim())
        }
        // A button inside the expected area beats one somewhere else on screen.
        return candidates.firstOrNull { it.inExpectedArea } ?: candidates.firstOrNull()
    }
}

/**
 * Holds a state back until the same reading has come in several times in a row.
 *
 * One bad OCR pass - a popup caught mid-animation, a frame caught during a fade - should never
 * move the watchdog. Each state carries its own streak: ones that trigger an action need more
 * agreement, and a disconnect (which has dialog text of its own to go on) needs less so recovery
 * is not slow.
 */
class ScreenStabilizer(
    private val defaultStreak: Int = 3,
    private val perState: Map<ScreenState, Int> = mapOf(
        ScreenState.DISCONNECTED to 2,
        ScreenState.ROBLOX_CLOSED to 2,
        ScreenState.WELCOME to 3,
        ScreenState.UNKNOWN to 4,
    ),
) {
    /** The state that has actually been confirmed. Starts unknown. */
    var confirmed: ScreenState = ScreenState.UNKNOWN
        private set
    var confirmedAtMs: Long = 0L
        private set
    var lastReading: ScreenReading? = null
        private set

    private var candidate: ScreenState? = null
    private var streak = 0

    /** The state waiting for more agreement, if any. */
    val pending: ScreenState? get() = candidate?.takeIf { it != confirmed }

    /** How many more agreeing readings the pending candidate still needs. 0 = nothing pending. */
    val pendingRemaining: Int
        get() = pending?.let { (needed(it) - streak).coerceAtLeast(0) } ?: 0

    private fun needed(state: ScreenState) = perState[state] ?: defaultStreak

    /** Feeds one reading in; returns the new state only on the tick it becomes confirmed. */
    fun offer(reading: ScreenReading, nowMs: Long): ScreenState? {
        lastReading = reading
        if (reading.state == candidate) {
            streak++
        } else {
            candidate = reading.state
            streak = 1
        }
        if (reading.state == confirmed) return null
        if (streak < needed(reading.state)) return null
        confirmed = reading.state
        confirmedAtMs = nowMs
        return reading.state
    }

    fun reset() {
        confirmed = ScreenState.UNKNOWN
        confirmedAtMs = 0
        candidate = null
        streak = 0
        lastReading = null
    }
}
