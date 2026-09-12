package com.unscathed.monitor.network

/**
 * Every alert belongs to one event. Each webhook subscribes to a set of events, and the user
 * picks which events ping them.
 */
enum class AlertEvent(val label: String, val defaultPing: Boolean) {
    DISCONNECTED("Disconnected", defaultPing = true),
    RECONNECTED("Reconnected", defaultPing = true),
    RECONNECT_FAILED("Reconnect failed", defaultPing = true),
    MERCHANT("Dark Arts Merchant", defaultPing = true),
    AUTO_PLAY("Auto Play", defaultPing = false),
    TAPS_BLOCKED("Taps blocked", defaultPing = true),
    CRASHED("Roblox closed / crashed", defaultPing = false),
    LEFT_GAME("Left the game", defaultPing = false),
    FROZEN("Frozen", defaultPing = false),
    INTERNET("Internet lost / restored", defaultPing = false),
    DEVICE("Battery, heat, charger, screen", defaultPing = false),
    STATUS("Status report", defaultPing = false),
    WATCHDOG("Watchdog started / stopped", defaultPing = false),
    ;

    companion object {
        val defaultPings: Set<AlertEvent> = entries.filter { it.defaultPing }.toSet()
    }
}

/**
 * Discord webhooks can only ping by numeric ID (`<@123…>`); typing "@name" just shows text.
 * These helpers accept whatever the user pastes and build the ping.
 */
object DiscordMention {
    private val idPattern = Regex("""(\d{15,21})""")

    /** Pulls a user ID out of "123…", "<@123…>" or "<@!123…>". Returns null for "@name" or junk. */
    fun parseUserId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("<@&")) return null // role, not a user
        return idPattern.find(trimmed)?.groupValues?.get(1)?.takeIf { trimmed.all { c -> c.isDigit() || c in "<@!> " } }
    }

    /** Message content that pings the user, or shows "@name" as plain text when no ID is set. */
    fun content(userId: String?, displayName: String): String? = when {
        userId != null -> "<@$userId>"
        displayName.isNotBlank() -> "@" + displayName.trim().removePrefix("@")
        else -> null
    }
}
