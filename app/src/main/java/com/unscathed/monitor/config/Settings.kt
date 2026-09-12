package com.unscathed.monitor.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unscathed.monitor.analyzer.GameEventRule
import com.unscathed.monitor.analyzer.RegionMask
import com.unscathed.monitor.analyzer.RobloxErrorCodes
import com.unscathed.monitor.analyzer.ScreenState
import com.unscathed.monitor.automation.PlayPolicy
import com.unscathed.monitor.games.Feature
import com.unscathed.monitor.games.GameCatalog
import com.unscathed.monitor.games.GameLinks
import com.unscathed.monitor.games.GameProfile
import com.unscathed.monitor.network.AlertEvent
import com.unscathed.monitor.network.DiscordMention
import com.unscathed.monitor.recovery.RecoveryPolicy
import com.unscathed.monitor.state.Thresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "watchdog_settings")

/** Per-game settings. Defaults come from the game's [GameProfile]. */
@Serializable
data class GameConfig(
    val placeId: String = "",
    val privateServerLink: String = "",
    val features: Set<Feature> = emptySet(),
    val freezeSeconds: Int = 60,
    val minMotionPercent: Float = 0.4f,
    val ignoreTopPercent: Int = 10,
    val ignoreBottomPercent: Int = 10,
    val ignoreLeftPercent: Int = 0,
    val ignoreRightPercent: Int = 0,
    val maxReconnectAttempts: Int = 3,
    val maxRelaunchAttempts: Int = 2,
    val noRecoverCodes: String = "268, 273",

    // Screen-state detection
    /** How many agreeing readings a state needs before the watchdog acts on it. */
    val confirmScans: Int = 3,
    /** Rejoin when Roblox drops back to its own home screen. */
    val rejoinWhenNotInGame: Boolean = true,

    // Auto Play
    /** Seconds to wait after tapping Play before deciding the tap failed. */
    val playVerifySec: Int = 30,
    /** Shortest gap between two Play taps. */
    val playCooldownSec: Int = 20,
    /** Failed taps in a row before Auto Play stands down. */
    val playMaxAttempts: Int = 4,

    // In-game event alerts
    /** Ids of the game's [GameEventRule]s that are switched on. Empty = all of them. */
    val disabledEventIds: Set<String> = emptySet(),
    /** Sightings needed before an event alert is sent. */
    val eventConfirmScans: Int = 2,
    /** Minutes before the same event can alert again. */
    val eventCooldownMinutes: Int = 10,
    /** Only read this part of the screen for announcements (percent cut from each edge). */
    val eventIgnoreTopPercent: Int = 0,
    val eventIgnoreBottomPercent: Int = 0,
    val eventIgnoreLeftPercent: Int = 0,
    val eventIgnoreRightPercent: Int = 0,
    /** How often the announcement area is read, in seconds. */
    val eventScanSec: Int = 3,
) {
    fun has(feature: Feature) = feature in features

    fun sanitized() = copy(
        placeId = placeId.trim(),
        privateServerLink = privateServerLink.trim(),
        freezeSeconds = freezeSeconds.coerceIn(15, 600),
        minMotionPercent = minMotionPercent.coerceIn(0.05f, 10f),
        ignoreTopPercent = ignoreTopPercent.coerceIn(0, 45),
        ignoreBottomPercent = ignoreBottomPercent.coerceIn(0, 45),
        ignoreLeftPercent = ignoreLeftPercent.coerceIn(0, 45),
        ignoreRightPercent = ignoreRightPercent.coerceIn(0, 45),
        maxReconnectAttempts = maxReconnectAttempts.coerceIn(0, 10),
        maxRelaunchAttempts = maxRelaunchAttempts.coerceIn(0, 10),
        confirmScans = confirmScans.coerceIn(2, 10),
        playVerifySec = playVerifySec.coerceIn(10, 180),
        playCooldownSec = playCooldownSec.coerceIn(5, 300),
        playMaxAttempts = playMaxAttempts.coerceIn(1, 20),
        eventConfirmScans = eventConfirmScans.coerceIn(1, 6),
        eventCooldownMinutes = eventCooldownMinutes.coerceIn(1, 24 * 60),
        eventIgnoreTopPercent = eventIgnoreTopPercent.coerceIn(0, 60),
        eventIgnoreBottomPercent = eventIgnoreBottomPercent.coerceIn(0, 60),
        eventIgnoreLeftPercent = eventIgnoreLeftPercent.coerceIn(0, 60),
        eventIgnoreRightPercent = eventIgnoreRightPercent.coerceIn(0, 60),
        eventScanSec = eventScanSec.coerceIn(1, 30),
    )

    companion object {
        fun defaultsFor(profile: GameProfile) = GameConfig(
            features = profile.defaultFeatures,
            noRecoverCodes = profile.defaultNoRecoverCodes.joinToString(", "),
            ignoreTopPercent = profile.defaultIgnoreTopPercent,
            ignoreBottomPercent = profile.defaultIgnoreBottomPercent,
        )
    }
}

