package ai.rever.boss.mcp.telemetry

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

private val logger = BossLogger.forComponent("TelemetryOtlp")

/**
 * A loopback OTLP/HTTP receiver for trace spans.
 *
 * ## Why `com.sun.net.httpserver` and not Ktor
 *
 * `composeApp/build.gradle.kts` deliberately **excludes** the whole ktor server stack, with a
 * documented reason: 529 `io.ktor.server.*` classes sitting in the host classloader are fallback
 * targets for any plugin bundling its own ktor server, which is the exact material a plugin
 * classloader parent fallback turns into a loader constraint `LinkageError`. There is a CI guard,
 * `KtorServerAbsentFromHostTest`, that scans the classpath for `io/ktor/server/` and fails on any
 * newcomer.
 *
 * Adding a server framework back to satisfy this feature would reverse that decision for a single
 * endpoint. The JDK's own HTTP server needs no dependency at all, and `jdk.httpserver` is already
 * in the jlink module list, so packaged builds need no build change either.
 *
 * ## Posture
 *
 * The socket binds to the **loopback address**, which is a kernel level guarantee rather than a
 * check that could be bypassed: a non loopback peer cannot reach it in the first place. The
 * handler re-checks the remote address anyway, because defence that costs one comparison is worth
 * having.
 *
 * Nothing listens until an agent asks for traces. The receiver is started lazily by
 * `telemetry_query_traces`, so a BOSS install where nobody uses tracing never opens a port.
 */
internal class OtlpReceiver(
    val buffer: SpanBuffer = SpanBuffer(),
    private val port: Int = DEFAULT_PORT,
) {
    private var server: HttpServer? = null

    private val rejected = AtomicLong()

    /** The port actually bound, or null while stopped. */
    @Volatile
    var boundPort: Int? = null
        private set

    val isRunning: Boolean get() = server != null

    /**
     * Starts listening, or explains why not. Idempotent.
     *
     * A failure here is deliberately **not** fatal to anything else: a port already in use is an
     * ordinary condition (a real collector may be running), and the other five telemetry tools
     * must keep working when it happens.
     */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun start(): Result<Int> {
        server?.let { return Result.success(boundPort ?: port) }
        return try {
            val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG)
            created.createContext(TRACES_PATH) { exchange -> handle(exchange) }
            created.executor = Executors.newFixedThreadPool(HANDLER_THREADS, daemonThreadFactory())
            created.start()
            server = created
            boundPort = created.address.port
            logger.info(
                LogCategory.SYSTEM,
                "OTLP receiver listening",
                mapOf("endpoint" to "http://127.0.0.1:${created.address.port}$TRACES_PATH"),
            )
            Result.success(created.address.port)
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "OTLP receiver could not bind port $port", error = t)
            Result.failure(t)
        }
    }

    @Synchronized
    fun stop() {
        server?.let {
            it.stop(0)
            (it.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
        server = null
        boundPort = null
    }

    fun rejectedCount(): Long = rejected.get()

    /**
     * Handles one export.
     *
     * Never throws out of the handler: an exception escaping here kills the exchange without a
     * response, which a sender sees as a hang rather than an error.
     */
    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    private fun handle(exchange: HttpExchange) {
        try {
            exchange.use {
                if (!it.remoteAddress.address.isLoopbackAddress) {
                    rejected.incrementAndGet()
                    it.respond(HTTP_FORBIDDEN, """{"error":"loopback only"}""")
                    return@use
                }
                if (!it.requestMethod.equals("POST", ignoreCase = true)) {
                    it.respond(HTTP_METHOD_NOT_ALLOWED, """{"error":"POST only"}""")
                    return@use
                }

                val body = it.readBoundedBody()
                if (body == null) {
                    it.respond(HTTP_PAYLOAD_TOO_LARGE, """{"error":"body exceeds $MAX_BODY_BYTES bytes"}""")
                    return@use
                }

                val decoded = OtlpJson.decodeTraces(body)
                buffer.addAll(decoded.spans)
                // OTLP defines partial success as a 200 carrying a rejected count, so a batch with
                // one bad span is accepted and the sender is told, rather than the batch failing.
                it.respond(
                    HTTP_OK,
                    """{"partialSuccess":{"rejectedSpans":${decoded.skipped},"errorMessage":""}}""",
                )
            }
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "OTLP export failed", error = t)
            runCatching { exchange.respond(HTTP_SERVER_ERROR, """{"error":"internal"}""") }
        }
    }

    /**
     * The request body, or null when it exceeds the ceiling.
     *
     * Bounded because this reads from a socket into the host's heap. `Content-Length` is a claim
     * by the sender and is checked first as a cheap rejection, but the read is bounded again on
     * the way in, since a chunked request declares no length at all.
     */
    private fun HttpExchange.readBoundedBody(): String? {
        val declared = requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_BODY_BYTES) return null
        val bytes = requestBody.readNBytes(MAX_BODY_BYTES + 1)
        return if (bytes.size > MAX_BODY_BYTES) null else bytes.toString(Charsets.UTF_8)
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    /**
     * Daemon threads, so a running receiver can never hold the JVM open at shutdown.
     *
     * The host's teardown is not guaranteed to reach [stop] on every exit path, and a non daemon
     * pool would turn that into a process that does not exit.
     */
    private fun daemonThreadFactory(): ThreadFactory {
        val counter = AtomicLong()
        return ThreadFactory { runnable ->
            Thread(runnable, "boss-otlp-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    internal companion object {
        /** The OTLP/HTTP default port. Senders need no configuration beyond the protocol. */
        const val DEFAULT_PORT: Int = 4318

        const val TRACES_PATH: String = "/v1/traces"

        /** 8 MB. An OTLP batch is kilobytes; anything this size is a mistake or an attack. */
        const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

        private const val BACKLOG = 16
        private const val HANDLER_THREADS = 2
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_SERVER_ERROR = 500
    }
}
