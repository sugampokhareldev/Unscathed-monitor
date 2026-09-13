package com.unscathed.monitor.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaTelemetryParserTest {
    private fun ok(body: String): LuaStatePacket {
        val result = LuaTelemetryParser.parseState(body)
        assertTrue("expected Ok, got $result", result is TelemetryParseResult.Ok)
        return (result as TelemetryParseResult.Ok).value
    }

    private fun rejected(body: String): TelemetryParseResult.Rejected {
        val result = LuaTelemetryParser.parseState(body)
        assertTrue("expected Rejected, got $result", result is TelemetryParseResult.Rejected)
        return result as TelemetryParseResult.Rejected
    }

    @Test
    fun parsesTheDocumentedStatePacket() {
        val packet = ok(TelemetryFixtures.state())
        assertEquals(1, packet.schemaVersion)
        assertEquals(1253L, packet.sequence)
        assertEquals(1789324000.12, packet.serverTime!!, 0.001)
        assertEquals(true, packet.player?.inGame)
        assertEquals(100.0, packet.player?.health!!, 0.001)
        assertEquals(true, packet.glider?.ready)
        assertEquals(1789322400L, packet.innkeeper?.rotationId)
        assertEquals(6, packet.innkeeper?.stock?.get("ToughHunkOfBread"))
        assertEquals(false, packet.darkArts?.active)
        assertEquals("Normal", packet.weather?.name)
        assertEquals(480, packet.weather?.timeLeft)
    }

    /** A newer collector adding data must not break this build. */
    @Test
    fun unknownFieldsAreIgnoredAtEveryLevel() {
        val packet = ok(
            """{"schemaVersion":1,"sequence":5,"futureTopLevel":{"a":1},
               "player":{"inGame":false,"mana":42,"extra":[1,2,3]}}""",
        )
        assertEquals(false, packet.player?.inGame)
    }

    /** A collector that cannot see part of the game just leaves it out. */
    @Test
    fun aMinimalPacketParsesWithEverythingUnknown() {
        val packet = ok("""{"schemaVersion":1}""")
        assertNull(packet.sequence)
        assertNull(packet.player)
        assertNull(packet.weather)
    }

    @Test
    fun malformedJsonIsRejected() {
        assertEquals(RejectReason.MALFORMED_JSON, rejected("""{"schemaVersion":1,""").reason)
        assertEquals(RejectReason.MALFORMED_JSON, rejected("not json at all").reason)
    }

    @Test
    fun anEmptyBodyIsRejected() {
        assertEquals(RejectReason.EMPTY_BODY, rejected("").reason)
        assertEquals(RejectReason.EMPTY_BODY, rejected("   \n ").reason)
    }

    @Test
    fun anythingButAnObjectIsRejected() {
        assertEquals(RejectReason.NOT_AN_OBJECT, rejected("[1,2,3]").reason)
        assertEquals(RejectReason.NOT_AN_OBJECT, rejected("42").reason)
        assertEquals(RejectReason.NOT_AN_OBJECT, rejected("\"hello\"").reason)
    }

    @Test
    fun aMissingSchemaVersionIsRejected() {
        assertEquals(RejectReason.MISSING_SCHEMA_VERSION, rejected("""{"sequence":1}""").reason)
        assertEquals(RejectReason.MISSING_SCHEMA_VERSION, rejected("""{"schemaVersion":null}""").reason)
    }

    @Test
    fun anUnsupportedSchemaVersionIsRejected() {
        listOf("2", "0", "-1", "1.5").forEach { version ->
            val result = rejected(TelemetryFixtures.state(schemaVersion = version))
            assertEquals("schemaVersion $version", RejectReason.UNSUPPORTED_SCHEMA_VERSION, result.reason)
            assertTrue(result.reason.isSchemaProblem)
        }
    }

    /** A quoted "1" is a bug in the collector, not a version to be generous about. */
    @Test
    fun aSchemaVersionSentAsAStringIsRejected() {
        assertEquals(
            RejectReason.UNSUPPORTED_SCHEMA_VERSION,
            rejected(TelemetryFixtures.state(schemaVersion = "\"1\"")).reason,
        )
    }

    @Test
    fun wrongFieldTypesAreRejectedWithoutThrowing() {
        assertEquals(RejectReason.INVALID_FIELDS, rejected("""{"schemaVersion":1,"player":{"inGame":"yes"}}""").reason)
        assertEquals(RejectReason.INVALID_FIELDS, rejected("""{"schemaVersion":1,"sequence":"soon"}""").reason)
    }

    /** "Not loaded yet" and "sold out" are different facts and must stay distinguishable. */
    @Test
    fun stockNotLoadedStaysDistinctFromZeroStock() {
        val notLoaded = ok("""{"schemaVersion":1,"innkeeper":{"rotationId":7,"stockLoaded":false}}""")
        assertEquals(false, notLoaded.innkeeper?.stockLoaded)
        assertNull(notLoaded.innkeeper?.stock)

        val soldOut = ok("""{"schemaVersion":1,"innkeeper":{"rotationId":7,"stockLoaded":true,"stock":{"FreshWater":0}}}""")
        assertEquals(true, soldOut.innkeeper?.stockLoaded)
        assertEquals(0, soldOut.innkeeper?.stock?.get("FreshWater"))
    }

    /** The detail is shown in the Debug tab and echoed to the collector, so it stays bounded. */
    @Test
    fun rejectionDetailIsShortAndOnOneLine() {
        val huge = "{" + "\"k\":\n".repeat(5_000)
        val detail = rejected(huge).detail
        assertTrue("detail was ${detail.length} chars", detail.length <= 160)
        assertTrue(!detail.contains('\n'))
    }
}
