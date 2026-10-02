package ai.rever.boss.mcp.session

import ai.rever.boss.mcp.McpToolRegistryCore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The provider's contract with the host: naming, the permission split, the kill switch, and the
 * promise that the ungated tool never discloses argument values or error text.
 */
class SessionToolContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val temps = mutableListOf<File>()

    private val secretArg = "SECRET-ARG-abc123"
    private val secretError = "SECRET-ERROR-xyz789"

    @BeforeTest
    fun setUp() {
        // A fixture ledger holding one stuck loop, one governance denial, and secrets in both the
        // arguments and the error text, so the disclosure guard has something real to catch.
        val dir = createTempDirectory("session-contract").toFile()
        val file = File(dir, "mcp-calls.jsonl")
        temps += file

        val lines =
            (1..3).map {
                """{"id":"fail-$it","timestamp":${2_000 + it},"toolName":"codebase_tree","providerId":"p",""" +
                    """"policyApplied":"ALLOW","approvalDisposition":"AUTO_ALLOWED","durationMs":3,""" +
                    """"isError":true,"sanitizedArgs":{"token":"$secretArg"},"errorSnippet":"$secretError"}"""
            } +
                listOf(
                    """{"id":"denied-1","timestamp":2100,"toolName":"k8s_delete","providerId":"p",""" +
                        """"policyApplied":"DENY","approvalDisposition":"POLICY_DENIED","durationMs":0,""" +
                        """"isError":true,"sanitizedArgs":{},"errorSnippet":"denied"}""",
                )

        file.writeText(lines.joinToString("\n"))

        SessionMcpToolProvider.ledgerFileProvider = { file }
        SessionMcpToolProvider.inMemoryProvider = { emptyList() }
        SessionMcpToolProvider.sessionStartProvider = { 0L }
    }

    @AfterTest
    fun tearDown() {
        SessionMcpToolProvider.ledgerFileProvider = { File("mcp-calls.jsonl") }
        SessionMcpToolProvider.inMemoryProvider = { emptyList() }
        SessionMcpToolProvider.sessionStartProvider = { 0L }
        temps.forEach { it.parentFile?.deleteRecursively() }
        temps.clear()
    }

    private fun core() = McpToolRegistryCore(disabledFile = null).apply { registerProvider(SessionMcpToolProvider) }

    // ------------------------------------------------------------- shape

    @Test
    fun `both tools register under the session provider with unprefixed snake case names`() {
        val core = core()
        // The gated tool is invisible to a logged-out user, so check the full inventory.
        val names =
            core.allTools.value
                .map { it.definition.name }
                .toSet()

        assertEquals(setOf("session_review", "session_inspect_calls"), names)
        assertTrue(core.allTools.value.all { it.providerId == "boss-session" })
        SessionMcpToolProvider.tools().forEach {
            assertFalse(it.name.startsWith("mcp__"), "${it.name} must not carry the client prefix")
            assertTrue(it.name.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun `both tools are read only because reading a log changes nothing`() {
        assertTrue(SessionMcpToolProvider.tools().all { it.readOnly })
    }

    @Test
    fun `only the detail tool is permission gated`() {
        val byName = SessionMcpToolProvider.tools().associateBy { it.name }

        assertTrue(
            byName.getValue("session_review").requiredPermissions.isEmpty(),
            "loop detection needs no sensitive content, so gating it would only make it useless",
        )
        assertEquals(
            listOf(SessionMcpToolProvider.ACTIVITY_PERMISSION),
            byName.getValue("session_inspect_calls").requiredPermissions,
        )
    }

    @Test
    fun `the gated tool is hidden without the permission and appears with it`() {
        val core = core()

        assertFalse(core.tools.value.any { it.definition.name == "session_inspect_calls" })
        assertTrue(
            core.tools.value.any { it.definition.name == "session_review" },
            "the ungated half must stay usable",
        )

        core.updateAccess(isAdmin = false, permissions = setOf(SessionMcpToolProvider.ACTIVITY_PERMISSION))
        assertTrue(core.tools.value.any { it.definition.name == "session_inspect_calls" })
    }

    @Test
    fun `an admin sees the gated tool without holding the permission`() {
        val core = core()
        core.updateAccess(isAdmin = true, permissions = emptySet())

        assertTrue(core.tools.value.any { it.definition.name == "session_inspect_calls" })
    }

    // ------------------------------------------------------------- behaviour

    @Test
    fun `review names the stuck loop and the calls that never ran`() {
        val payload = invokeOk("session_review", "{}")

        val loop =
            payload
                .getValue("loops")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("REPEATING_FAILURE", loop.getValue("kind").jsonPrimitive.content)
        assertEquals("codebase_tree", loop.getValue("tool_name").jsonPrimitive.content)
        assertEquals("3", loop.getValue("occurrences").jsonPrimitive.content)

        assertEquals("1", payload.getValue("blocked_calls").jsonPrimitive.content)
        val blocked =
            payload
                .getValue("blocked")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("k8s_delete", blocked.getValue("tool_name").jsonPrimitive.content)
        assertEquals("POLICY_DENIED", blocked.getValue("disposition").jsonPrimitive.content)
        // The agent must be told these did not run, or it reasons from an outcome that never was.
        assertContains(payload.getValue("blocked_note").jsonPrimitive.content, "did NOT run")
    }

    @Test
    fun `review never discloses argument values or error text`() {
        // The headline safety property of the ungated tool, asserted on the wire payload rather
        // than on the report object, because the payload is what actually reaches the model.
        val raw = invokeRaw("session_review", "{}")

        assertFalse(raw.contains(secretArg), "an argument value reached the ungated payload")
        assertFalse(raw.contains(secretError), "error text reached the ungated payload")
    }

    @Test
    fun `the gated tool does disclose that detail, which is why it is gated`() {
        val payload = invokeOk("session_inspect_calls", """{"errors_only":true,"limit":5}""")
        val calls = payload.getValue("calls").jsonArray

        assertTrue(calls.isNotEmpty())
        val raw = payload.toString()
        assertTrue(raw.contains(secretArg), "the detail tool exists precisely to show arguments")
        assertTrue(raw.contains(secretError))
    }

    @Test
    fun `inspect filters by tool name and honours its limit`() {
        val payload = invokeOk("session_inspect_calls", """{"tool_name":"codebase_tree","limit":2}""")
        val calls = payload.getValue("calls").jsonArray

        assertEquals(2, calls.size)
        assertTrue(
            calls.all {
                it.jsonObject
                    .getValue("tool_name")
                    .jsonPrimitive.content == "codebase_tree"
            },
        )
    }

    @Test
    fun `inspect reports whether each call actually executed`() {
        val payload = invokeOk("session_inspect_calls", """{"tool_name":"k8s_delete"}""")
        val call =
            payload
                .getValue("calls")
                .jsonArray
                .single()
                .jsonObject

        assertEquals("BLOCKED", call.getValue("executed").jsonPrimitive.content)
    }

    @Test
    fun `a nonsense window is refused rather than silently widened`() {
        val core = core()
        listOf("""{"since_minutes":0}""", """{"since_minutes":-5}""", """{"since_minutes":"soon"}""").forEach {
            val result = runBlocking { core.invoke("session_review", it) }
            assertTrue(result.isError, "expected refusal for $it")
        }
    }

    @Test
    fun `an empty session is answered with an explanation rather than an error`() {
        SessionMcpToolProvider.sessionStartProvider = { System.currentTimeMillis() + 60_000 }

        val payload = invokeOk("session_review", "{}")

        assertEquals("0", payload.getValue("total_calls").jsonPrimitive.content)
        assertContains(payload.getValue("note").jsonPrimitive.content, "No MCP calls recorded")
    }

    @Test
    fun `the host kill switch removes a session tool and refuses to invoke it`() {
        val core = core()
        core.setToolEnabled("session_review", false)

        assertFalse(core.tools.value.any { it.definition.name == "session_review" })
        assertTrue(runBlocking { core.invoke("session_review", "{}") }.isError)
    }

    // ------------------------------------------------------------- helpers

    private fun invokeRaw(
        tool: String,
        args: String,
    ): String {
        val core = core()
        // Admin, so the gated tool is reachable from the same helper.
        core.updateAccess(isAdmin = true, permissions = emptySet())
        val result = runBlocking { core.invoke(tool, args) }
        assertFalse(result.isError, result.text)
        return result.text
    }

    private fun invokeOk(
        tool: String,
        args: String,
    ): JsonObject = json.parseToJsonElement(invokeRaw(tool, args)) as JsonObject
}