/** A Discord webhook and the alert events it receives. */
@Serializable
data class WebhookConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Main",
    val url: String = "",
    val enabled: Boolean = true,
    val events: Set<AlertEvent> = AlertEvent.entries.toSet(),
) {
    val usable: Boolean get() = enabled && url.isNotBlank()

    /** Looks like a Discord webhook rather than a pasted channel link or half a URL. */
    val looksValid: Boolean
        get() = url.trim().startsWith("https://") && Regex("""/api/webhooks/\d+/[\w-]+""").containsMatchIn(url)
}

/** The game being watched right now, with everything the service needs derived from it. */
data class ActiveGame(val profile: GameProfile, val config: GameConfig) {
    val name: String get() = profile.name
    val packageName: String get() = profile.packageName
    fun has(feature: Feature) = feature in profile.supportedFeatures && config.has(feature)

    val launchUris: List<String> get() = GameLinks.launchUris(config.placeId, config.privateServerLink)

    val thresholds: Thresholds
        get() {
            val freezeMs = config.freezeSeconds * 1000L
            return Thresholds(suspectStillMs = minOf(10_000L, freezeMs / 2), freezeMs = freezeMs)
        }

    val mask: RegionMask
        get() = RegionMask(
            top = config.ignoreTopPercent / 100f,
            bottom = config.ignoreBottomPercent / 100f,
            left = config.ignoreLeftPercent / 100f,
            right = config.ignoreRightPercent / 100f,
        )

    val recoveryPolicy: RecoveryPolicy
        get() = RecoveryPolicy(
            tapReconnect = has(Feature.AUTO_RECONNECT),
            rejoin = has(Feature.AUTO_REJOIN),
            maxReconnects = config.maxReconnectAttempts,
            maxRelaunches = config.maxRelaunchAttempts,
            persistent = has(Feature.KEEP_RETRYING),
            recoverFrozen = has(Feature.REJOIN_WHEN_FROZEN),
            noRecoverCodes = RobloxErrorCodes.parseCodeList(config.noRecoverCodes),
        )

    val playPolicy: PlayPolicy
        get() = PlayPolicy(
            enabled = has(Feature.AUTO_PLAY),
            verifyWindowMs = config.playVerifySec * 1000L,
            cooldownMs = config.playCooldownSec * 1000L,
            maxAttempts = config.playMaxAttempts,
        )

    /** How many agreeing readings each screen needs. Actions need more than observations. */
    val confirmStreaks: Map<ScreenState, Int>
        get() {
            val n = config.confirmScans
            return mapOf(
                ScreenState.DISCONNECTED to (n - 1).coerceAtLeast(2),
                ScreenState.ROBLOX_CLOSED to (n - 1).coerceAtLeast(2),
                ScreenState.WELCOME to n,
                ScreenState.UNKNOWN to n + 1,
            )
        }

    /** The announcements being watched for, cropped to the area the user marked. */
    val eventRules: List<GameEventRule>
        get() {
            if (!has(Feature.EVENT_ALERTS)) return emptyList()
            val region = com.unscathed.monitor.analyzer.NormBox(
                left = config.eventIgnoreLeftPercent / 100f,
                top = config.eventIgnoreTopPercent / 100f,
                right = 1f - config.eventIgnoreRightPercent / 100f,
                bottom = 1f - config.eventIgnoreBottomPercent / 100f,
            )
            return profile.events
                .filter { it.id !in config.disabledEventIds }
                .map { it.copy(region = region) }
        }
}

@Serializable
data class WifiSettings(
    /** Keep trying to get back online for as long as the phone is offline. */
    val autoRecover: Boolean = true,
    /** The network registered with the phone (the password is never stored). */
    val ssid: String = "",
    val savedToPhone: Boolean = false,
    /** Android 10+: open the Wi-Fi panel and flip the switch through Accessibility. */
    val toggleViaAccessibility: Boolean = false,
)

