package ai.rever.boss.mcp.telemetry

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

private val logger = BossLogger.forComponent("TelemetryOtlp")

/** One read at a time, so a large body does not land in the heap in a single allocation. */
private const val READ_CHUNK_BYTES = 64 * 1024

/** How long to wait before re-checking whether owed bytes have arrived. */
private const val POLL_INTERVAL_MS = 20L

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
    /** Overridable so the incomplete-body test does not wait [REQUEST_TIMEOUT_MS] in real time. */
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
) {
    private var server: HttpServer? = null

    private val rejected = AtomicLong()

    private val timedOut = AtomicLong()

    /**
     * Closes abandoned exchanges off the handler pool. See [handle].
     *
     * A fixed pool with an unbounded queue, on purpose: `execute` then never blocks the caller,
     * so a flood of stalled clients costs queued closes rather than a stalled receiver. The worst
     * case is sockets held until the peer goes away, which is strictly better than a receiver
     * that has stopped accepting spans.
     */
    private val reaper: ExecutorService =
        Executors.newFixedThreadPool(REAPER_THREADS, daemonThreadFactory())

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
            ensureRequestDeadline()
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
        // The reaper is deliberately NOT shut down here. start() is callable again after
        // stop(), and a shut-down pool rejects, which would throw out of this finally.
        // newFixedThreadPool creates its threads on demand, so a receiver that never times
        // a request out never starts one, and they are daemon threads regardless.
    }

    fun rejectedCount(): Long = rejected.get()

    /** Requests abandoned because the body never arrived. Read by the incomplete-body test. */
    fun timedOutCount(): Long = timedOut.get()

    /**
     * Two JDK server properties, both needed, both only set when absent.
     *
     * **`drainAmount = 0` is the one that actually frees the handler thread**, and it was found
     * by the incomplete-body test failing with the deadline already in place. Refusing a request
     * whose body never arrived leaves bytes owed on the connection, and `ExchangeImpl.close`
     * drains up to `drainAmount` of them before returning - by reading, from the client that
     * stopped sending. So the handler answered 408 on time and then blocked in `close`, which is
     * the same occupancy by a later door. Zero means the server closes the connection instead of
     * draining it for reuse, which is what should happen to a request that was refused anyway.
     *
     * **`maxReqTime`** is the backstop for the one case `readBoundedBody` cannot bound: a
     * **chunked** sender that goes silent, where there is no declared length to stop at, so the
     * loop is in a blocking `read` and no deadline check can run. The server's own timer closes
     * that exchange.
     *
     * Both are read by `ServerConfig` during **static initialisation**, so they take effect only
     * if nothing in the process created an `HttpServer` first. Nothing else in composeApp does
     * today, but this class cannot guarantee that, and a control that silently no-ops is exactly
     * the kind that reads as present and is not - which is why the fixed-length path enforces
     * its own deadline rather than trusting either property.
     */
    private fun ensureRequestDeadline() {
        // Constant values, not the per-instance timeout: ServerConfig reads these once per JVM,
        // so a per-instance value would mean whichever receiver started first silently decided
        // for every later one. The per-instance deadline is enforced by readBoundedBody.
        if (System.getProperty(MAX_REQ_TIME_PROPERTY).isNullOrBlank()) {
            System.setProperty(MAX_REQ_TIME_PROPERTY, (REQUEST_TIMEOUT_MS / MILLIS_PER_SECOND).toString())
        }
        if (System.getProperty(DRAIN_AMOUNT_PROPERTY).isNullOrBlank()) {
            System.setProperty(DRAIN_AMOUNT_PROPERTY, "0")
        }
    }

    /**
     * Handles one export.
     *
     * The exchange is closed in a `finally` rather than by `use`, because **a timed out request
     * must not be closed on this thread**. `ExchangeImpl.close` drains the bytes the request
     * still owes by reading from the client that stopped sending, so closing a stalled exchange
     * here re-occupies the handler immediately after the 408 - the same starvation by a later
     * door, and exactly what the incomplete-body test caught with the deadline already in place.
     * Those closes go to [reaper], whose queue is unbounded, so no handler thread is ever held by
     * one however many clients stall at once.
     *
     * `drainAmount = 0` makes that close instant, but it is a JVM-global read once during static
     * initialisation and so cannot be depended on - in a shared test JVM another `HttpServer` had
     * already fixed the value. The reaper is what makes this correct without it.
     */
    // One bad export must never take the receiver down, so the catch is deliberately the widest
    // one: anything a decoder, a socket or a handler can throw ends as a 500 and a warning.
    @Suppress("TooGenericExceptionCaught")
    private fun handle(exchange: HttpExchange) {
        var closeElsewhere = false
        try {
            closeElsewhere = serve(exchange)
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "OTLP export failed", error = t)
            runCatching { exchange.respond(HTTP_SERVER_ERROR, """{"error":"internal"}""") }
        } finally {
            if (closeElsewhere) reaper.execute { exchange.closeQuietly() } else exchange.closeQuietly()
        }
    }

    /** Answers one request. Returns true when closing it must not happen on this thread. */
    @Suppress("ReturnCount")
    private fun serve(exchange: HttpExchange): Boolean {
        if (!exchange.remoteAddress.address.isLoopbackAddress) {
            rejected.incrementAndGet()
            exchange.respond(HTTP_FORBIDDEN, """{"error":"loopback only"}""")
            return false
        }
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.respond(HTTP_METHOD_NOT_ALLOWED, """{"error":"POST only"}""")
            return false
        }

        val body =
            when (val read = exchange.readBoundedBody()) {
                is BodyRead.Complete -> {
                    read.text
                }

                BodyRead.TooLarge -> {
                    exchange.respond(HTTP_PAYLOAD_TOO_LARGE, """{"error":"body exceeds $MAX_BODY_BYTES bytes"}""")
                    return true
                }

                BodyRead.Timeout -> {
                    timedOut.incrementAndGet()
                    exchange.respond(HTTP_REQUEST_TIMEOUT, """{"error":"request body incomplete after timeout"}""")
                    return true
                }
            }

        val decoded = OtlpJson.decodeTraces(body)
        buffer.addAll(decoded.spans)
        // OTLP defines partial success as a 200 carrying a rejected count, so a batch with one
        // bad span is accepted and the sender is told, rather than the batch failing.
        exchange.respond(HTTP_OK, """{"partialSuccess":{"rejectedSpans":${decoded.skipped},"errorMessage":""}}""")
        return false
    }

    /**
     * The request body, bounded by **size and by time**.
     *
     * Size, because this reads from a socket into the host's heap. `Content-Length` is a claim by
     * the sender and is checked first as a cheap rejection, but the total is bounded again on the
     * way in, since a chunked request declares no length.
     *
     * Time, because there are only [HANDLER_THREADS] handler threads. `readNBytes` blocked until
     * EOF or the cap, so two local clients that declare a body and then send one byte each
     * occupied both of them **permanently** and trace ingestion stopped. Loopback-only keeps the
     * blast radius at "this receiver stops accepting spans", which is why it is bounded rather
     * than redesigned, but an unbounded wait for a client under no obligation to finish is not
     * defensible on its own terms.
     *
     * The two cases are bounded differently and the split is the whole design; see [readDeclared]
     * and [readUntilEof].
     */
    private fun HttpExchange.readBoundedBody(): BodyRead {
        val declared = requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_BODY_BYTES) return BodyRead.TooLarge

        val deadline = System.nanoTime() + requestTimeoutMs * NANOS_PER_MILLI
        return if (declared != null) readDeclared(declared, deadline) else readUntilEof(deadline)
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

        /**
         * How long one export may take to arrive.
         *
         * Generous for loopback, where a real OTLP batch lands in milliseconds, and short enough
         * that a stalled client cannot hold one of [HANDLER_THREADS] for long. Internal so the
         * incomplete-body test does not have to wait it out in real time.
         */
        internal const val REQUEST_TIMEOUT_MS: Long = 10_000

        private const val MAX_REQ_TIME_PROPERTY = "sun.net.httpserver.maxReqTime"
        private const val DRAIN_AMOUNT_PROPERTY = "sun.net.httpserver.drainAmount"
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val BACKLOG = 16

        /** Internal so the incomplete-body test can stall exactly as many clients as there are threads. */
        internal const val HANDLER_THREADS = 2

        /** Only ever blocked in a close, never in request handling, so two is plenty. */
        private const val REAPER_THREADS = 2
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_SERVER_ERROR = 500
    }
}

