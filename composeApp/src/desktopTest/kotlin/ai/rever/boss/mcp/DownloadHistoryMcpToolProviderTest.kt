package ai.rever.boss.mcp

import ai.rever.boss.downloads.DownloadHistoryManager
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [DownloadHistoryMcpToolProvider], exercising each tool through its registered
 * handler. The provider reads/writes the [DownloadHistoryManager] singleton, redirected to a
 * hermetic temp file per test.
 */
@ResourceLock("DownloadHistoryManager")
class DownloadHistoryMcpToolProviderTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("dl-mcp-test-").toFile()
        tempFile = File(tempDir, "download-history.json")
        DownloadHistoryManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        DownloadHistoryManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    private suspend fun call(
        name: String,
        args: McpToolArgs = McpToolArgs(emptyMap(), "{}"),
    ): McpToolResult {
        val tool = DownloadHistoryMcpToolProvider.tools().firstOrNull { it.name == name }
        requireNotNull(tool) { "tool $name not found" }
        return tool.handler.call(args)
    }

    private fun json(result: McpToolResult) = Json.parseToJsonElement(result.text).jsonObject

    @Test
    fun `only the list tool is read-only`() {
        val readOnly = DownloadHistoryMcpToolProvider.tools().associate { it.name to it.readOnly }
        assertEquals(true, readOnly["downloads_history_list"])
        assertEquals(false, readOnly["downloads_history_clear"])
    }

    @Test
    fun `sensitive history requires approval despite its read-only declaration`() {
        val tool = DownloadHistoryMcpToolProvider.tools().first { it.name == "downloads_history_list" }
        val engine = McpPolicyEngine(policyFile = null)
        assertEquals(McpPolicyAction.ASK, engine.policyFor(tool.name, "boss-downloads", tool.readOnly))
        assertTrue(McpMutatingToolCatalog.isMutating(tool.name, tool.readOnly))
    }

    @Test
    fun `denied history invocation never returns a signed download URL`() =
        runBlocking {
            DownloadHistoryManager.record("https://example.test/file?token=private-token", "/private/download.zip")
            val bus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = McpPolicyEngine(policyFile = null),
                    approvalBus = bus,
                )
            core.registerProvider(DownloadHistoryMcpToolProvider)
            withTimeout(5000L) {
                val invocation = async { core.invoke("downloads_history_list", "{}") }
                val request = bus.pendingList.first { it.isNotEmpty() }.first()
                assertEquals("downloads_history_list", request.toolName)
                assertFalse(invocation.isCompleted)
                bus.deny(request.id, "Private history")
                val result = invocation.await()
                assertTrue(result.isError)
                assertFalse(result.text.contains("private-token"))
                assertFalse(result.text.contains("/private/download.zip"))
            }
        }

    @Test
    fun `list returns recorded downloads newest first`() =
        runBlocking {
            DownloadHistoryManager.record("https://ex.com/a.zip", "/d/a.zip")
            DownloadHistoryManager.record("https://ex.com/b.pdf", "/d/b.pdf")

            val entries = json(call("downloads_history_list"))["downloads"]!!.jsonArray
            assertEquals(2, entries.size)
            assertEquals(
                "b.pdf",
                entries
                    .first()
                    .jsonObject["fileName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `clear empties the history`() =
        runBlocking {
            DownloadHistoryManager.record("u", "/d/a.txt")
            val result = json(call("downloads_history_clear"))
            assertEquals(1, result["removed"]!!.jsonPrimitive.content.toInt())
            assertTrue(DownloadHistoryManager.downloads.value.isEmpty())
        }

    @Test
    fun `a record with no size omits the sizeBytes field`() =
        runBlocking {
            DownloadHistoryManager.record("u", "/d/a.txt")
            val entry = json(call("downloads_history_list"))["downloads"]!!.jsonArray.first().jsonObject
            assertNull(entry["sizeBytes"])
            assertFalse(entry["fileName"]!!.jsonPrimitive.content.isEmpty())
        }

    @Test
    fun `list pages a bounded number of entries`() =
        runBlocking {
            repeat(55) { DownloadHistoryManager.record("u", "/d/$it.txt") }
            val first = json(call("downloads_history_list"))
            assertEquals(55, first["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(50, first["downloads"]!!.jsonArray.size)

            val next =
                json(
                    call(
                        "downloads_history_list",
                        McpToolArgs(mapOf("offset" to 50, "limit" to 10), "{}"),
                    ),
                )
            assertEquals(5, next["downloads"]!!.jsonArray.size)
            assertEquals(
                "4.txt",
                next["downloads"]!!
                    .jsonArray
                    .first()
                    .jsonObject["fileName"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `clear reports a disk failure and keeps the in-memory history`() =
        runBlocking {
            DownloadHistoryManager.record("u", "/d/a.txt")
            assertTrue(tempFile.delete())
            assertTrue(tempFile.mkdir())

            assertTrue(call("downloads_history_clear").isError)
            assertEquals(1, DownloadHistoryManager.downloads.value.size)
        }

    @Test
    fun `a corrupt history is reported until clear replaces it`() =
        runBlocking {
            tempFile.writeText("{ broken")
            DownloadHistoryManager.resetForTesting(tempFile)
            assertTrue(call("downloads_history_list").isError)
            assertFalse(call("downloads_history_clear").isError)
            assertEquals(0, json(call("downloads_history_list"))["total"]!!.jsonPrimitive.content.toInt())
        }
}
