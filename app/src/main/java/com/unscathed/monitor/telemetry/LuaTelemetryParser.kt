package com.unscathed.monitor.telemetry

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Turns a telemetry body into a packet, or says precisely why it could not.
 *
 * It never throws: whatever arrives on the socket is untrusted, and one bad packet must not be
 * able to take anything down. Unknown fields are ignored so a newer collector can add data
 * without breaking this build; the schema version is checked first so a genuinely incompatible
 * collector is refused outright instead of being half-understood.
 */
object LuaTelemetryParser {
    private const val MAX_DETAIL_CHARS = 160
    private val whitespace = Regex("\\s+")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parseState(body: String): TelemetryParseResult<LuaStatePacket> {
        if (body.isBlank()) return rejected(RejectReason.EMPTY_BODY, "The body was empty")

        val element = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            return rejected(RejectReason.MALFORMED_JSON, e.message)
        } catch (e: IllegalArgumentException) {
            return rejected(RejectReason.MALFORMED_JSON, e.message)
        }
        val obj = element as? JsonObject
            ?: return rejected(RejectReason.NOT_AN_OBJECT, "Expected a JSON object")

        val rawSchema = obj["schemaVersion"]
        if (rawSchema == null || rawSchema is JsonNull) {
            return rejected(RejectReason.MISSING_SCHEMA_VERSION, "schemaVersion is required")
        }
        // A quoted "1" is not accepted as 1: a collector that gets this wrong has other bugs too.
        val version = (rawSchema as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (version != SUPPORTED_SCHEMA_VERSION) {
            return rejected(
                RejectReason.UNSUPPORTED_SCHEMA_VERSION,
                "schemaVersion $rawSchema is not supported; this build reads v$SUPPORTED_SCHEMA_VERSION",
            )
        }

        return try {
            TelemetryParseResult.Ok(json.decodeFromJsonElement(LuaStatePacket.serializer(), obj))
        } catch (e: SerializationException) {
            rejected(RejectReason.INVALID_FIELDS, e.message)
        } catch (e: IllegalArgumentException) {
            rejected(RejectReason.INVALID_FIELDS, e.message)
        }
    }

    /** Kept short and on one line: it is shown in the Debug tab and sent back to the collector. */
    private fun rejected(reason: RejectReason, detail: String?): TelemetryParseResult.Rejected =
        TelemetryParseResult.Rejected(
            reason,
            (detail ?: reason.code).replace(whitespace, " ").trim().take(MAX_DETAIL_CHARS),
        )
}