/** What came off the socket: a whole body, too much of one, or not enough in time. */
private sealed interface BodyRead {
    data class Complete(
        val text: String,
    ) : BodyRead

    data object TooLarge : BodyRead

    data object Timeout : BodyRead
}

/**
 * A body of known length, read without ever blocking.
 *
 * The count says when to stop, so this never reads past the last owed byte and so never
 * waits on EOF; `available()` says whether a read would block, so the wait for a slow or
 * absent sender is a bounded poll that re-checks the deadline. This is the path every OTLP
 * exporter takes and the path a stalled client must take to be interesting.
 */
// Each return is a distinct terminal outcome of one loop; collapsing them into a single exit
// would need a result variable and a flag, which reads worse than the four cases.
@Suppress("ReturnCount")
private fun HttpExchange.readDeclared(
    declared: Long,
    deadline: Long,
): BodyRead {
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (collected.size().toLong() < declared) {
        if (System.nanoTime() > deadline) return BodyRead.Timeout
        if (requestBody.available() == 0) {
            Thread.sleep(POLL_INTERVAL_MS)
            continue
        }
        val read = requestBody.read(chunk)
        if (read < 0) return BodyRead.Complete(collected.text())
        if (collected.size() + read > OtlpReceiver.MAX_BODY_BYTES) return BodyRead.TooLarge
        collected.write(chunk, 0, read)
    }
    return BodyRead.Complete(collected.text())
}

/**
 * A chunked body, which declares no length.
 *
 * There is no count to stop at, so this blocks in `read` and the deadline is only checked
 * between chunks: a slow drip is caught, a sender that goes silent is not. That residual case
 * belongs to `maxReqTime` in [ensureRequestDeadline], and is recorded in `docs/TELEMETRY.md`.
 */
@Suppress("ReturnCount")
private fun HttpExchange.readUntilEof(deadline: Long): BodyRead {
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (true) {
        if (System.nanoTime() > deadline) return BodyRead.Timeout
        val read = requestBody.read(chunk)
        if (read < 0) return BodyRead.Complete(collected.text())
        if (collected.size() + read > OtlpReceiver.MAX_BODY_BYTES) return BodyRead.TooLarge
        collected.write(chunk, 0, read)
    }
}

/** Closing an exchange can fail on a connection the peer already dropped; that is not news. */
private fun HttpExchange.closeQuietly() {
    runCatching { close() }
}

private fun ByteArrayOutputStream.text(): String = toByteArray().toString(Charsets.UTF_8)
