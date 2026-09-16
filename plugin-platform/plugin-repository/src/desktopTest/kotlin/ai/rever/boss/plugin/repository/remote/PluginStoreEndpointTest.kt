package ai.rever.boss.plugin.repository.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginStoreEndpointTest {
    private val requests = LinkedBlockingQueue<String>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.add("${exchange.requestMethod} ${exchange.requestURI}")
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }

    init {
        PluginStoreConfig.initialize(
            "http://127.0.0.1:${server.address.port}/prefix/functions/v1",
            "test",
            "test-token",
        )
    }

    @AfterTest
    fun cleanup() {
        server.stop(0)
        PluginStoreConfig.clear()
    }

    @Test
    fun `raw identifiers stay in one path segment on the actual HTTP request`() =
        runBlocking {
            val cases =
                mapOf(
                    "ai.example.plugin" to "ai.example.plugin",
                    "a/b?c#d%e+f" to "a%2Fb%3Fc%23d%25e%2Bf",
                    "%2Fadmin%2Fdelete" to "%252Fadmin%252Fdelete",
                    "x y" to "x%20y",
                )
            for ((input, encoded) in cases) {
                assertNull(PluginStoreClient.getPlugin(input))
                assertEquals("GET /prefix/functions/v1/plugin-store/$encoded", requests.poll())
            }
        }

    @Test
    fun `version and authenticated mutation endpoints encode every dynamic segment`() =
        runBlocking {
            val id = "a/b?x#y%z+q"
            val encoded = "a%2Fb%3Fx%23y%25z%2Bq"
            val calls: List<Pair<String, suspend () -> Unit>> =
                listOf(
                    "GET $encoded/download" to { PluginStoreClient.getDownloadUrl(id) },
                    "GET $encoded/download/1.2.3-rc%2Bbuild%2Fx" to {
                        PluginStoreClient.getDownloadUrl(id, "1.2.3-rc+build/x")
                    },
                    "POST $encoded/rate" to { PluginStoreClient.ratePlugin(id, 5) },
                    "POST $encoded/version" to { PluginStoreClient.publishVersion(id, PublishVersionRequest("1.0.0")) },
                    "POST admin/$encoded/publish" to { PluginStoreClient.setPluginPublished(id, true) },
                    "POST admin/$encoded/verify" to { PluginStoreClient.setPluginVerified(id, true) },
                    "DELETE admin/$encoded" to { PluginStoreClient.deletePlugin(id) },
                    "DELETE api-keys/$encoded" to { PluginStoreClient.revokeApiKey(id) },
                )
            for ((expected, call) in calls) {
                assertFailsWith<PluginStoreException> { call() }
                val (method, path) = expected.split(' ', limit = 2)
                assertEquals("$method /prefix/functions/v1/plugin-store/$path", requests.poll())
            }
        }

    @Test
    fun `empty and dot segments fail before sending a request`() =
        runBlocking {
            for (input in listOf("", ".", "..")) {
                assertFailsWith<IllegalArgumentException> { PluginStoreClient.getPlugin(input) }
                assertFailsWith<IllegalArgumentException> { PluginStoreClient.getDownloadUrl("valid", input) }
            }
            assertTrue(requests.isEmpty())
        }
}
