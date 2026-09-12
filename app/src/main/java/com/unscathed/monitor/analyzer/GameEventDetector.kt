package com.unscathed.monitor.analyzer

/**
 * An in-game announcement worth alerting on, described entirely by text so a new one can be
 * added without code.
 *
 * [subject] is the part that must be there - it carries the identity of the event and is long
 * enough that no ordinary HUD text matches it by accident. [verbs] are the ways the game might
 * word the rest of the sentence, and one of them must also be present unless [requireVerb] is
 * off; that is what separates "The Dark Arts merchant has appeared!" from a player typing the
 * merchant's name in chat.
 */
data class GameEventRule(
    val id: String,
    val title: String,
    val subject: String,
    val verbs: List<String> = emptyList(),
    val requireVerb: Boolean = true,
    /** Only text inside this part of the screen counts. Full screen by default. */
    val region: NormBox = NormBox(0f, 0f, 1f, 1f),
) {
    companion object {
        /**
         * "The Dark Arts merchant has appeared!" - matched loosely enough to survive OCR
         * ("Dark Arts merchant appeared", a dropped "The", a missing "!") but strictly enough
         * that the three-word subject has to be on screen.
         */
        val DARK_ARTS_MERCHANT = GameEventRule(
            id = "dark_arts_merchant",
            title = "Dark Arts Merchant",
            subject = "dark arts merchant",
            verbs = listOf(
                "has appeared", "have appeared", "appeared", "has arrived", "arrived",
                "is here", "has spawned", "spawned",
            ),
        )
    }
}

/** One confirmed-looking sighting of a [GameEventRule] in the OCR output of one frame. */
data class GameEventSighting(
    val rule: GameEventRule,
    /** 0..1: how much of the expected wording was actually read. */
    val confidence: Double,
    /** The screen text the match came from, for the alert and the log. */
    val text: String,
    val matchedVerb: String?,
)

/**
 * Looks for [GameEventRule]s in OCR output.
 *
 * Matching runs on the lines joined together as well as on each line, because a long banner is
 * regularly split across two OCR lines - "The Dark Arts merchant" / "has appeared!" - and neither
 * half matches on its own.
 */
object GameEventDetector {
    /** Sightings need at least this much of the wording before they are even reported. */
    const val MIN_CONFIDENCE = 0.7

    fun detect(lines: List<OcrLine>, rules: List<GameEventRule>): List<GameEventSighting> {
        if (lines.isEmpty() || rules.isEmpty()) return emptyList()
        return rules.mapNotNull { rule -> detectOne(lines, rule) }
    }

    fun detectOne(lines: List<OcrLine>, rule: GameEventRule): GameEventSighting? {
        val inRegion = lines.filter { line -> line.box?.let { rule.region.contains(it) } ?: true }
        if (inRegion.isEmpty()) return null

        val subject = TextMatch.normalize(rule.subject)
        if (subject.isEmpty()) return null
        val joined = TextMatch.joinLines(inRegion)
        if (!TextMatch.appearsIn(subject, joined)) return null

        val verb = TextMatch.firstPresent(rule.verbs, joined)
        if (verb == null && rule.requireVerb) return null

        val confidence = if (verb != null) 1.0 else 0.7
        if (confidence < MIN_CONFIDENCE) return null

        // Quote the lines that carry the message, not the whole screen.
        val relevant = inRegion.filter { line ->
            val t = TextMatch.normalize(line.text)
            TextMatch.appearsIn(subject, t) || rule.verbs.any { TextMatch.appearsIn(TextMatch.normalize(it), t) }
        }
        val text = (relevant.ifEmpty { inRegion }).joinToString(" ") { it.text.trim() }.take(300)
        return GameEventSighting(rule, confidence, text, verb)
    }
}
