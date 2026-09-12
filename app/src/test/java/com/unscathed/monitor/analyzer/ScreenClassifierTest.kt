package com.unscathed.monitor.analyzer

import com.unscathed.monitor.games.GameCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenClassifierTest {
    private val markers = GameCatalog.UNSCATHED.screenMarkers

    /** Places a line at a point on the screen, as OCR would. */
    private fun at(text: String, x: Float, y: Float) =
        OcrLine(text, NormBox(x - 0.05f, y - 0.02f, x + 0.05f, y + 0.02f))

    private fun evidence(vararg lines: OcrLine, foreground: Boolean? = true) =
        ScreenEvidence(robloxForeground = foreground, lines = lines.toList(), ocrRan = true)

    @Test
    fun robloxNotOnScreenOutranksEverything() {
        val reading = ScreenClassifier.classify(
            ScreenEvidence(robloxForeground = false, lines = listOf(OcrLine("PLAY")), ocrRan = true),
            markers,
        )
        assertEquals(ScreenState.ROBLOX_CLOSED, reading.state)
        assertEquals(1.0, reading.confidence, 0.001)
    }

    /** The welcome screen from the user's phone: one big PLAY button near the bottom. */
    @Test
    fun theWelcomeScreenIsFoundByItsPlayButton() {
        val reading = ScreenClassifier.classify(
            evidence(
                at("Here", 0.03f, 0.06f),
                at("Friends", 0.09f, 0.06f),
                at("Global", 0.15f, 0.06f),
                at("PLAY", 0.50f, 0.93f),
            ),
            markers,
        )
        assertEquals(ScreenState.WELCOME, reading.state)
        assertTrue("confidence was ${reading.confidence}", reading.confidence >= 0.75)
    }

    /** "play" anywhere on screen is not a Play button; chat must not trigger Auto Play. */
    @Test
    fun theWordPlayInChatIsNotAWelcomeScreen() {
        val reading = ScreenClassifier.classify(
            evidence(
                at("lets play later", 0.15f, 0.20f),
                at("Roll", 0.5f, 0.8f),
                at("Inventory", 0.8f, 0.1f),
            ),
            markers,
        )
        assertEquals(ScreenState.IN_GAME, reading.state)
        assertNull(ScreenClassifier.findPlayButton(listOf(at("lets play later", 0.15f, 0.2f)), markers))
    }

    @Test
    fun aPlayButtonOutsideItsUsualPlaceCountsForLess() {
        val low = ScreenClassifier.classify(evidence(at("PLAY", 0.5f, 0.30f)), markers)
        val normal = ScreenClassifier.classify(evidence(at("PLAY", 0.5f, 0.93f)), markers)
        assertTrue(normal.confidence > low.confidence)
        val found = ScreenClassifier.findPlayButton(listOf(at("PLAY", 0.5f, 0.30f)), markers)
        assertNotNull(found)
        assertTrue(!found!!.inExpectedArea)
    }

    @Test
    fun theRobloxHomeScreenNeedsSeveralOfItsOwnWords() {
        val home = evidence(
            OcrLine("Q Search Home"), OcrLine("Moments"), OcrLine("Chat"), OcrLine("More"),
            OcrLine("For you Charts"), OcrLine("Steal An Egg"),
        )
        assertEquals(ScreenState.ROBLOX_HOME, ScreenClassifier.classify(home, markers).state)

        // One stray "Home" is a button in the game, not the Roblox home screen.
        val inGame = evidence(OcrLine("Home"), OcrLine("Roll"), OcrLine("Luck"))
        assertEquals(ScreenState.IN_GAME, ScreenClassifier.classify(inGame, markers).state)
    }

    @Test
    fun theJoiningScreenIsLoading() {
        val reading = ScreenClassifier.classify(
            evidence(OcrLine("Unscathed RNG"), OcrLine("Joining server"), OcrLine("1 in 175,000")),
            markers,
        )
        assertEquals(ScreenState.LOADING, reading.state)
    }

    @Test
    fun aDisconnectDialogWins() {
        val match = DisconnectMatch("Disconnected", 277, NormBox(0.4f, 0.6f, 0.6f, 0.65f), null, "Disconnected")
        val reading = ScreenClassifier.classify(
            ScreenEvidence(
                robloxForeground = true,
                lines = listOf(OcrLine("Disconnected"), OcrLine("Reconnect"), OcrLine("Leave")),
                ocrRan = true,
                disconnect = match,
            ),
            markers,
        )
        assertEquals(ScreenState.DISCONNECTED, reading.state)
        assertEquals(1.0, reading.confidence, 0.001)
    }

    /** A screen nobody recognises must come back as UNKNOWN, never as a guess. */
    @Test
    fun anUnrecognisedScreenIsUnknownNotAGuess() {
        val reading = ScreenClassifier.classify(
            ScreenEvidence(robloxForeground = null, lines = listOf(OcrLine("???")), ocrRan = true),
            markers,
        )
        assertEquals(ScreenState.UNKNOWN, reading.state)
        assertTrue(reading.reasons.isNotEmpty())
    }

    @Test
    fun skippingOcrNeverProducesAState() {
        val reading = ScreenClassifier.classify(ScreenEvidence(robloxForeground = true, ocrRan = false), markers)
        assertEquals(ScreenState.UNKNOWN, reading.state)
        assertEquals(0.0, reading.confidence, 0.001)
    }

    /**
     * Most of the Unscathed HUD is art, so a real game screen often carries none of the marker
     * words. Being in Roblox with a screenful of text that matches no other screen is the game.
     */
    @Test
    fun aGameScreenWithNoMarkerWordsStillReadsAsInGame() {
        val reading = ScreenClassifier.classify(
            evidence(at("x2", 0.1f, 0.1f), at("42", 0.5f, 0.2f), at("Bob", 0.2f, 0.5f), at("99", 0.9f, 0.9f)),
            markers,
        )
        assertEquals(ScreenState.IN_GAME, reading.state)
        // Deliberately low: it is a fallback, not a recognition.
        assertEquals(0.5, reading.confidence, 0.001)
        assertTrue(reading.reasons.any { it.contains("no other screen matched") })
    }

    /** An almost-empty screen is not guessed at, even inside Roblox. */
    @Test
    fun anAlmostEmptyScreenStaysUnknown() {
        val reading = ScreenClassifier.classify(evidence(at("?", 0.5f, 0.5f)), markers)
        assertEquals(ScreenState.UNKNOWN, reading.state)
    }

    /** The fallback must never outrank a screen that was actually recognised. */
    @Test
    fun theFallbackNeverBeatsARecognisedScreen() {
        val home = evidence(
            OcrLine("Home"), OcrLine("Moments"), OcrLine("Chat"), OcrLine("Charts"), OcrLine("Friends"),
        )
        assertEquals(ScreenState.ROBLOX_HOME, ScreenClassifier.classify(home, markers).state)

        val welcome = evidence(
            at("Here", 0.03f, 0.06f), at("Friends", 0.09f, 0.06f), at("PLAY", 0.50f, 0.93f), OcrLine("Say hi"),
        )
        assertEquals(ScreenState.WELCOME, ScreenClassifier.classify(welcome, markers).state)
    }

    @Test
    fun everyReadingExplainsItself() {
        val reading = ScreenClassifier.classify(evidence(at("PLAY", 0.5f, 0.93f)), markers)
        assertTrue(reading.reasons.any { it.contains("Play button") })
    }
}
