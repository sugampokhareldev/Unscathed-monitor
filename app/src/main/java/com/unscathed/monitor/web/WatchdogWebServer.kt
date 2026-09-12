package com.unscathed.monitor.web

import android.content.Context
import com.unscathed.monitor.AppContainer
import com.unscathed.monitor.config.GameConfig
import com.unscathed.monitor.config.WatchdogSettings
import com.unscathed.monitor.service.MonitoringService
import com.unscathed.monitor.service.WatchdogRuntime
import com.unscathed.monitor.util.formatDateTime
import com.unscathed.monitor.util.formatDuration
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.net.NetworkInterface
import android.os.SystemClock

/**
 * Small HTTP server so the watchdog can be adjusted from a browser on the same Wi-Fi, without
 * picking the phone up: see what it is doing, grab the current screen, and mark the area where
 * in-game announcements appear.
 *
 * It runs only while monitoring is on, is reachable only on the local network, and every request
 * needs the key from Settings.
 */
class WatchdogWebServer(
    private val context: Context,
    private val container: AppContainer,
    port: Int,
    private val key: String,
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun serve(session: IHTTPSession): Response {
        return try {
            // Read the key from the query string directly: NanoHTTPD only fills parameters for POST bodies.
            val provided = session.queryParameterString?.split('&')
                ?.firstOrNull { it.startsWith("k=") }?.substringAfter("k=")
                ?: session.headers["x-key"]
            if (key.isBlank() || provided != key) {
                return text(Response.Status.FORBIDDEN, "Wrong or missing key. Open the link shown in the app.")
            }
            when (session.uri) {
                "/", "/index.html" -> asset("web/index.html", "text/html")
                "/api/status" -> json(statusJson())
                "/api/settings" -> if (session.method == Method.POST) applySettings(session) else json(settingsJson())
                "/api/frame/request" -> {
                    WatchdogRuntime.requestFrame()
                    json(buildJsonObject { put("ok", true) }.toString())
                }
                "/api/frame.jpg" -> frame()
                "/api/log" -> json(logJson())
                "/api/stop" -> {
                    MonitoringService.stop(context)
                    json(buildJsonObject { put("ok", true) }.toString())
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        } catch (t: Throwable) {
            Timber.e(t, "Web request failed: %s", session.uri)
            text(Response.Status.INTERNAL_ERROR, "Error: ${t.message}")
        }
    }

    private fun statusJson(): String {
        val s = WatchdogRuntime.status.value
        val settings = container.settings.state.value
        val now = SystemClock.elapsedRealtime()
        return buildJsonObject {
            put("monitoring", s.monitoring)
            put("state", s.state.label)
            put("game", s.gameName.ifBlank { settings.activeGame.name })
            put("runtime", s.robloxSinceMs?.let { formatDuration(now - it) } ?: "—")
            put("uptime", s.monitoringSinceMs?.let { formatDuration(now - it) } ?: "—")
            put("lastScreenChange", s.lastScreenChangeMs?.let { formatDuration(now - it) + " ago" } ?: "—")
            put("network", s.network.label)
            put("battery", s.battery?.summary ?: "—")
            put("temperature", s.battery?.temperatureLabel ?: "—")
            put("reconnects", s.reconnects)
            put("relaunches", s.relaunches)
            put("screen", s.debug.screen.label)
            put("confidence", s.debug.confidence)
            put("reasons", s.debug.reasons.joinToString("; "))
            put("pending", s.debug.pending ?: "")
            put("playClicks", s.playClicks)
            put("merchantSightings", s.merchantSightings)
            put("lastMerchant", s.lastMerchantWallMs?.let(::formatDateTime) ?: "-")
            put("lastEvent", s.lastEvent ?: "-")
            put("webhook", s.webhookNote ?: "not checked")
            put("webhookOk", s.webhookOk ?: false)
            put("lastError", s.lastError ?: "None")
            put("note", s.note ?: "")
            put("recoveryNote", s.recoveryNote ?: "")
            put("internetRetries", s.internetRetries)
            put("frameVersion", s.calibrationVersion)
        }.toString()
    }

    /** Recent events, so a session can be looked over without picking the phone up. */
    private fun logJson(): String = runBlocking {
        val events = container.events.recent(120).first()
        buildJsonArray {
            events.forEach { e ->
                add(
                    buildJsonObject {
                        put("time", e.timestampMs)
                        put("type", e.type)
                        put("title", e.title)
                        put("detail", e.detail)
                    },
                )
            }
        }.toString()
    }

    private fun settingsJson(): String {
        val s = container.settings.state.value
        val game = s.activeGame
        val c = game.config
        return buildJsonObject {
            put("game", game.name)
            put("placeId", c.placeId)
            put("eventIgnoreTopPercent", c.eventIgnoreTopPercent)
            put("eventIgnoreBottomPercent", c.eventIgnoreBottomPercent)
            put("eventIgnoreLeftPercent", c.eventIgnoreLeftPercent)
            put("eventIgnoreRightPercent", c.eventIgnoreRightPercent)
            put("eventConfirmScans", c.eventConfirmScans)
            put("eventCooldownMinutes", c.eventCooldownMinutes)
            put("eventScanSec", c.eventScanSec)
            put("confirmScans", c.confirmScans)
            put("playVerifySec", c.playVerifySec)
            put("playCooldownSec", c.playCooldownSec)
            put("playMaxAttempts", c.playMaxAttempts)
            put("freezeSeconds", c.freezeSeconds)
            put("autoRecover", s.autoRecover)
            put("discordEnabled", s.discordEnabled)
            put("debugLogOcr", s.debugLogOcr)
            put("statusReportMinutes", s.statusReportMinutes)
        }.toString()
    }

    /** Applies the fields present in the posted JSON; anything else is left alone. */
    private fun applySettings(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"].orEmpty().ifBlank { return text(Response.Status.BAD_REQUEST, "Empty body") }
        val obj = json.parseToJsonElement(body) as? JsonObject
            ?: return text(Response.Status.BAD_REQUEST, "Expected a JSON object")

        runBlocking {
            container.settings.update { current ->
                val profile = current.activeGame.profile
                var updated = current.applyGlobals(obj)
                updated.withGameConfig(profile, updated.configFor(profile).applyGame(obj))
            }
        }
        container.events.log(
            com.unscathed.monitor.data.EventType.SYSTEM,
            "Settings changed from the web page",
            obj.keys.joinToString(", "),
        )
        return json(settingsJson())
    }

    private fun WatchdogSettings.applyGlobals(o: JsonObject) = copy(
        autoRecover = o.bool("autoRecover") ?: autoRecover,
        discordEnabled = o.bool("discordEnabled") ?: discordEnabled,
        debugLogOcr = o.bool("debugLogOcr") ?: debugLogOcr,
        statusReportMinutes = o.int("statusReportMinutes") ?: statusReportMinutes,
    )

    private fun GameConfig.applyGame(o: JsonObject) = copy(
        placeId = o.str("placeId") ?: placeId,
        eventIgnoreTopPercent = o.int("eventIgnoreTopPercent") ?: eventIgnoreTopPercent,
        eventIgnoreBottomPercent = o.int("eventIgnoreBottomPercent") ?: eventIgnoreBottomPercent,
        eventIgnoreLeftPercent = o.int("eventIgnoreLeftPercent") ?: eventIgnoreLeftPercent,
        eventIgnoreRightPercent = o.int("eventIgnoreRightPercent") ?: eventIgnoreRightPercent,
        eventConfirmScans = o.int("eventConfirmScans") ?: eventConfirmScans,
        eventCooldownMinutes = o.int("eventCooldownMinutes") ?: eventCooldownMinutes,
        eventScanSec = o.int("eventScanSec") ?: eventScanSec,
        confirmScans = o.int("confirmScans") ?: confirmScans,
        playVerifySec = o.int("playVerifySec") ?: playVerifySec,
        playCooldownSec = o.int("playCooldownSec") ?: playCooldownSec,
        playMaxAttempts = o.int("playMaxAttempts") ?: playMaxAttempts,
        freezeSeconds = o.int("freezeSeconds") ?: freezeSeconds,
    )

    private fun JsonObject.prim(name: String): JsonPrimitive? = (this[name] as? JsonPrimitive)
    private fun JsonObject.str(name: String): String? = prim(name)?.content
    private fun JsonObject.int(name: String): Int? = prim(name)?.intOrNull
    private fun JsonObject.long(name: String): Long? = prim(name)?.longOrNull
    private fun JsonObject.bool(name: String): Boolean? = prim(name)?.booleanOrNull

    private fun frame(): Response {
        val path = WatchdogRuntime.status.value.calibrationPath
            ?: return text(Response.Status.NOT_FOUND, "No screenshot yet")
        val file = File(path)
        if (!file.exists()) return text(Response.Status.NOT_FOUND, "No screenshot yet")
        val bytes = file.readBytes()
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        ).apply { addHeader("Cache-Control", "no-store") }
    }

    private fun asset(name: String, mime: String): Response {
        val bytes = context.assets.open(name).use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun json(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain", body)

    companion object {
        /** The phone's address on the Wi-Fi, for the link shown in the app. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
        }.getOrNull()
    }
}
