package ai.rever.boss.orchestrator

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpAiRepairClientLimitsTest {
    @Test
    fun `normal gateway proposal remains usable`() =
        runBlocking {
            withResponse("""{"explanation":"fixed","configChanges":{"MODE":"safe"}}""") { client ->
                val proposal = client.proposeConfigFix("service", "failure", null, "message")
                assertEquals(mapOf("MODE" to "safe"), proposal?.configChanges)
            }
        }

    @Test
    fun `oversized chunked response is refused before parsing`() =
        runBlocking {
            withResponse("""{"explanation":"${"x".repeat(1_048_576)}","configChanges":{}}""") { client ->
                assertNull(client.proposeConfigFix("service", "failure", null, "message"))
            }
        }

    private suspend fun withResponse(
        body: String,
        action: suspend (HttpAiRepairClient) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { it.write(body.toByteArray()) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            action(
                HttpAiRepairClient(
                    AiRepairConfig(
                        AiRepairWire.OPENAI,
                        "http://127.0.0.1:${server.address.port}/",
                        "test-key",
                        "model",
                        "prompt",
                    ),
                ),
            )
        } finally {
            server.stop(0)
        }
    }
}
