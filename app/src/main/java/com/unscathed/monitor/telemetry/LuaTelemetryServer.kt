package com.unscathed.monitor.telemetry

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Receives telemetry from a collector on this phone.
 *
 * Bound to 127.0.0.1 only, so nothing on the Wi-Fi can reach it. That is not the same as
 * trusted: any app on the phone can connect. So every request is treated as hostile input -
 * bounded in size, rate and concurrency, parsed defensively - and none of it can do anything
 * but update [LuaTelemetryRepository]. Responses carry no settings and no secrets.
 *
 * Deliberately free of Android types, so it is tested over real HTTP on the JVM.
 */
class LuaTelemetryServer(
    private val repository: LuaTelemetryRepository,
    port: Int,
    private val monoClock: () -> Long,
    private val rateLimiter: TelemetryRateLimiter = TelemetryRateLimiter(),
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    maxConcurrentConnections: Int = MAX_CONCURRENT_CONNECTIONS,
) : NanoHTTPD(LOOPBACK_HOST, port) {

    init {
        setAsyncRunner(BoundedAsyncRunner(maxConcurrentConnections))
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        // One bad request must never take the server down, and internals are never echoed back.
        error(HttpStatus.INTERNAL_ERROR, "internal_error", "The request could not be handled")
    }

    private fun route(session: IHTTPSession): Response {
        if (!rateLimiter.tryAcquire(monoClock())) {
            repository.onRateLimited()
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests")
        }
        return when (session.uri) {
            PATH_HEALTH -> if (session.method == Method.GET) health() else methodNotAllowed()
            PATH_STATE -> if (session.method == Method.POST) state(session) else methodNotAllowed()
            PATH_EVENT -> if (session.method == Method.POST) {
                error(HttpStatus.NOT_IMPLEMENTED, "not_implemented", "Events are not accepted by this version yet")
            } else {
                methodNotAllowed()
            }
            else -> error(HttpStatus.NOT_FOUND, "not_found", "Unknown path")
        }
    }

    private fun health(): Response = respond(
        HttpStatus.OK,
        buildJsonObject {
            put("status", "ok")
            put("schemaVersion", SUPPORTED_SCHEMA_VERSION)
            put("connected", repository.snapshot.value.isConnectedAt(monoClock()))
        },
    )

    private fun state(session: IHTTPSession): Response {
        val body = when (val read = readBody(session)) {
            is Body.Ok -> read.text
            is Body.Failed -> {
                repository.onTransportRejected("${read.code}: ${read.message}")
                return error(read.status, read.code, read.message)
            }
        }
        return when (val result = repository.acceptState(body)) {
            StateAcceptance.Accepted -> respond(HttpStatus.OK, buildJsonObject { put("ok", true) })
            StateAcceptance.Stale -> respond(
                HttpStatus.OK,
                buildJsonObject {
                    put("ok", true)
                    put("stale", true)
                },
            )
            is StateAcceptance.Rejected -> error(
                if (result.reason.isSchemaProblem) HttpStatus.UNPROCESSABLE_ENTITY else HttpStatus.BAD_REQUEST,
                result.reason.code,
                result.detail,
            )
        }
    }

    /**
     * Reads exactly Content-Length bytes, and refuses to read at all when that is missing or too
     * large. The body is never buffered to disk, and a slow sender is cut off by the socket timeout.
     */
    private fun readBody(session: IHTTPSession): Body {
        val header = session.headers["content-length"]
            ?: return Body.Failed(HttpStatus.LENGTH_REQUIRED, "length_required", "Content-Length is required")
        val length = header.trim().toLongOrNull()
        if (length == null || length < 0) {
            return Body.Failed(HttpStatus.BAD_REQUEST, "bad_length", "Content-Length is not a valid number")
        }
        if (length > maxBodyBytes) {
            return Body.Failed(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "Body exceeds $maxBodyBytes bytes")
        }
        val bytes = ByteArray(length.toInt())
        val input = session.inputStream
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        if (read < bytes.size) {
            return Body.Failed(HttpStatus.BAD_REQUEST, "truncated_body", "Body ended before Content-Length bytes")
        }
        return Body.Ok(String(bytes, Charsets.UTF_8))
    }

    private fun methodNotAllowed() = error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed")

    private fun error(status: HttpStatus, code: String, message: String): Response = respond(
        status,
        buildJsonObject {
            put("ok", false)
            put("error", code)
            put("detail", message)
        },
    )

    private fun respond(status: HttpStatus, body: JsonObject): Response =
        newFixedLengthResponse(status, MIME_JSON, body.toString()).apply {
            addHeader("Cache-Control", "no-store")
            // One request per connection, so nothing lingers holding a thread or a socket.
            closeConnection(true)
        }

    private sealed interface Body {
        data class Ok(val text: String) : Body
        data class Failed(val status: HttpStatus, val code: String, val message: String) : Body
    }

    private enum class HttpStatus(private val statusCode: Int, private val reason: String) : Response.IStatus {
        OK(200, "OK"),
        BAD_REQUEST(400, "Bad Request"),
        NOT_FOUND(404, "Not Found"),
        METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
        LENGTH_REQUIRED(411, "Length Required"),
        PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
        UNPROCESSABLE_ENTITY(422, "Unprocessable Entity"),
        TOO_MANY_REQUESTS(429, "Too Many Requests"),
        INTERNAL_ERROR(500, "Internal Server Error"),
        NOT_IMPLEMENTED(501, "Not Implemented"),
        ;

        override fun getDescription(): String = "$statusCode $reason"

        override fun getRequestStatus(): Int = statusCode
    }

    /**
     * NanoHTTPD's default runner starts a thread for every connection with no upper bound. This
     * one caps concurrent connections and simply closes any beyond the cap, so a flood cannot
     * exhaust threads, and [closeAll] tears everything down when the server stops.
     */
    private class BoundedAsyncRunner(maxConcurrent: Int) : AsyncRunner {
        private val active: MutableSet<ClientHandler> = Collections.newSetFromMap(ConcurrentHashMap())
        private val executor = ThreadPoolExecutor(
            0,
            maxConcurrent.coerceAtLeast(1),
            30,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { runnable -> Thread(runnable, "telemetry-http").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

        override fun exec(code: ClientHandler) {
            active += code
            try {
                executor.execute(code)
            } catch (e: RejectedExecutionException) {
                active -= code
                code.close()
            }
        }

        override fun closed(clientHandler: ClientHandler) {
            active -= clientHandler
        }

        override fun closeAll() {
            active.toList().forEach { it.close() }
            active.clear()
            executor.shutdownNow()
        }
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 17384
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_CONCURRENT_CONNECTIONS = 4
        const val READ_TIMEOUT_MS = 5_000

        const val PATH_HEALTH = "/health"
        const val PATH_STATE = "/state"
        const val PATH_EVENT = "/event"
        private const val MIME_JSON = "application/json"
    }
}
