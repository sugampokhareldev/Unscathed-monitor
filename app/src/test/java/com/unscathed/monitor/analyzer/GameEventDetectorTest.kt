package com.unscathed.monitor.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEventDetectorTest {
    private val rule = GameEventRule.DARK_ARTS_MERCHANT

    private fun lines(vararg text: String) = text.map { OcrLine(it) }

    @Test
    fun theExactMessageMatches() {
        val hit = GameEventDetector.detectOne(lines("The Dark Arts merchant has appeared!"), rule)
        assertNotNull(hit)
        assertEquals(1.0, hit!!.confidence, 0.001)
        assertEquals("has appeared", hit.matchedVerb)
    }

    /** OCR drops words and punctuation; all of these are the same announcement. */
    @Test
    fun ocrVariationsStillMatch() {
        listOf(
            "Dark Arts merchant has appeared",
            "The Dark Arts merchant appeared",
            "Dark Arts merchant appeared!",
            "the dark arts merchant has arrived",
            "THE DARK ARTS MERCHANT HAS APPEARED",
        ).forEach { text ->
            assertNotNull("should have matched: $text", GameEventDetector.detectOne(lines(text), rule))
        }
    }

    /** A banner regularly lands as two OCR lines, and neither half matches on its own. */
    @Test
    fun aMessageSplitAcrossTwoLinesMatches() {
        assertNotNull(GameEventDetector.detectOne(lines("The Dark Arts merchant", "has appeared!"), rule))
    }

    @Test
    fun singleLetterOcrErrorsAreTolerated() {
        assertNotNull(GameEventDetector.detectOne(lines("The Dark Arls merchant has appeared!"), rule))
    }

    /** The strictness that keeps random HUD text out. */
    @Test
    fun ordinaryScreenTextNeverMatches() {
        listOf(
            "Dark Souls",
            "Merchant",
            "The merchant has appeared",
            "Arts and Crafts shop appeared",
            "Roll  Luck  Inventory  Index",
            "1 in 20,000  Iron Jaw",
            "xX_DarkLord_Xx has appeared",
        ).forEach { text ->
            assertNull("should NOT have matched: $text", GameEventDetector.detectOne(lines(text), rule))
        }
    }

    /** The subject alone is someone typing it in chat, not the game announcing it. */
    @Test
    fun theSubjectWithoutAVerbIsNotAnEvent() {
        assertNull(GameEventDetector.detectOne(lines("where is the dark arts merchant"), rule))
    }

    @Test
    fun onlyTextInsideTheMarkedAreaCounts() {
        val cropped = rule.copy(region = NormBox(0f, 0.0f, 1f, 0.25f))
        val inArea = listOf(OcrLine("The Dark Arts merchant has appeared!", NormBox(0.2f, 0.08f, 0.8f, 0.12f)))
        val belowArea = listOf(OcrLine("The Dark Arts merchant has appeared!", NormBox(0.2f, 0.80f, 0.8f, 0.85f)))
        assertNotNull(GameEventDetector.detectOne(inArea, cropped))
        assertNull(GameEventDetector.detectOne(belowArea, cropped))
    }

    @Test
    fun theQuotedTextIsTheMessageNotTheWholeScreen() {
        val hit = GameEventDetector.detectOne(
            lines("Roll", "Luck x2", "The Dark Arts merchant has appeared!", "Inventory"),
            rule,
        )
        assertNotNull(hit)
        assertTrue(hit!!.text.contains("Dark Arts merchant"))
        assertTrue(!hit.text.contains("Inventory"))
    }

    @Test
    fun severalRulesAreCheckedAtOnce() {
        val other = GameEventRule("boss", "Boss", "ancient guardian", listOf("has spawned"))
        val hits = GameEventDetector.detect(
            lines("The Dark Arts merchant has appeared!", "The ancient guardian has spawned"),
            listOf(rule, other),
        )
        assertEquals(2, hits.size)
    }

    @Test
    fun anEmptyScreenMatchesNothing() {
        assertTrue(GameEventDetector.detect(emptyList(), listOf(rule)).isEmpty())
    }
}
