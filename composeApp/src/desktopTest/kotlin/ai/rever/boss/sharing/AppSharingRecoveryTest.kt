package ai.rever.boss.sharing

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppSharingRecoveryTest {
    @Test
    fun `health checks recover beyond five seconds and three failures without changing session identity`(): Unit =
        runTest {
            var attempts = 0
            var interrupted = 0
            val expected = buildJsonObject { put("generation", "same-generation") }
            val response =
                recoverAppSharingRequest(onInterrupted = { interrupted++ }) {
                    if (++attempts < 5) throw IOException("synthetic reset")
                    expected
                }
            assertEquals(expected, response)
            assertEquals(5, attempts)
            assertEquals(1, interrupted)
            assertTrue(testScheduler.currentTime > 5000)
        }

    @Test
    fun `denials and malformed requests are never retried`(): Unit =
        runTest {
            for (status in listOf(400, 401, 403, 404, 409)) {
                var attempts = 0
                val error =
                    assertFailsWith<AppSharingException> {
                        recoverAppSharingRequest {
                            attempts++
                            throw AppSharingException("unauthorized", status)
                        }
                    }
                assertEquals(status, error.status)
                assertEquals(1, attempts)
            }
            assertFalse(transientAppSharingFailure(AppSharingException("account_changed")))
            assertFalse(transientAppSharingFailure(IllegalArgumentException("invalid descriptor")))
        }

    @Test
    fun `a denial after an outage stops recovery immediately`(): Unit =
        runTest {
            var attempts = 0
            val error =
                assertFailsWith<AppSharingException> {
                    recoverAppSharingRequest {
                        if (++attempts == 1) throw IOException("reset")
                        throw AppSharingException("account_changed", 401)
                    }
                }
            assertEquals("account_changed", error.reason)
            assertEquals(2, attempts)
            assertEquals(1000L, testScheduler.currentTime)
        }

    @Test
    fun `hung retry is bounded and owner cancellation prevents another request`(): Unit =
        runTest {
            var attempts = 0
            val error =
                assertFailsWith<AppSharingException> {
                    recoverAppSharingRequest {
                        if (++attempts == 1) throw AppSharingException("upstream_unavailable", 503)
                        awaitCancellation()
                    }
                }
            assertEquals("recovery_timed_out", error.reason)
            assertEquals(30_000L, testScheduler.currentTime)
            attempts = 0
            val owner =
                launch {
                    recoverAppSharingRequest {
                        attempts++
                        throw IOException("reset")
                    }
                }
            runCurrent()
            owner.cancelAndJoin()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, attempts)
        }

    @Test
    fun `backend retains upstream HTTP status without surfacing its response body`(): Unit =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            var status = 503
            server.createContext("/") { exchange ->
                exchange.requestBody.close()
                val body = "{\"error\":\"upstream_unavailable\",\"private\":\"fixture-only\"}".toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val backend =
                    AppSharingBackend(
                        endpoint = { "http://127.0.0.1:${server.address.port}/" },
                        identity = { "fixture-owner" to "fixture-jwt" },
                        anonKey = { "fixture-anon" },
                    )
                for (code in listOf(401, 403, 409, 429, 503)) {
                    status = code
                    val error =
                        assertFailsWith<AppSharingException> { backend.call("fixture-owner", buildJsonObject {}) }
                    assertEquals(code, error.status)
                    assertEquals("upstream_unavailable", error.reason)
                    assertEquals(code in listOf(429, 503), transientAppSharingFailure(error))
                }
            } finally {
                server.stop(0)
            }
        }
}
