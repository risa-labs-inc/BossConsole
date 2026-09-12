package ai.rever.boss.plugin.repository.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * That cancelling a health check does not report the store as down.
 *
 * [PluginStoreClient.checkHealth] answers `false` from `catch (_: Exception)`, and on the JVM a
 * `CancellationException` is an `Exception`, so a caller cancelled mid-request was told the store is
 * unavailable. That answer outlives the cancellation: `RemotePluginRepository.isAvailable` and
 * `PluginStoreSetup`'s readiness check both key off it, so closing a dialog could mark a healthy
 * store offline. It is the same family as the six store calls in #531, at the one site that returns a
 * bare `Boolean` rather than a `Result`, which is why it needed its own change.
 *
 * Driven against a real local server that receives the request and holds it, so the cancellation
 * lands inside the ktor call rather than in a fake. A positive control proves the assertion can see
 * a real "down" answer.
 */
class PluginStoreHealthCancellationTest {
    private lateinit var server: HttpServer
    private lateinit var received: CountDownLatch
    private lateinit var release: CountDownLatch
    private var status = 200

    @BeforeTest
    fun setup() {
        received = CountDownLatch(1)
        release = CountDownLatch(1)
        status = 200
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                // The JDK dispatches handlers on its own thread when no executor is passed, so
                // blocking here does not stall the test's own threads.
                createContext("/") { exchange ->
                    received.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    val body = "{}".toByteArray()
                    exchange.sendResponseHeaders(status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        PluginStoreConfig.initialize("http://127.0.0.1:${server.address.port}/functions/v1", "test-anon-key")
    }

    @AfterTest
    fun cleanup() {
        release.countDown()
        server.stop(0)
        PluginStoreConfig.clear()
    }

    @Test
    fun `cancelling a health check propagates instead of answering unhealthy`() {
        // Block body, not `= runBlocking { ... }`: the last expression is `assertIs`, which returns
        // the value it checked, so an expression body would give this method a non-Unit return type
        // and JUnit would silently not run it at all.
        runBlocking {
            // Whichever half actually happens: a returned Boolean, or a thrown exception.
            val outcome = CompletableDeferred<Any>()

            val job =
                launch(Dispatchers.IO) {
                    // runCatching, not a try/catch: it catches Throwable (which is the point - the
                    // answer may be a value or a CancellationException) without an explicit generic
                    // catch clause. complete() does not suspend, so both halves record even from a
                    // cancelled coroutine.
                    val answer = runCatching { PluginStoreClient.checkHealth() }
                    outcome.complete(answer.getOrElse { it })
                }

            assertTrue(received.await(10, TimeUnit.SECONDS), "the store never received the request")
            job.cancelAndJoin()
            release.countDown()

            assertIs<CancellationException>(
                outcome.await(),
                "a cancelled health check answered about the store instead of propagating",
            )
        }
    }

    @Test
    fun `a store that is really down still answers false`() =
        runBlocking {
            // Positive control: without this the assertion above could pass against a client that
            // never reaches the network at all.
            status = 503
            release.countDown()

            val healthy = PluginStoreClient.checkHealth()

            assertTrue(received.await(10, TimeUnit.SECONDS), "the store never received the request")
            assertEquals(false, healthy, "a 503 must still read as unhealthy")
        }
}
