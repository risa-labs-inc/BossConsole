package ai.rever.boss.mcp.coordination

import ai.rever.boss.mcp.McpApprovalBus
import ai.rever.boss.mcp.McpToolRegistryCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * The tools end to end, through a real `McpToolRegistryCore` and a real file.
 *
 * This is the suite that proves two agents actually see each other, which is the whole claim the
 * feature makes.
 */
class CoordinationToolContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var boardFile: File
    private var now = 5_000_000L

    /**
     * A standing operator who always says yes.
     *
     * `agent_claim` and `agent_release` declare `readOnly = false`, so the host's policy engine
     * puts them behind ASK. That is the behaviour this feature wants, and it means an unattended
     * test hangs for the approval timeout on every mutating call. Rather than configure the gate
     * away, these tests answer it, so the real approval path is exercised on every call.
     * `the approval gate really does fire` below proves the gate is still there.
     */
    private val approvalBus = McpApprovalBus(defaultTimeoutMs = APPROVAL_TIMEOUT_MS)
    private val approver = CoroutineScope(Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        val dir = createTempDirectory("agent-claims-test").toFile()
        boardFile = File(dir, AgentClaimStore.FILE_NAME)
        CoordinationMcpToolProvider.store = AgentClaimStore { boardFile }
        CoordinationMcpToolProvider.clock = { now }
        approver.launch {
            approvalBus.pendingList.collect { pending -> pending.forEach { approvalBus.approve(it.id) } }
        }
    }

    @AfterTest
    fun tearDown() {
        approver.cancel()
        CoordinationMcpToolProvider.store = AgentClaimStore()
        CoordinationMcpToolProvider.clock = { System.currentTimeMillis() }
        boardFile.parentFile?.deleteRecursively()
    }

    private fun core() =
        McpToolRegistryCore(disabledFile = null, approvalBus = approvalBus)
            .apply { registerProvider(CoordinationMcpToolProvider) }

    private fun call(
        tool: String,
        args: String,
    ): JsonObject {
        val result = runBlocking { core().invoke(tool, args) }
        assertFalse(result.isError, result.text)
        return json.parseToJsonElement(result.text) as JsonObject
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.content.toInt()

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 300L
    }

    // ------------------------------------------------------------- shape

    @Test
    fun `three tools register under the coordination provider with snake case names`() {
        val core = core()
        assertEquals(
            setOf("agent_claim", "agent_peers", "agent_release"),
            core.allTools.value
                .map { it.definition.name }
                .toSet(),
        )
        assertTrue(core.allTools.value.all { it.providerId == "boss-coordination" })
        CoordinationMcpToolProvider.tools().forEach {
            assertFalse(it.name.startsWith("mcp__"))
            assertTrue(it.name.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun `writing to the shared board is mutating and reading it is not`() {
        val byName = CoordinationMcpToolProvider.tools().associateBy { it.name }

        // Other agents read this board, so writing to it is a side effect on shared state and
        // inherits the host's ASK default rather than being silently allowed.
        assertFalse(byName.getValue("agent_claim").readOnly)
        assertFalse(byName.getValue("agent_release").readOnly)
        assertTrue(byName.getValue("agent_peers").readOnly)
    }

    @Test
    fun `every tool description states that this is advisory`() {
        // The agent reading the description is the one that needs to know what the answer is
        // worth, so the honesty cannot live only in the docs.
        CoordinationMcpToolProvider.tools().forEach {
            assertTrue(
                it.description.contains("advisory", ignoreCase = true) ||
                    it.description.contains("does NOT lock") ||
                    it.description.contains("expire"),
                "${it.name} does not say what it is worth",
            )
        }
    }

    // ------------------------------------------------------------- behaviour

    @Test
    fun `two agents on the same file see each other`() {
        // The headline claim of the whole feature.
        call("agent_claim", """{"agent_id":"claude-1","task":"refactor auth","files":["src/Auth.kt"]}""")

        val second =
            call("agent_claim", """{"agent_id":"codex-2","task":"add logging","files":["src/Auth.kt","src/Log.kt"]}""")

        assertEquals(1, second.int("overlap_count"))
        val overlap =
            second
                .getValue("overlaps")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("claude-1", overlap.getValue("peer_agent_id").jsonPrimitive.content)
        assertEquals("refactor auth", overlap.getValue("peer_task").jsonPrimitive.content)
        assertEquals(
            "src/Auth.kt",
            overlap
                .getValue("shared_files")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertContains(second.getValue("overlap_note").jsonPrimitive.content, "conflict")
    }

    @Test
    fun `agents on different files do not collide`() {
        call("agent_claim", """{"agent_id":"a","task":"t","files":["src/A.kt"]}""")
        val b = call("agent_claim", """{"agent_id":"b","task":"t","files":["src/B.kt"]}""")

        assertEquals(0, b.int("overlap_count"))
    }

    @Test
    fun `peers lists the others and flags overlap against your own claim`() {
        call("agent_claim", """{"agent_id":"me","task":"mine","files":["shared.kt"]}""")
        call("agent_claim", """{"agent_id":"them","task":"theirs","files":["shared.kt"]}""")

        val peers = call("agent_peers", """{"agent_id":"me"}""")

        assertEquals(1, peers.int("peer_count"), "peers excludes you")
        assertEquals(
            "them",
            peers
                .getValue("peers")
                .jsonArray
                .single()
                .jsonObject
                .getValue("agent_id")
                .jsonPrimitive.content,
        )
        assertEquals(1, peers.int("overlap_count"))
    }

    @Test
    fun `peers without an id just lists everyone and flags nothing`() {
        call("agent_claim", """{"agent_id":"a","task":"t","files":["x.kt"]}""")
        call("agent_claim", """{"agent_id":"b","task":"t","files":["x.kt"]}""")

        val peers = call("agent_peers", "{}")

        assertEquals(2, peers.int("peer_count"))
        assertEquals(0, peers.int("overlap_count"))
    }

    @Test
    fun `asking about yourself before claiming says so rather than staying silent`() {
        val peers = call("agent_peers", """{"agent_id":"stranger"}""")

        assertEquals("true", peers.getValue("your_claim_missing").jsonPrimitive.content)
        assertContains(peers.getValue("your_claim_note").jsonPrimitive.content, "agent_claim")
    }

    @Test
    fun `releasing removes you from what other agents see`() {
        call("agent_claim", """{"agent_id":"leaver","task":"t","files":["x.kt"]}""")
        call("agent_claim", """{"agent_id":"stayer","task":"t","files":["x.kt"]}""")

        val released = call("agent_release", """{"agent_id":"leaver"}""")
        assertEquals("true", released.getValue("released").jsonPrimitive.content)

        val peers = call("agent_peers", """{"agent_id":"stayer"}""")
        assertEquals(0, peers.int("peer_count"))
        assertEquals(0, peers.int("overlap_count"))
    }

    @Test
    fun `releasing a claim that was never made is reported, not an error`() {
        val released = call("agent_release", """{"agent_id":"nobody"}""")

        assertEquals("false", released.getValue("released").jsonPrimitive.content)
        assertContains(released.getValue("note").jsonPrimitive.content, "expired")
    }

    @Test
    fun `an expired claim stops being visible without anyone cleaning up`() {
        call("agent_claim", """{"agent_id":"ghost","task":"t","files":["x.kt"],"ttl_minutes":1}""")
        assertEquals(1, call("agent_peers", "{}").int("peer_count"))

        now += 120_000

        assertEquals(0, call("agent_peers", "{}").int("peer_count"), "a crashed agent frees its claim by itself")
    }

    @Test
    fun `a claim survives across separate tool calls, so it is really shared`() {
        // Each call builds a fresh registry, so this passing means the board is on disk rather
        // than in one provider instance's memory.
        call("agent_claim", """{"agent_id":"persist","task":"t","files":["x.kt"]}""")

        assertTrue(boardFile.isFile, "the board must be a real file other processes can read")
        assertEquals(1, call("agent_peers", "{}").int("peer_count"))
    }

    // ------------------------------------------------------------- input handling

    @Test
    fun `files arrive as a json array and also as a comma separated string`() {
        // McpToolArgs exposes only scalars, so the array is parsed out of raw. A model told
        // "files" sometimes sends a string instead, and refusing that would fail a call over an
        // encoding detail rather than anything the agent meant.
        val asArray = call("agent_claim", """{"agent_id":"a1","task":"t","files":["x.kt","y.kt"]}""")
        assertEquals(2, asArray.int("files_claimed"))

        val asString = call("agent_claim", """{"agent_id":"a2","task":"t","files":"p.kt, q.kt"}""")
        assertEquals(2, asString.int("files_claimed"))
    }

    @Test
    fun `a claim with no files is allowed and collides with nobody`() {
        call("agent_claim", """{"agent_id":"busy","task":"thinking"}""")
        val peers = call("agent_peers", """{"agent_id":"busy"}""")

        assertEquals(0, peers.int("overlap_count"))
    }

    @Test
    fun `a bad agent id or a missing task is refused with a usable message`() {
        val core = core()
        listOf(
            """{"task":"t"}""",
            """{"agent_id":"has space","task":"t"}""",
            """{"agent_id":"../etc/passwd","task":"t"}""",
        ).forEach {
            val result = runBlocking { core.invoke("agent_claim", it) }
            assertTrue(result.isError, "expected refusal for $it")
            assertContains(result.text, "agent_id")
        }

        val noTask = runBlocking { core.invoke("agent_claim", """{"agent_id":"ok"}""") }
        assertTrue(noTask.isError)
        assertContains(noTask.text, "task is required")
    }

    @Test
    fun `an unreadable board is reported rather than failing the call`() {
        boardFile.parentFile?.mkdirs()
        boardFile.writeText("this is not json")

        val peers = call("agent_peers", "{}")

        assertEquals("true", peers.getValue("board_unreadable").jsonPrimitive.content)
        assertEquals(0, peers.int("peer_count"))
    }

    @Test
    fun `a corrupt board is replaced by the next claim rather than blocking forever`() {
        boardFile.parentFile?.mkdirs()
        boardFile.writeText("{ broken")

        call("agent_claim", """{"agent_id":"recover","task":"t","files":["x.kt"]}""")

        assertEquals(1, call("agent_peers", "{}").int("peer_count"))
    }

    @Test
    fun `the approval gate really does fire on a write, with nobody to answer it`() {
        // The counterpart to the auto-approver above. Writing to a board other agents read is a
        // side effect on shared state, so it must reach the operator rather than run silently.
        val unattended = McpApprovalBus(defaultTimeoutMs = SHORT_TIMEOUT_MS)
        val core =
            McpToolRegistryCore(disabledFile = null, approvalBus = unattended)
                .apply { registerProvider(CoordinationMcpToolProvider) }

        val result = runBlocking { core.invoke("agent_claim", """{"agent_id":"nobody","task":"t"}""") }

        assertTrue(result.isError, "a mutating tool must not run itself when no operator answers")
        assertEquals(0, runBlocking { core.invoke("agent_peers", "{}") }.let { peersCount(it.text) })
    }

    private fun peersCount(raw: String): Int = (json.parseToJsonElement(raw) as JsonObject).int("peer_count")

    @Test
    fun `the host kill switch removes a coordination tool and refuses to invoke it`() {
        val core = core()
        core.setToolEnabled("agent_claim", false)

        assertFalse(core.tools.value.any { it.definition.name == "agent_claim" })
        assertTrue(runBlocking { core.invoke("agent_claim", """{"agent_id":"a","task":"t"}""") }.isError)
    }
}
