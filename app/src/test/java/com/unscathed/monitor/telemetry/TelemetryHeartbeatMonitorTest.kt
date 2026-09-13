package com.unscathed.monitor.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryHeartbeatMonitorTest {

    @Test
    fun nothingIsConnectedBeforeTheFirstPacket() {
        val h = TelemetryHeartbeatMonitor()
        assertTrue(h.check(100_000).isEmpty())
        assertEquals(TelemetryConnection.NEVER_CONNECTED, h.connection)
        assertFalse(h.isConnected(100_000))
        assertNull(h.lastPacketAgeMs(100_000))
    }

    @Test
    fun theFirstPacketConnects() {
        val h = TelemetryHeartbeatMonitor()
        val changes = h.onPacket(1_000)
        assertEquals(listOf(TelemetryConnectionChange.Connected(1_000, gapMs = null)), changes)
        assertTrue(h.isConnected(1_500))
        assertEquals(500L, h.lastPacketAgeMs(1_500))
    }

    /** Normal traffic: one packet every 1.5 s produces exactly one event, the first. */
    @Test
    fun regularPacketsStayConnectedWithoutRepeatingTheEvent() {
        val h = TelemetryHeartbeatMonitor()
        var events = 0
        for (i in 0 until 40) {
            val t = i * 1_500L
            events += h.onPacket(t).size
            events += h.check(t + 1_000).size
        }
        assertEquals(1, events)
        assertEquals(TelemetryConnection.CONNECTED, h.connection)
    }

    @Test
    fun sevenSecondsOfSilenceIsLostAndReportedOnce() {
        val h = TelemetryHeartbeatMonitor()
        h.onPacket(0)
        assertTrue(h.check(6_999).isEmpty())
        assertTrue(h.isConnected(6_999))

        assertEquals(listOf(TelemetryConnectionChange.Lost(7_000, lastPacketMs = 0)), h.check(7_000))
        assertEquals(TelemetryConnection.LOST, h.connection)
        assertTrue(h.check(8_000).isEmpty())
        assertTrue(h.check(60_000).isEmpty())
    }

    @Test
    fun aPacketAfterALossReconnects() {
        val h = TelemetryHeartbeatMonitor()
        h.onPacket(0)
        h.check(10_000)
        assertEquals(listOf(TelemetryConnectionChange.Connected(20_000, gapMs = 20_000)), h.onPacket(20_000))
        assertTrue(h.isConnected(20_100))
    }

    /** If nobody checked during the silence, the loss is still reported before the reconnect. */
    @Test
    fun anUnnoticedGapIsStillReportedInOrder() {
        val h = TelemetryHeartbeatMonitor()
        h.onPacket(0)
        assertEquals(
            listOf(
                TelemetryConnectionChange.Lost(30_000, lastPacketMs = 0),
                TelemetryConnectionChange.Connected(30_000, gapMs = 30_000),
            ),
            h.onPacket(30_000),
        )
    }

    @Test
    fun theTimeoutIsConfigurable() {
        val h = TelemetryHeartbeatMonitor(timeoutMs = 3_000)
        h.onPacket(0)
        assertTrue(h.check(2_999).isEmpty())
        assertEquals(1, h.check(3_000).size)
    }

    /** The UI reads this between checks, so it has to follow the clock, not the last check. */
    @Test
    fun isConnectedNeverLagsBehindTheClock() {
        val h = TelemetryHeartbeatMonitor()
        h.onPacket(0)
        assertEquals(TelemetryConnection.CONNECTED, h.connection)
        assertFalse(h.isConnected(10_000))
    }

    @Test
    fun aNonsenseTimeoutIsClampedRatherThanFlappingOnEveryPacket() {
        val h = TelemetryHeartbeatMonitor(timeoutMs = 0)
        assertEquals(TelemetryHeartbeatMonitor.MIN_TIMEOUT_MS, h.timeoutMs)
        h.timeoutMs = -5
        assertEquals(TelemetryHeartbeatMonitor.MIN_TIMEOUT_MS, h.timeoutMs)
    }

    @Test
    fun resetForgetsEverything() {
        val h = TelemetryHeartbeatMonitor()
        h.onPacket(0)
        h.reset()
        assertEquals(TelemetryConnection.NEVER_CONNECTED, h.connection)
        assertNull(h.lastPacketMs)
        assertEquals(listOf(TelemetryConnectionChange.Connected(5_000, gapMs = null)), h.onPacket(5_000))
    }
}
