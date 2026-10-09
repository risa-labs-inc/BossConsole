package ai.rever.boss.daemon

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Optional installable-JAR fixture. Exercises the actual plugin in the host's worker classloader. */
class TerminalDaemonIntegrationTest {
    @Test
    fun `real terminal plugin keeps PTY output across detached clients and drains on disable`() {
        val jarPath = System.getenv("BOSS_TERMINAL_DAEMON_TEST_JAR")
        assumeTrue(jarPath != null, "Provide the locally built terminal plugin JAR")
        val directory = Files.createTempDirectory("boss-terminal-integration").toFile()
        val registry = DaemonServiceRegistry(File(directory, "daemon"))
        try {
            startService(registry, File(checkNotNull(jarPath)), directory)
            val endpoint = registry.dispatch(request("attach", "main")).payload
            val first = attach(endpoint)
            first.first
                .sendText(
                    """{"t":"open","id":"integration","requestId":"first","command":"/bin/sh",
                   |"arguments":["-c","printf DAEMON_INTEGRATION_OK; sleep 30"]}
                    """.trimMargin(),
                    true,
                ).get(5, TimeUnit.SECONDS)
            first.second.get(10, TimeUnit.SECONDS)
            first.first.sendClose(WebSocket.NORMAL_CLOSURE, "detach").get(5, TimeUnit.SECONDS)
            assertEquals(1, registry.count())
            assertEquals(endpoint, registry.dispatch(request("attach", "main")).payload)
            val second = attach(endpoint)
            second.second.get(10, TimeUnit.SECONDS)
            second.first.sendClose(WebSocket.NORMAL_CLOSURE, "detach").get(5, TimeUnit.SECONDS)
            registry.stopPlugin("terminal.fixture")
            assertEquals(0, registry.count())
        } finally {
            registry.close()
            directory.deleteRecursively()
        }
    }

    private fun startService(
        registry: DaemonServiceRegistry,
        jar: File,
        directory: File,
    ) {
        registry.dispatch(
            DaemonRequest(
                "",
                "connect",
                "terminal.fixture",
                "terminals",
                jar.absolutePath,
                sha256(jar),
                "ai.rever.boss.plugin.dynamic.terminaltab.HostedTerminalDaemonService",
                mapOf("settingsDirectory" to File(directory, "settings").absolutePath),
            ),
        )
    }

    private fun request(
        method: String,
        payload: String,
    ) = DaemonRequest("", "request", "terminal.fixture", "terminals", method = method, payload = payload)

    private fun attach(endpoint: String): Pair<WebSocket, CompletableFuture<Unit>> {
        val json = daemonJson.parseToJsonElement(endpoint).jsonObject
        val output = CompletableFuture<Unit>()
        val bytes = StringBuilder()
        val listener =
            object : WebSocket.Listener {
                override fun onBinary(
                    socket: WebSocket,
                    data: ByteBuffer,
                    last: Boolean,
                ): CompletionStage<*>? {
                    val chunk = ByteArray(data.remaining()).also { data.get(it) }
                    bytes.append(chunk.toString(Charsets.UTF_8))
                    if ("DAEMON_INTEGRATION_OK" in bytes) output.complete(Unit)
                    socket.request(1)
                    return null
                }

                override fun onError(
                    socket: WebSocket,
                    error: Throwable,
                ) {
                    output.completeExceptionally(error)
                }
            }
        val socket =
            HttpClient
                .newHttpClient()
                .newWebSocketBuilder()
                .header("X-BossTerm-Token", json.getValue("token").jsonPrimitive.content)
                .buildAsync(URI("ws://127.0.0.1:${json.getValue("port").jsonPrimitive.int}/attach"), listener)
                .get(5, TimeUnit.SECONDS)
        return socket to output
    }
}
