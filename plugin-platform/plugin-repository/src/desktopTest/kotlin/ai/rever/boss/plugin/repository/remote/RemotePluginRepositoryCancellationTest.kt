package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.logging.LogLevel
import ai.rever.boss.plugin.repository.PluginSearchFilter
import com.sun.net.httpserver.HttpServer
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
 * A caller's cancellation of a store call must not be recorded as a network failure.
 *
 * `runCatching` catches Throwable, so a cancelled request used to come back as `Result.failure`
 * carrying a `CancellationException`, and the repository logged it at ERROR under NETWORK as a fault
 * that never happened. Dismissing the dependency dialog while the store was slow produced one such
 * line per in-flight lookup. [RemotePluginRepository.downloadPlugin] had the fix; the other five
 * store calls did not.
 *
 * Tested against a real local server that receives the request and then holds it, so the
 * cancellation lands inside the ktor call the way it does in production, not in a fake. The
 * observable is the log, because the caller's own `await` throws on a cancelled job either way.
 * A positive control proves the assertion can see an ERROR when there is one.
 *
 * `ratePlugin` gets the same rule but is not driven here: it refuses without an access token before
 * any request is sent, so a held server would never see it.
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
