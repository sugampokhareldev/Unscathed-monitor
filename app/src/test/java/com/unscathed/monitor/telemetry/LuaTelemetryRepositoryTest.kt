package com.unscathed.monitor.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaTelemetryRepositoryTest {
    private var mono = 0L
    private var wall = 1_700_000_000_000L
    private val changes = mutableListOf<TelemetryConnectionChange>()

    private fun repository(timeoutMs: Long = 7_000) =
        LuaTelemetryRepository(monoClock = { mono }, wallClock = { wall }, timeoutMs = timeoutMs)
            .also { repo -> repo.listener = { changes += it } }

    private val LuaTelemetryRepository.snap get() = snapshot.value

    @Test
    fun aValidPacketConnectsAndPublishesState() {
        val repo = repository()
        assertEquals(StateAcceptance.Accepted, repo.acceptState(TelemetryFixtures.state(sequence = 1)))

        val s = repo.snap
        assertEquals(TelemetryConnection.CONNECTED, s.connection)
        assertTrue(s.isConnectedAt(mono))
        assertEquals(1L, s.lastSequence)
        assertEquals(1, s.schemaVersion)
        assertEquals(1L, s.packetsReceived)
        assertEquals(true, s.state?.player?.inGame)
        assertEquals(wall, s.lastConnectedWallMs)
        assertEquals(1, changes.count { it is TelemetryConnectionChange.Connected })
    }

    @Test
    fun malformedPacketsAreCountedAndNeverCountAsAHeartbeat() {
        val repo = repository()
        val result = repo.acceptState("{broken")
        assertTrue(result is StateAcceptance.Rejected)
        assertEquals(RejectReason.MALFORMED_JSON, (result as StateAcceptance.Rejected).reason)

        val s = repo.snap
        assertEquals(1L, s.rejectedPackets)
        assertNotNull(s.lastParseError)
        assertEquals(wall, s.lastParseErrorWallMs)
        assertEquals(TelemetryConnection.NEVER_CONNECTED, s.connection)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun aBadPacketDoesNotDisturbAnEstablishedConnection() {
        val repo = repository()
        repo.acceptState(TelemetryFixtures.state(sequence = 1, inGame = true))
        mono = 1_000
        repo.acceptState("""{"schemaVersion":99}""")

        val s = repo.snap
        assertTrue(s.isConnectedAt(mono))
        assertEquals(true, s.state?.player?.inGame)
        assertEquals(1L, s.rejectedPackets)
    }

    /** A retried packet must not roll the state back - but it does prove the collector is alive. */
    @Test
    fun aRetriedOlderPacketIsIgnoredButKeepsTheConnectionAlive() {
        val repo = repository(timeoutMs = 7_000)
        repo.acceptState(TelemetryFixtures.state(sequence = 10, inGame = true))

        mono = 6_000
        assertEquals(StateAcceptance.Stale, repo.acceptState(TelemetryFixtures.state(sequence = 9, inGame = false)))
        assertEquals(true, repo.snap.state?.player?.inGame)
        assertEquals(10L, repo.snap.lastSequence)
        assertEquals(1L, repo.snap.stalePackets)

        // Without that stale packet the last one would be 12 s old and telemetry would be lost.
        mono = 12_000
        assertTrue(repo.tick().isEmpty())
        assertTrue(repo.snap.isConnectedAt(mono))
    }

    @Test
    fun aDuplicateSequenceIsStale() {
        val repo = repository()
        repo.acceptState(TelemetryFixtures.state(sequence = 5))
        assertEquals(StateAcceptance.Stale, repo.acceptState(TelemetryFixtures.state(sequence = 5)))
    }

    /** A collector restarted inside the timeout counts from 1 again and must not be ignored forever. */
    @Test
    fun aRestartedCollectorIsAcceptedAfterAShortRun() {
        val repo = repository()
        repo.acceptState(TelemetryFixtures.state(sequence = 500, inGame = true))

        mono = 1_000
        assertEquals(StateAcceptance.Stale, repo.acceptState(TelemetryFixtures.state(sequence = 1, inGame = false)))
        mono = 2_000
        assertEquals(StateAcceptance.Stale, repo.acceptState(TelemetryFixtures.state(sequence = 2, inGame = false)))
        mono = 3_000
        assertEquals(StateAcceptance.Accepted, repo.acceptState(TelemetryFixtures.state(sequence = 3, inGame = false)))

        assertEquals(3L, repo.snap.lastSequence)
        assertEquals(false, repo.snap.state?.player?.inGame)
    }

    /** The same old packet retried over and over is not a restart. */
    @Test
    fun repeatedRetriesOfOnePacketNeverLookLikeARestart() {
        val repo = repository()
        repo.acceptState(TelemetryFixtures.state(sequence = 500))
        repeat(10) {
            mono += 500
            assertEquals(StateAcceptance.Stale, repo.acceptState(TelemetryFixtures.state(sequence = 499)))
        }
        assertEquals(500L, repo.snap.lastSequence)
    }

    @Test
    fun silenceMarksTelemetryLostButKeepsTheLastKnownState() {
        val repo = repository(timeoutMs = 7_000)
        repo.acceptState(TelemetryFixtures.state(sequence = 1, inGame = true))

        mono = 6_999
        assertTrue(repo.tick().isEmpty())
        mono = 7_000
        wall += 7_000
        assertEquals(1, repo.tick().size)

        val s = repo.snap
        assertEquals(TelemetryConnection.LOST, s.connection)
        assertFalse(s.isConnectedAt(mono))
        assertEquals(wall, s.lastDisconnectedWallMs)
        // Kept for display as "last known"; it is not treated as current.
        assertEquals(true, s.state?.player?.inGame)
    }

    @Test
    fun telemetryReconnectsAfterALoss() {
        val repo = repository()
        repo.acceptState(TelemetryFixtures.state(sequence = 1))
        mono = 10_000
        repo.tick()
        mono = 15_000
        repo.acceptState(TelemetryFixtures.state(sequence = 2))

        assertTrue(repo.snap.isConnectedAt(mono))
        assertEquals(
            listOf("Connected", "Lost", "Connected"),
            changes.map { it::class.simpleName },
        )
    }

    /** After a real disconnect a collector may legitimately start its count over. */
    @Test
    fun theSequenceMayStartOverAfterADisconnect() {
        val repo = repository()
        repo.acceptState(TelemetryFixtures.state(sequence = 900))
        mono = 10_000
        repo.tick()
        mono = 11_000
        assertEquals(StateAcceptance.Accepted, repo.acceptState(TelemetryFixtures.state(sequence = 1)))
        assertEquals(1L, repo.snap.lastSequence)
    }

    @Test
    fun packetsWithoutASequenceAreAlwaysApplied() {
        val repo = repository()
        assertEquals(StateAcceptance.Accepted, repo.acceptState("""{"schemaVersion":1,"player":{"inGame":true}}"""))
        assertEquals(StateAcceptance.Accepted, repo.acceptState("""{"schemaVersion":1,"player":{"inGame":false}}"""))
        assertEquals(false, repo.snap.state?.player?.inGame)
        assertNull(repo.snap.lastSequence)
    }

    @Test
    fun stoppingTheServerEndsAnEstablishedConnectionCleanly() {
        val repo = repository()
        repo.onServerStarted(17384)
        repo.acceptState(TelemetryFixtures.state(sequence = 50))
        wall += 1_000
        repo.onServerStopped()

        val s = repo.snap
        assertFalse(s.serverRunning)
        assertNull(s.listeningPort)
        assertFalse(s.isConnectedAt(mono))
        assertEquals(wall, s.lastDisconnectedWallMs)
        assertNull(s.lastSequence)
        assertTrue(changes.last() is TelemetryConnectionChange.Lost)

        // A new session starts clean: sequence 1 is not stale.
        repo.onServerStarted(17384)
        assertEquals(StateAcceptance.Accepted, repo.acceptState(TelemetryFixtures.state(sequence = 1)))
    }

    @Test
    fun stoppingAServerThatNeverConnectedReportsNoLoss() {
        val repo = repository()
        repo.onServerStarted(17384)
        repo.onServerStopped()
        assertTrue(changes.isEmpty())
        assertNull(repo.snap.lastDisconnectedWallMs)
    }

    @Test
    fun transportRejectionsAndRateLimitsAreCounted() {
        val repo = repository()
        repo.onTransportRejected("payload_too_large: Body exceeds 16384 bytes")
        repo.onRateLimited()
        repo.onRateLimited()
        assertEquals(1L, repo.snap.rejectedPackets)
        assertEquals(2L, repo.snap.rateLimited)
        assertEquals("payload_too_large: Body exceeds 16384 bytes", repo.snap.lastParseError)
    }

    @Test
    fun aListenerThatThrowsCannotBreakTheRepository() {
        val repo = LuaTelemetryRepository(monoClock = { mono }, wallClock = { wall })
        repo.listener = { throw IllegalStateException("boom") }
        assertEquals(StateAcceptance.Accepted, repo.acceptState(TelemetryFixtures.state(sequence = 1)))
        assertTrue(repo.snap.isConnectedAt(mono))
    }
}
