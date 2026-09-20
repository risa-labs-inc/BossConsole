package ai.rever.boss.mcp.telemetry

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OTLP path: buffer bounds, JSON decoding, and a real HTTP round trip.
 *
 * Port 0 is used everywhere so the tests never collide with a real collector on 4318 and never
 * collide with each other when run in parallel.
 */
class OtlpReceiverTest {
    private val receivers = mutableListOf<OtlpReceiver>()

    @AfterTest
    fun tearDown() {
        receivers.forEach { it.stop() }
        receivers.clear()
    }

    private fun receiver(): OtlpReceiver = OtlpReceiver(port = 0).also { receivers += it }

    // ------------------------------------------------------------- buffer

    @Test
    fun `the buffer evicts oldest first when the span ceiling is reached`() {
        val buffer = SpanBuffer(maxSpans = 3)
        for (i in 1..5) buffer.add(span(name = "span-$i"))

        val retained = buffer.query(null, 0, false, 50).map { it.name }

        assertEquals(3, buffer.stats().retained)
        // Newest first, and the two oldest are gone.
        assertEquals(listOf("span-5", "span-4", "span-3"), retained)
        assertEquals(5L, buffer.stats().acceptedTotal, "the accepted tally counts evicted spans too")
    }

    @Test
    fun `the buffer honours its byte ceiling independently of the count`() {
        // Well under the span ceiling, so only the byte limit can be doing the work.
        val buffer = SpanBuffer(maxSpans = 1_000, maxBytes = 2_000)
        repeat(50) { buffer.add(span(name = "s$it", attributes = mapOf("padding" to "x".repeat(400)))) }

        val stats = buffer.stats()
        assertTrue(stats.retained < 50, "the byte ceiling must have evicted something")
        assertTrue(stats.retainedBytes <= 2_000, "retained ${stats.retainedBytes} exceeds the ceiling")
    }

    @Test
    fun `one span larger than the whole ceiling is kept rather than looping forever`() {
        val buffer = SpanBuffer(maxSpans = 100, maxBytes = 10)
        buffer.add(span(name = "huge", attributes = mapOf("a" to "x".repeat(10_000))))

        assertEquals(1, buffer.stats().retained, "evicting to empty would drop every future span too")
    }

    @Test
    fun `queries filter by service, duration and error status`() {
        val buffer = SpanBuffer()
        buffer.add(span(name = "fast", service = "api", durationMs = 5))
        buffer.add(span(name = "slow", service = "api", durationMs = 1_400))
        buffer.add(span(name = "broken", service = "api", durationMs = 900, status = "ERROR"))
        buffer.add(span(name = "other", service = "worker", durationMs = 2_000))

        assertEquals(listOf("broken", "slow"), buffer.query("api", 100, false, 10).map { it.name })
        assertEquals(listOf("broken"), buffer.query(null, 0, true, 10).map { it.name })
        assertEquals(listOf("other"), buffer.query("worker", 0, false, 10).map { it.name })
        assertEquals(2, buffer.query(null, 0, false, 2).size, "limit truncates the oldest end")
    }

    // ------------------------------------------------------------- decoding

    @Test
    fun `a realistic otlp json payload decodes with service, duration, status and attributes`() {
        val decoded = OtlpJson.decodeTraces(EXPORT_PAYLOAD)

        assertEquals(0, decoded.skipped)
        assertEquals(1, decoded.spans.size)
        val span = decoded.spans.single()
        assertEquals("7f8b2c4e1a3d5e9f8a7b6c5d4e3f2a1b", span.traceId)
        assertEquals("POST /api/v1/transform", span.name)
        assertEquals("checkout", span.serviceName, "service.name comes off the resource, not the span")
        assertEquals(1_420L, span.durationMs)
        assertEquals("ERROR", span.status)
        assertEquals("Read timed out waiting on upstream pipeline", span.errorMessage)
        // intValue arrives as a string in OTLP JSON and must survive as one.
        assertEquals("504", span.attributes["http.status_code"])
        assertEquals("http-client", span.attributes["component"])
    }

    @Test
    fun `one malformed span does not lose the rest of the batch`() {
        val decoded = OtlpJson.decodeTraces(MIXED_PAYLOAD)

        assertEquals(1, decoded.spans.size, "the good span must survive")
        assertEquals(1, decoded.skipped, "and the bad one must be counted, not hidden")
        assertEquals("good", decoded.spans.single().name)
    }

    @Test
    fun `junk bodies decode to nothing instead of throwing`() {
        listOf("", "not json at all", "[]", "null", """{"resourceSpans":"wrong type"}""").forEach {
            val decoded = OtlpJson.decodeTraces(it)
            assertTrue(decoded.spans.isEmpty(), "unexpected spans from: $it")
        }
    }

    @Test
    fun `a backwards clock yields zero duration rather than a negative one`() {
        // A negative duration would match every min_duration_ms filter, which is the worst
        // possible failure for a tool whose job is finding slow spans.
        val decoded = OtlpJson.decodeTraces(payload(startNano = 5_000_000_000, endNano = 1_000_000_000))
        assertEquals(0L, decoded.spans.single().durationMs)
    }

    // ------------------------------------------------------------- live HTTP

    @Test
    fun `a posted otlp export is accepted and becomes queryable`() {
        val receiver = receiver()
        val port = receiver.start().getOrThrow()

        val status = post(port, OtlpReceiver.TRACES_PATH, EXPORT_PAYLOAD)

        assertEquals(200, status, "OTLP defines success as 200 with a partialSuccess body")
        val spans = receiver.buffer.query(null, 0, false, 10)
        assertEquals(1, spans.size)
        assertEquals("POST /api/v1/transform", spans.single().name)
    }