@Serializable
data class WatchdogSettings(
    // Discord
    /** Legacy single-webhook field; moved into [webhooks] on load. */
    val webhookUrl: String = "",
    val webhooks: List<WebhookConfig> = emptyList(),
    val discordEnabled: Boolean = true,
    val deviceName: String = "AFK Phone",
    /** Numeric Discord user ID; the only thing a webhook can actually ping. */
    val pingUserId: String = "",
    /** Shown as "@name" when no ID is set (Discord will not ping it). */
    val pingName: String = "noobeiiks",
    val pingEvents: Set<AlertEvent> = AlertEvent.defaultPings,
    /** Refuse to start monitoring until a webhook has been tested successfully. */
    val requireWebhookCheck: Boolean = true,

    // Game
    val activeGameId: String = GameCatalog.default.id,
    val games: Map<String, GameConfig> = emptyMap(),
    /** Master switch for Reconnect / rejoin; the per-game features decide which actions are used. */
    val autoRecover: Boolean = false,

    // Capture
    val captureIntervalSec: Int = 3,
    val captureScalePercent: Int = 60,
    val ocrHeartbeatSec: Int = 15,
    /** Log every line of screen text to the Log tab, for working out why something fired. */
    val debugLogOcr: Boolean = false,
    /** Keep a screenshot whenever the screen could not be recognised. */
    val saveFailureScreenshots: Boolean = true,
    /**
     * Hold the screen on for as long as monitoring runs.
     *
     * On by default, because the alternative defeats the whole point: when the screen sleeps,
     * capture stops, and when it wakes the lock screen sits over the game and swallows every
     * tap the watchdog sends.
     */
    val keepScreenAwake: Boolean = true,

    // Internet
    val wifi: WifiSettings = WifiSettings(),

    // Remote control from a browser on the same Wi-Fi
    val webEnabled: Boolean = true,
    val webPort: Int = 8099,
    /** Anyone with this key can change settings, so it is generated per install. */
    val webKey: String = "",

    // System
    val statusReportMinutes: Int = 60,
    val lowBatteryPercent: Int = 20,
    val highTempC: Int = 45,
) {
    fun configFor(profile: GameProfile): GameConfig = games[profile.id] ?: GameConfig.defaultsFor(profile)

    val activeGame: ActiveGame
        get() {
            val profile = GameCatalog.byId(activeGameId)
            return ActiveGame(profile, configFor(profile))
        }

    fun withGameConfig(profile: GameProfile, config: GameConfig) = copy(games = games + (profile.id to config))

    val hasWebhook: Boolean get() = webhooks.any { it.usable }

    val pingUserIdParsed: String? get() = DiscordMention.parseUserId(pingUserId)

    /** Text to put in the message for [event]: a real ping, "@name" text, or nothing. */
    fun mentionFor(event: AlertEvent): String? =
        if (event in pingEvents) DiscordMention.content(pingUserIdParsed, pingName) else null

    fun migrated(): WatchdogSettings {
        var s = this
        if (webhooks.isEmpty() && webhookUrl.isNotBlank()) {
            s = s.copy(webhooks = listOf(WebhookConfig(name = "Main", url = webhookUrl.trim())), webhookUrl = "")
        }
        // The remote-control key has to exist before the link is shown, not just after a save.
        if (s.webKey.isBlank()) s = s.copy(webKey = UUID.randomUUID().toString().take(8))
        return s
    }

    fun sanitized(): WatchdogSettings {
        val base = migrated()
        return base.copy(
            webhooks = base.webhooks.map { it.copy(url = it.url.trim(), name = it.name.trim().ifBlank { "Webhook" }) },
            pingUserId = pingUserId.trim(),
            games = games.mapValues { it.value.sanitized() },
            wifi = wifi.copy(ssid = wifi.ssid.trim()),
            captureIntervalSec = captureIntervalSec.coerceIn(1, 30),
            captureScalePercent = captureScalePercent.coerceIn(30, 100),
            ocrHeartbeatSec = ocrHeartbeatSec.coerceIn(5, 300),
            webPort = webPort.coerceIn(1024, 65535),
            statusReportMinutes = statusReportMinutes.coerceIn(0, 24 * 60),
            lowBatteryPercent = lowBatteryPercent.coerceIn(0, 100),
            highTempC = highTempC.coerceIn(30, 70),
        )
    }
}

/** All settings live in one JSON blob so new fields just need a default value. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings_json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val flow: Flow<WatchdogSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it[key]) }
        .distinctUntilChanged()

    val state: StateFlow<WatchdogSettings> = flow.stateIn(scope, SharingStarted.Eagerly, WatchdogSettings())

    suspend fun current(): WatchdogSettings = flow.first()

    suspend fun update(transform: (WatchdogSettings) -> WatchdogSettings) {
        context.settingsDataStore.edit { prefs ->
            val updated = transform(decode(prefs[key])).sanitized()
            prefs[key] = json.encodeToString(WatchdogSettings.serializer(), updated)
        }
    }

    private fun decode(raw: String?): WatchdogSettings =
        (raw?.let { runCatching { json.decodeFromString(WatchdogSettings.serializer(), it) }.getOrNull() }
            ?: WatchdogSettings()).migrated()
}
