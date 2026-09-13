package com.unscathed.monitor.telemetry

import kotlinx.serialization.Serializable

/** The only telemetry schema this build understands. Anything else is rejected, never guessed at. */
const val SUPPORTED_SCHEMA_VERSION = 1

/**
 * One `/state` packet from the local collector.
 *
 * Every section is optional: a collector that cannot see a part of the game yet simply leaves it
 * out, and a missing value always means "unknown" - never false, never zero.
 */
@Serializable
data class LuaStatePacket(
    val schemaVersion: Int,
    /** Increases with every packet; used to ignore retried or out-of-order ones. */
    val sequence: Long? = null,
    val serverTime: Double? = null,
    val player: LuaPlayerState? = null,
    val glider: LuaGliderState? = null,
    val innkeeper: LuaInnkeeperState? = null,
    val darkArts: LuaDarkArtsState? = null,
    val weather: LuaWeatherState? = null,
)

@Serializable
data class LuaPlayerState(
    val inGame: Boolean? = null,
    val health: Double? = null,
)

@Serializable
data class LuaGliderState(
    val ready: Boolean? = null,
    val remaining: Double? = null,
    val cooldownEndsAt: Double? = null,
)

/**
 * [stockLoaded] false means the shop has not been opened yet, so [stock] says nothing - it is not
 * the same as every item being sold out.
 */
@Serializable
data class LuaInnkeeperState(
    val rotationId: Long? = null,
    val stockLoaded: Boolean? = null,
    val stock: Map<String, Int>? = null,
)

@Serializable
data class LuaDarkArtsState(
    val active: Boolean? = null,
)

/** Weather names are not hardcoded anywhere: they are whatever the game reports. */
@Serializable
data class LuaWeatherState(
    val name: String? = null,
    val timeLeft: Int? = null,
)

/** Why a telemetry body was refused. [code] is what goes back to the collector. */
enum class RejectReason(val code: String, val isSchemaProblem: Boolean = false) {
    EMPTY_BODY("empty_body"),
    MALFORMED_JSON("malformed_json"),
    NOT_AN_OBJECT("not_an_object"),
    MISSING_SCHEMA_VERSION("missing_schema_version", isSchemaProblem = true),
    UNSUPPORTED_SCHEMA_VERSION("unsupported_schema_version", isSchemaProblem = true),
    INVALID_FIELDS("invalid_fields"),
}

sealed interface TelemetryParseResult<out T> {
    data class Ok<T>(val value: T) : TelemetryParseResult<T>
    data class Rejected(val reason: RejectReason, val detail: String) : TelemetryParseResult<Nothing>
}