    @Test
    fun `the receiver binds loopback only`() {
        val receiver = receiver()
        val port = receiver.start().getOrThrow()

        // The kernel level guarantee: nothing outside loopback can even connect. Asserted by
        // checking the bound address rather than by trying to dial a routable interface, which
        // would depend on the test machine having one.
        val local = InetAddress.getLoopbackAddress()
        Socket().use { it.connect(java.net.InetSocketAddress(local, port), CONNECT_TIMEOUT_MS) }

        assertTrue(local.isLoopbackAddress)
        assertEquals(port, receiver.boundPort)
    }

    @Test
    fun `a non POST request is refused`() {
        val receiver = receiver()
        val port = receiver.start().getOrThrow()

        val connection = open(port, OtlpReceiver.TRACES_PATH).apply { requestMethod = "GET" }
        assertEquals(405, connection.responseCode)
        connection.disconnect()
    }

    @Test
    fun `an oversized body is refused rather than buffered into the heap`() {
        val receiver = receiver()
        val port = receiver.start().getOrThrow()

        val oversized = "x".repeat(OtlpReceiver.MAX_BODY_BYTES + 1024)
        // Either outcome counts as a refusal, and the choice is not ours to make. Rejecting on
        // the declared Content-Length closes the connection while the client is still writing,
        // so the client can see a 413 or an IOException depending on how much of the body made
        // it into socket buffers first. Asserting one specific outcome would be asserting a race.
        val refused =
            runCatching { post(port, OtlpReceiver.TRACES_PATH, oversized) }
                .fold(onSuccess = { it == 413 }, onFailure = { it is java.io.IOException })
        assertTrue(refused, "an oversized body must be refused")

        // The properties that actually matter, and neither depends on the race above.
        assertEquals(0, receiver.buffer.stats().retained, "nothing oversized reached the buffer")
        assertEquals(
            200,
            post(port, OtlpReceiver.TRACES_PATH, EXPORT_PAYLOAD),
            "refusing one request must not take the receiver down",
        )
        assertEquals(1, receiver.buffer.stats().retained)
    }

    @Test
    fun `start is idempotent and stop releases the port`() {
        val receiver = receiver()
        val first = receiver.start().getOrThrow()
        val second = receiver.start().getOrThrow()

        assertEquals(first, second, "a second start must not rebind")
        assertTrue(receiver.isRunning)

        receiver.stop()
        assertFalse(receiver.isRunning)
        assertNull(receiver.boundPort)
    }

    @Test
    fun `a port already in use fails without throwing`() {
        val first = receiver()
        val port = first.start().getOrThrow()

        val clash = OtlpReceiver(port = port).also { receivers += it }
        val result = clash.start()

        // Ordinary condition: a real collector may own 4318. The other tools must keep working.
        assertTrue(result.isFailure, "binding a taken port must report failure")
        assertFalse(clash.isRunning)
        assertNotNull(first.boundPort)
    }

    // ------------------------------------------------------------- helpers

    private fun open(
        port: Int,
        path: String,
    ): HttpURLConnection =
        (URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
        }

    private fun post(
        port: Int,
        path: String,
        body: String,
    ): Int {
        val connection =
            open(port, path).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun span(
        name: String,
        service: String? = "svc",
        durationMs: Long = 10,
        status: String = "UNSET",
        attributes: Map<String, String> = emptyMap(),
    ) = BufferedSpan(
        traceId = "t",
        spanId = "s",
        parentSpanId = null,
        name = name,
        serviceName = service,
        startUnixNano = 0,
        durationMs = durationMs,
        status = status,
        errorMessage = null,
        attributes = attributes,
    )

    private fun payload(
        startNano: Long,
        endNano: Long,
    ) = """
        {"resourceSpans":[{"resource":{"attributes":[]},"scopeSpans":[{"spans":[
          {"traceId":"aa","spanId":"bb","name":"n",
           "startTimeUnixNano":"$startNano","endTimeUnixNano":"$endNano"}
        ]}]}]}
        """.trimIndent()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000

        /** Shaped exactly like a real OTLP/HTTP JSON export. */
        val EXPORT_PAYLOAD =
            """
            {
              "resourceSpans": [{
                "resource": {
                  "attributes": [{ "key": "service.name", "value": { "stringValue": "checkout" } }]
                },
                "scopeSpans": [{
                  "scope": { "name": "io.opentelemetry.okhttp" },
                  "spans": [{
                    "traceId": "7f8b2c4e1a3d5e9f8a7b6c5d4e3f2a1b",
                    "spanId": "3a4b5c6d7e8f9a0b",
                    "name": "POST /api/v1/transform",
                    "kind": 3,
                    "startTimeUnixNano": "1700000000000000000",
                    "endTimeUnixNano": "1700000001420000000",
                    "attributes": [
                      { "key": "http.status_code", "value": { "intValue": "504" } },
                      { "key": "component", "value": { "stringValue": "http-client" } }
                    ],
                    "status": { "code": 2, "message": "Read timed out waiting on upstream pipeline" }
                  }]
                }]
              }]
            }
            """.trimIndent()

        /** One usable span and one missing the required ids. */
        val MIXED_PAYLOAD =
            """
            {"resourceSpans":[{"scopeSpans":[{"spans":[
              {"name":"no ids at all"},
              {"traceId":"aa","spanId":"bb","name":"good",
               "startTimeUnixNano":"0","endTimeUnixNano":"1000000"}
            ]}]}]}
            """.trimIndent()
    }
}
