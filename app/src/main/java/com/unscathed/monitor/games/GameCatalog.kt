package com.unscathed.monitor.games

import com.unscathed.monitor.analyzer.GameEventRule
import com.unscathed.monitor.analyzer.NormBox
import com.unscathed.monitor.analyzer.ScreenMarkers

enum class GameCategory(val label: String, val icon: String) {
    ROBLOX("Roblox", "🎮"),
}

/** Watchdog capabilities that can be switched on or off per game. */
enum class Feature(val label: String, val description: String) {
    DISCONNECT_DETECTION("Disconnect detection", "Read the Disconnected / Error Code dialog"),
    FREEZE_DETECTION("Freeze detection", "Alert when the screen stops changing in-game"),
    CRASH_DETECTION("Crash detection", "Alert when Roblox leaves the screen"),
    AUTO_RECONNECT("Tap Reconnect", "Press the dialog's Reconnect button"),
    AUTO_REJOIN("Rejoin game", "Relaunch Roblox straight into this game"),
    KEEP_RETRYING("Never give up", "After the quick attempts, keep rejoining with backoff until back in"),
    REJOIN_WHEN_FROZEN("Rejoin when frozen", "Treat a frozen screen like a crash (off for still AFK spots)"),
    AUTO_PLAY("Auto Play", "Press Play for you on the game's welcome screen"),
    EVENT_ALERTS("Event alerts", "Watch for in-game announcements like the Dark Arts Merchant"),
}

data class GameProfile(
    val id: String,
    val name: String,
    val category: GameCategory,
    val packageName: String,
    val tagline: String,
    val supportedFeatures: Set<Feature>,
    val defaultFeatures: Set<Feature>,
    /** Game-specific kick / shutdown wording that should count as a disconnect. */
    val extraDisconnectPhrases: List<String> = emptyList(),
    /** Everything the screen classifier needs to tell this game's screens apart. */
    val screenMarkers: ScreenMarkers = ScreenMarkers(),
    /** In-game announcements worth alerting on. */
    val events: List<GameEventRule> = emptyList(),
    val defaultNoRecoverCodes: Set<Int> = setOf(268, 273),
    val defaultIgnoreTopPercent: Int = 10,
    val defaultIgnoreBottomPercent: Int = 10,
    /** Offered as a one-tap fill in the Games tab; never applied without the user choosing it. */
    val suggestedPlaceId: String? = null,
    val suggestedPlaceName: String? = null,
)

const val ROBLOX_PACKAGE = "com.roblox.client"

object GameCatalog {
    val UNSCATHED = GameProfile(
        id = "roblox.unscathed",
        name = "Unscathed",
        category = GameCategory.ROBLOX,
        packageName = ROBLOX_PACKAGE,
        tagline = "Roblox RNG battler",
        supportedFeatures = Feature.entries.toSet(),
        defaultFeatures = setOf(
            Feature.DISCONNECT_DETECTION,
            Feature.FREEZE_DETECTION,
            Feature.CRASH_DETECTION,
            Feature.AUTO_RECONNECT,
            Feature.AUTO_REJOIN,
            Feature.KEEP_RETRYING,
            Feature.AUTO_PLAY,
            Feature.EVENT_ALERTS,
        ),
        screenMarkers = ScreenMarkers(
            // The welcome screen is a full-bleed banner with one big PLAY button near the bottom.
            welcome = listOf("play"),
            welcomeSupporting = listOf("unscathed", "here", "friends", "global"),
            // HUD wording that only exists once you are actually in the world.
            inGame = listOf("roll", "rolls", "luck", "inventory", "index", "craft", "equip", "quests", "shop", "auto"),
            playButtonArea = NormBox(left = 0.20f, top = 0.78f, right = 0.80f, bottom = 1.0f),
        ),
        events = listOf(GameEventRule.DARK_ARTS_MERCHANT),
        suggestedPlaceId = "122951224417794",
        suggestedPlaceName = "Unscathed RNG",
    )

    val all: List<GameProfile> = listOf(UNSCATHED)
    val default: GameProfile = UNSCATHED

    fun byId(id: String): GameProfile = all.firstOrNull { it.id == id } ?: default

    fun byCategory(): Map<GameCategory, List<GameProfile>> = all.groupBy { it.category }
}

/** Turns what the user pastes (game page, share link, bare number) into something Roblox can open. */
object GameLinks {
    private val placeIdPatterns = listOf(
        Regex("""roblox\.com/(?:[a-z]{2}/)?games/(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""placeId=(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(\d{4,})\s*$"""),
    )

    fun parsePlaceId(input: String): String? =
        placeIdPatterns.firstNotNullOfOrNull { it.find(input)?.groupValues?.get(1) }

    fun isPrivateServerLink(input: String): Boolean {
        val s = input.lowercase()
        return "privateserverlinkcode=" in s || ("roblox.com/share" in s && "type=server" in s)
    }

    /** Most specific first: private server, then deep link into the place, then the web page. */
    fun launchUris(placeId: String, privateServerLink: String): List<String> = buildList {
        if (privateServerLink.isNotBlank()) add(privateServerLink.trim())
        if (placeId.isNotBlank()) {
            add("roblox://experiences/start?placeId=$placeId")
            add("https://www.roblox.com/games/$placeId")
        }
    }
}
