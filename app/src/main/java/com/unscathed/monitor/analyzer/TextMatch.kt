package com.unscathed.monitor.analyzer

import kotlin.math.abs
import kotlin.math.min

/**
 * Fuzzy whole-word matching for OCR output.
 *
 * OCR of a game HUD is never clean: letters get swapped, spaces disappear inside stylised fonts,
 * and punctuation comes and goes. Everything here works on a normalized form (lowercase, letters
 * and digits only) and allows a few edit-distance errors that grow with the length of the phrase,
 * so short words stay strict and long phrases stay findable.
 */
object TextMatch {
    private val apostrophes = Regex("[’'`´]")
    private val nonAlnum = Regex("[^a-z0-9]+")

    fun normalize(s: String): String =
        s.lowercase().replace(apostrophes, "").replace(nonAlnum, " ").trim()

    /** One normalized string for a whole screen, so phrases split across OCR lines still match. */
    fun joinLines(lines: List<OcrLine>): String = lines.joinToString(" ") { normalize(it.text) }

    /**
     * True if normalized [phrase] appears in normalized [text] as whole words. Whole words keep
     * "aqua" from matching "aquatic" and "play" from matching "player".
     */
    fun appearsIn(phrase: String, text: String): Boolean {
        if (phrase.isEmpty() || text.isEmpty()) return false
        val words = phrase.split(' ').filter { it.isNotEmpty() }
        val textWords = text.split(' ').filter { it.isNotEmpty() }
        val k = words.size
        val allowed = phrase.length / 8
        if (textWords.size >= k) {
            for (i in 0..textWords.size - k) {
                val window = textWords.subList(i, i + k).joinToString(" ")
                if (window == phrase) return true
                if (allowed > 0 && abs(window.length - phrase.length) <= allowed &&
                    levenshtein(window, phrase) <= allowed
                ) {
                    return true
                }
            }
        }
        // OCR drops the space in stylised fonts: "DarkArts".
        if (k > 1) {
            val joined = phrase.replace(" ", "")
            if (textWords.any {
                    it == joined ||
                        (allowed > 0 && abs(it.length - joined.length) <= allowed && levenshtein(it, joined) <= allowed)
                }
            ) {
                return true
            }
        }
        return false
    }

    /** How many of [phrases] appear in [text]. */
    fun countPresent(phrases: List<String>, text: String): Int =
        phrases.map(::normalize).count { it.isNotEmpty() && appearsIn(it, text) }

    fun anyPresent(phrases: List<String>, text: String): Boolean =
        phrases.map(::normalize).any { it.isNotEmpty() && appearsIn(it, text) }

    fun firstPresent(phrases: List<String>, text: String): String? =
        phrases.firstOrNull { normalize(it).isNotEmpty() && appearsIn(normalize(it), text) }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
