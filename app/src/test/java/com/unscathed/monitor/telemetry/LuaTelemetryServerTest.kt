package com.unscathed.monitor.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/**
 * Runs the real server over real HTTP on loopback - no mocks - so binding, limits, routing and
 * shutdown are exercised the way a collector would exercise them.
 */
class LuaTelemetryServerTest {
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private lateinit var repository: LuaTelemetryRepository
    private val servers = mutableListOf<LuaTelemetryServer>()

    @Before
    fun setUp() {
        repository = LuaTelemetryRepository(monoClock = clock)
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
    }

    private fun startServer(
        port: Int = 0,
        rateLimiter: TelemetryRateLimiter = TelemetryRateLimiter(),
        maxBodyBytes: Int = LuaTelemetryServer.MAX_BODY_BYTES,
    ): LuaTelemetryServer = LuaTelemetryServer(repository, port, clock, rateLimiter, maxBodyBytes).also {
        it.start(LuaTelemetryServer.READ_TIMEOUT_MS, true)
        servers += it
    }

    private data class Reply(val status: Int, val body: String)

    private fun request(server: LuaTelemetryServer, method: String, path: String, body: String? = null): Reply {
        val conn = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.useCaches = false
            conn.setRequestProperty("Connection", "close")
            if (body != null) {
                val bytes = body.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return Reply(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    /** For requests HttpURLConnection will not send: no Content-Length, lies, garbage. */
    private fun rawStatus(server: LuaTelemetryServer, raw: ByteArray): Int =
        Socket(InetAddress.getByName("127.0.0.1"), server.listeningPort).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply {
                write(raw)
                flush()
            }
            val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
            line?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: -1
        }

    @Test
    fun bindsToLoopbackOnly() {
        val server = startServer()
        assertEquals("127.0.0.1", server.hostname)
        assertEquals(200, request(server, "GET", "/health").status)

        // The real guarantee: from this machine's own network address, the port is not there.
        val lanAddress = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }.getOrNull()
        if (lanAddress != null) {
            val reachable = runCatching {
                Socket().use { it.connect(InetSocketAddress(lanAddress, server.listeningPort), 1_000) }
            }.isSuccess
            assertFalse("telemetry must not be reachable on ${lanAddress.hostAddress}", reachable)
        }
    }

    @Test
    fun healthAnswersBeforeAnyCollectorConnects() {
        val reply = request(startServer(), "GET", "/health")
        assertEquals(200, reply.status)
        assertTrue(reply.body, reply.body.contains("\"status\":\"ok\""))
        assertTrue(reply.body, reply.body.contains("\"schemaVersion\":1"))
        assertTrue(reply.body, reply.body.contains("\"connected\":false"))
    }

    @Test
    fun aValidStatePacketReachesTheRepository() {
        val server = startServer()
        val reply = request(server, "POST", "/state", TelemetryFixtures.state(sequence = 1253))
        assertEquals(200, reply.status)
        assertTrue(reply.body, reply.body.contains("\"ok\":true"))

        val s = repository.snapshot.value
        assertEquals(1253L, s.lastSequence)
        assertEquals(true, s.state?.player?.inGame)
        assertTrue(request(server, "GET", "/health").body.contains("\"connected\":true"))
    }

    @Test
    fun aRetriedPacketIsAcknowledgedAsStale() {
        val server = startServer()
        request(server, "POST", "/state", TelemetryFixtures.state(sequence = 10))
        val reply = request(server, "POST", "/state", TelemetryFixtures.state(sequence = 9))
        assertEquals(200, reply.status)
        assertTrue(reply.body, reply.body.contains("\"stale\":true"))
        assertEquals(10L, repository.snapshot.value.lastSequence)
    }

    @Test
    fun malformedJsonIsRejected() {
        val reply = request(startServer(), "POST", "/state", "{nope")
        assertEquals(400, reply.status)
        assertTrue(reply.body, reply.body.contains("malformed_json"))
        assertEquals(1L, repository.snapshot.value.rejectedPackets)
    }

    @Test
    fun anUnsupportedSchemaIsRefusedAsUnprocessable() {
        val reply = request(startServer(), "POST", "/state", TelemetryFixtures.state(schemaVersion = "2"))
        assertEquals(422, reply.status)
        assertTrue(reply.body, reply.body.contains("unsupported_schema_version"))
        assertNull(repository.snapshot.value.lastSequence)
    }

    @Test
    fun anOversizedBodyIsRefusedWithoutBeingRead() {
        val server = startServer(maxBodyBytes = 256)
        val body = "x".repeat(1_000)
        val raw = "POST /state HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n$body"
        assertEquals(413, rawStatus(server, raw.toByteArray()))
        assertEquals(1L, repository.snapshot.value.rejectedPackets)
        assertNull(repository.snapshot.value.lastSequence)
    }

    @Test
    fun aBodyWithoutContentLengthIsRefused() {
        val raw = "POST /state HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n{\"schemaVersion\":1}"
        assertEquals(411, rawStatus(startServer(), raw.toByteArray()))
    }

    @Test
    fun aContentLengthThatIsNotANumberIsRefused() {
        val raw = "POST /state HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: lots\r\nConnection: close\r\n\r\n{}"
        assertEquals(400, rawStatus(startServer(), raw.toByteArray()))
    }

    @Test
    fun theWrongMethodIsRefused() {
        val server = startServer()
        assertEquals(405, request(server, "GET", "/state").status)
        assertEquals(405, request(server, "POST", "/health", "{}").status)
    }

    @Test
    fun anUnknownPathIsNotFound() {
        assertEquals(404, request(startServer(), "GET", "/admin").status)
    }

    /** Milestone 1 does not accept events; that arrives with deduplication in Milestone 2. */
    @Test
    fun eventsAreNotAcceptedYet() {
        val reply = request(startServer(), "POST", "/event", """{"schemaVersion":1,"type":"AURA_ROLLED"}""")
        assertEquals(501, reply.status)
    }

    @Test
    fun floodingIsRateLimited() {
        val server = startServer(rateLimiter = TelemetryRateLimiter(capacity = 3, refillPerSecond = 0.0))
        repeat(3) { assertEquals(200, request(server, "GET", "/health").status) }
        assertEquals(429, request(server, "GET", "/health").status)
        assertTrue(repository.snapshot.value.rateLimited >= 1)
    }

    /** Nothing the server says may carry webhook or Discord details, whatever is asked. */
    @Test
    fun noResponseEverMentionsWebhooksOrDiscord() {
        val server = startServer()
        val bodies = listOf(
            request(server, "GET", "/health"),
            request(server, "POST", "/state", TelemetryFixtures.state()),
            request(server, "POST", "/state", "{bad"),
            request(server, "GET", "/webhooks"),
            request(server, "GET", "/state"),
            request(server, "POST", "/event", "{}"),
        ).map { it.body.lowercase() }
        bodies.forEach { body ->
            assertFalse(body, body.contains("webhook"))
            assertFalse(body, body.contains("discord"))
        }
    }

    @Test
    fun garbageOnTheSocketDoesNotTakeTheServerDown() {
        val server = startServer()
        runCatching { rawStatus(server, byteArrayOf(0, 1, 2, 3) + "not http at all\r\n\r\n".toByteArray()) }
        runCatching { rawStatus(server, ByteArray(0)) }
        assertEquals(200, request(server, "GET", "/health").status)
    }

    /** Stopping must release the socket, or monitoring could never be restarted on the same port. */
    @Test
    fun stoppingReleasesThePortForTheNextSession() {
        val first = startServer()
        val port = first.listeningPort
        assertEquals(200, request(first, "GET", "/health").status)
        first.stop()

        val second = startServer(port = port)
        assertEquals(port, second.listeningPort)
        assertEquals(200, request(second, "GET", "/health").status)
    }
}
