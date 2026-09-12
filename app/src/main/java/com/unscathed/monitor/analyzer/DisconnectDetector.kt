package com.unscathed.monitor.analyzer

/** Bounding box in normalized full-frame coordinates (0..1), independent of capture resolution. */
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun contains(box: NormBox): Boolean = contains(box.centerX, box.centerY)
}

data class OcrLine(val text: String, val box: NormBox? = null)

data class DisconnectMatch(
    val reason: String,
    val errorCode: Int?,
    val reconnectButton: NormBox?,
    val leaveButton: NormBox?,
    val text: String,
) {
    val description: String
        get() = errorCode?.let { code ->
            RobloxErrorCodes.describe(code)?.let { "Error $code: $it" } ?: "Error $code"
        } ?: reason
}

/**
 * Decides whether OCR output looks like Roblox's disconnect / error dialog.
 *
 * It matches on text instead of pixel templates, so a restyled dialog still matches as long
 * as the wording stays roughly the same. OCR only looks at the center of the screen (see
 * [OcrEngine]), which keeps chat messages like "xyz disconnected" from triggering it.
 */
object DisconnectTextMatcher {
    private val strongPhrases = listOf(
        "disconnected",
        "lost connection",
        "error code",
        "you were kicked",
        "you have been kicked",
        "kicked from",
        "server shutdown",
        "server has shut down",
        "failed to connect",
        "connection attempt failed",
        "check your internet connection",
        "same account launched",
    )

    private val reconnectLabels = setOf("reconnect", "rejoin", "retry")
    private val leaveLabels = setOf("leave", "exit")

    private val errorCodeRegex = Regex("""error\s*code\s*[:;.]?\s*\(?\s*(\d{3,4})""")
    private val whitespace = Regex("\\s+")

    /** @param extraPhrases game-specific wording from the active [com.unscathed.monitor.games.GameProfile]. */
    fun match(lines: List<OcrLine>, extraPhrases: List<String> = emptyList()): DisconnectMatch? {
        if (lines.isEmpty()) return null
        val normalized = lines.map { it to normalize(it.text) }
        val full = normalized.joinToString(" ") { it.second }

        val reconnect = normalized.firstOrNull { (_, t) -> t in reconnectLabels }?.first
        val leave = normalized.firstOrNull { (_, t) -> t in leaveLabels }?.first
        val phrase = strongPhrases.firstOrNull { it in full }
            ?: extraPhrases.map(::normalize).firstOrNull { it.isNotEmpty() && it in full }
        val code = errorCodeRegex.find(full)?.groupValues?.get(1)?.toIntOrNull()
        val buttonPair = reconnect != null && leave != null

        if (phrase == null && code == null && !buttonPair) return null

        val reason = when {
            code != null -> "Error Code $code"
            phrase != null -> phrase.replaceFirstChar { it.uppercase() }
            else -> "Reconnect dialog"
        }
        return DisconnectMatch(
            reason = reason,
            errorCode = code,
            reconnectButton = reconnect?.box,
            leaveButton = leave?.box,
            text = lines.joinToString(" | ") { it.text }.take(500),
        )
    }

    private fun normalize(s: String) = s.lowercase().replace(whitespace, " ").trim().trimEnd('.', '!')
}

object RobloxErrorCodes {
    private val descriptions = mapOf(
        266 to "Connection timed out",
        267 to "Kicked by the experience",
        268 to "Kicked for unexpected client behavior",
        273 to "Same account joined from another device",
        277 to "Lost connection to the game server",
        279 to "Failed to connect to the game",
        524 to "Not authorized to join this server",
        529 to "Roblox service error",
        610 to "Could not join the game instance",
    )

    fun describe(code: Int): String? = descriptions[code]

    /** Parses the user's "never auto-reconnect" list, e.g. "268, 273". */
    fun parseCodeList(raw: String): Set<Int> =
        raw.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.toSet()
}
