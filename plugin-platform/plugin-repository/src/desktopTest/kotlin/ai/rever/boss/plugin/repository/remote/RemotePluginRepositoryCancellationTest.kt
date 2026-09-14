package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.logging.LogLevel
import ai.rever.boss.plugin.repository.PluginSearchFilter
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A caller's cancellation of a store call must not be recorded as a network failure, and it
 * must not be swallowed into a returned `Result.failure`.
 *
 * `runCatching` catches Throwable, so a cancelled request used to come back as `Result.failure`
 * carrying a `CancellationException`, and the repository logged it at ERROR under NETWORK as a
 * fault that never happened. Dismissing the dependency dialog while the store was slow produced
 * one such line per in-flight lookup, and the caller saw a failed store where there was only its
 * own cancellation. [RemotePluginRepository.downloadPlugin] had the rule; the other five store
 * calls did not.
 *
 * Every store call is tested both ways, against a real local server that receives the request
 * and then holds it, so the cancellation lands inside the ktor call the way it does in
 * production, not in a fake:
 *
 * 1. Log absence - the ERROR line for a store failure must not appear. A positive control
 *    proves the assertion can see an ERROR when there is one.
 * 2. Propagation - the repository method must throw the caller's [CancellationException] out,
 *    not return a failed [Result]. The log assertion cannot see this half: a swallowed
 *    cancellation and a correctly propagated one are both "no ERROR line".
 *
 * `ratePlugin` is the only store call that refuses before a request is sent - it requires an
 * access token - so its two tests install a structurally valid one first; see those tests.
 */
class RemotePluginRepositoryCancellationTest {
    private lateinit var server: HttpServer
    private lateinit var received: CountDownLatch
    private lateinit var release: CountDownLatch
    private var status = 200

    @BeforeTest
    fun setup() {
        received = CountDownLatch(1)
        release = CountDownLatch(1)
        status = 200
        // No executor is passed, so the JDK dispatches handlers on its own single thread. The
        // latch design depends on that thread: the handler runs off the test's threads (the test
        // blocks on `received`, the handler on `release`), and with exactly one request per test
        // nothing can queue behind the held one.
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                // One context for every store path: signal that the request arrived, then hold it
                // until the test lets go, so the cancellation is guaranteed to land mid-request.
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
        BossLogger.clearLogs()
    }

    @AfterTest
    fun cleanup() {
        release.countDown()
        server.stop(0)
        PluginStoreConfig.clear()
    }

    private fun networkErrors(message: String) =
        BossLogger
            .getRecentLogs(category = LogCategory.NETWORK, minLevel = LogLevel.ERROR)
            .filter { it.message == message }

    /** Starts [call], waits until the server holds it, cancels, and asserts nothing was logged as [message]. */
    private fun assertCancellationIsNotLogged(
        message: String,
        call: suspend (RemotePluginRepository) -> Unit,
    ) = runBlocking {
        val repository = RemotePluginRepository()
        // Off runBlocking's own thread: the latch wait below is a blocking Java call, and a child
        // launched on the same single-threaded loop would never start before it.
        val job = launch(Dispatchers.IO) { call(repository) }

        assertTrue(received.await(10, TimeUnit.SECONDS), "the store never received the request")
        job.cancelAndJoin()
        release.countDown()

        val spurious = networkErrors(message)
        assertTrue(spurious.isEmpty(), "a cancellation was logged as a network failure: $spurious")
    }

    /**
     * Asserts that cancelling [call] propagates the caller's [CancellationException] out of the
     * repository method, rather than the method swallowing it and returning a `Result.failure`
     * that reads as a store fault.
     *
     * The deferred records whichever half actually happened: the call returning normally (the
     * cancellation was swallowed into the returned value) or it throwing. Both branches complete
     * before the job ends, so after [cancelAndJoin] the deferred is always complete.
     */
    private fun assertCancellationPropagates(call: suspend (RemotePluginRepository) -> Any?) {
        runBlocking {
            val repository = RemotePluginRepository()
            val thrown = CompletableDeferred<Throwable>()
            val job =
                launch(Dispatchers.IO) {
                    // runCatching, the same idiom the production methods use: success means the
                    // cancellation was swallowed into the returned value, failure means it propagated.
                    val result = runCatching { call(repository) }
                    thrown.complete(
                        if (result.isSuccess) {
                            IllegalStateException(
                                "the store call returned normally instead of propagating the cancellation",
                            )
                        } else {
                            result.exceptionOrNull()!!
                        },
                    )
                }

            assertTrue(received.await(10, TimeUnit.SECONDS), "the store never received the request")
            job.cancelAndJoin()
            release.countDown()

            val error = thrown.await()
            assertTrue(error is CancellationException, "cancellation did not propagate out of the store call: $error")
        }
    }

    private fun installAccessToken() {
        // ratePlugin is the only store call that refuses before a request is sent (it requires
        // an access token), so without one the held server would never see it. "a.b.c" is a
        // three-part token with no admin claim: structurally valid for the client's token check
        // and for decodeIsAdmin, and enough to get the request on the wire.
        PluginStoreConfig.initialize(
            "http://127.0.0.1:${server.address.port}/functions/v1",
            "test-anon-key",
            accessToken = "a.b.c",
        )
    }

    @Test
    fun `cancelling getPlugin is not logged as a failure`() =
        assertCancellationIsNotLogged("Failed to get remote plugin") { it.getPlugin("some.plugin") }

    @Test
    fun `cancelling getPluginVersions is not logged as a failure`() =
        assertCancellationIsNotLogged("Failed to get plugin versions") { it.getPluginVersions("some.plugin") }

    @Test
    fun `cancelling listPlugins is not logged as a failure`() =
        assertCancellationIsNotLogged("Failed to list remote plugins") { it.listPlugins() }

    @Test
    fun `cancelling searchPlugins is not logged as a failure`() =
        assertCancellationIsNotLogged("Failed to search remote plugins") {
            it.searchPlugins(PluginSearchFilter(query = "x"))
        }

    @Test
    fun `cancelling ratePlugin is not logged as a failure`() {
        installAccessToken()
        assertCancellationIsNotLogged("Failed to rate plugin") { it.ratePlugin("some.plugin", 5) }
    }

    @Test
    fun `a cancelled getPlugin propagates the caller's cancellation`() {
        assertCancellationPropagates { it.getPlugin("some.plugin") }
    }

    @Test
    fun `a cancelled getPluginVersions propagates the caller's cancellation`() {
        assertCancellationPropagates { it.getPluginVersions("some.plugin") }
    }

    @Test
    fun `a cancelled listPlugins propagates the caller's cancellation`() {
        assertCancellationPropagates { it.listPlugins() }
    }

    @Test
    fun `a cancelled searchPlugins propagates the caller's cancellation`() {
        assertCancellationPropagates { it.searchPlugins(PluginSearchFilter(query = "x")) }
    }

    @Test
    fun `a cancelled ratePlugin propagates the caller's cancellation`() {
        installAccessToken()
        assertCancellationPropagates { it.ratePlugin("some.plugin", 5) }
    }

    @Test
    fun `a real failure is still logged`() =
        runBlocking {
            // Positive control: the assertions above are only meaningful if this one sees the line.
            status = 500
            release.countDown()

            val result = RemotePluginRepository().getPlugin("some.plugin")

            assertTrue(result.isFailure, "a 500 must still be a failure")
            assertTrue(networkErrors("Failed to get remote plugin").isNotEmpty(), "a real failure must still be logged")
        }
}
