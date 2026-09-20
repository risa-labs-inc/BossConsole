package ai.rever.boss.mcp.telemetry

import ai.rever.boss.mcp.McpToolRegistryCore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The provider's contract with the host: names, schemas, mutation declarations, and its behaviour
 * under the host's kill switch.
 *
 * Nothing here attaches to anything. This is about what the registry sees when the provider is
 * plugged in, which is the part a governance reviewer cares about.
 */
class TelemetryToolContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Tools that operate on the whole machine or the span buffer rather than one process. */
    private val noPidTools = setOf("telemetry_list_targets", "telemetry_query_traces")

    private val expectedNames =
        setOf(
            "telemetry_list_targets",
            "telemetry_profile_cpu",
            "telemetry_thread_state",
            "telemetry_capture_heap",
            "telemetry_query_traces",
            "telemetry_explain_bottleneck",
        )

    @Test
    fun `every telemetry tool registers and is exposed to a logged-out local agent`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(TelemetryMcpToolProvider)

        assertEquals(
            expectedNames,
            core.tools.value
                .map { it.definition.name }
                .toSet(),
        )
        assertTrue(core.tools.value.all { it.providerId == "boss-telemetry" })
    }

    @Test
    fun `tool names carry no client prefix and are snake case`() {
        // Clients type mcp__boss__<name>; the registry stores the bare name. Baking the prefix in
        // would surface as mcp__boss__mcp__boss__telemetry_*.
        TelemetryMcpToolProvider.tools().forEach { tool ->
            assertFalse(tool.name.startsWith("mcp__"), "${tool.name} must not carry the client prefix")
            assertTrue(
                tool.name.matches(Regex("[a-z][a-z0-9_]*")),
                "${tool.name} is not snake_case",
            )
            assertTrue(tool.name.startsWith("telemetry_"), "${tool.name} must be namespaced")
        }
    }

    @Test
    fun `only capture_heap declares itself mutating because force_gc pauses the target`() {
        val byName = TelemetryMcpToolProvider.tools().associateBy { it.name }

        // force_gc makes another process stop and collect. That is a side effect on something the
        // agent does not own, so it must inherit the host's ASK default rather than ALLOW.
        assertFalse(byName.getValue("telemetry_capture_heap").readOnly)

        listOf(
            "telemetry_list_targets",
            "telemetry_profile_cpu",
            "telemetry_thread_state",
            "telemetry_query_traces",
            "telemetry_explain_bottleneck",
        ).forEach { assertTrue(byName.getValue(it).readOnly, "$it only reads and must declare readOnly") }
    }

    @Test
    fun `no tool declares a permission it would be the only surface to require`() {
        // Matches WorkspaceMcpToolProvider's documented posture: the MCP server is loopback only,
        // and a permission nobody holds would hide these behind a wall nothing else has.
        TelemetryMcpToolProvider.tools().forEach {
            assertTrue(it.requiredPermissions.isEmpty(), "${it.name} unexpectedly gates on a permission")
            assertFalse(it.requiresAdmin, "${it.name} unexpectedly requires admin")
        }
    }

    @Test
    fun `every input schema is valid json and requires pid where a target is needed`() {
        TelemetryMcpToolProvider.tools().forEach { tool ->
            val schema = json.parseToJsonElement(tool.inputSchema).jsonObject
            assertEquals("object", schema["type"]?.jsonPrimitive?.content, "${tool.name} schema type")
            assertTrue(schema.containsKey("properties"), "${tool.name} declares no properties")

            if (tool.name !in noPidTools) {
                val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                assertContains(required, "pid", "${tool.name} must require a pid")
            }
        }
    }

    @Test
    fun `the host kill switch removes a telemetry tool and refuses to invoke it`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(TelemetryMcpToolProvider)

        core.setToolEnabled("telemetry_profile_cpu", false)

        assertFalse(
            core.tools.value.any { it.definition.name == "telemetry_profile_cpu" },
            "a disabled tool must leave the exposed set",
        )
        val result = runBlocking { core.invoke("telemetry_profile_cpu", """{"pid":1}""") }
        assertTrue(result.isError, "a disabled tool must not execute")

        // Re-enabling restores it, so the switch is a toggle rather than a one way removal.
        core.setToolEnabled("telemetry_profile_cpu", true)
        assertTrue(core.tools.value.any { it.definition.name == "telemetry_profile_cpu" })
    }

    @Test
    fun `a missing pid is refused without attaching to anything`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(TelemetryMcpToolProvider)

        val result = runBlocking { core.invoke("telemetry_thread_state", "{}") }

        assertTrue(result.isError)
        assertContains(result.text, "pid is required")
    }

    @Test
    fun `an unknown filter is refused rather than silently treated as all`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(TelemetryMcpToolProvider)

        val result = runBlocking { core.invoke("telemetry_list_targets", """{"filter_type":"erlang"}""") }

        assertTrue(result.isError, "an unknown filter must not quietly return every process")
        assertContains(result.text, "erlang")
    }

    @Test
    fun `listing targets returns parseable json that always includes this process`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(TelemetryMcpToolProvider)

        val result = runBlocking { core.invoke("telemetry_list_targets", """{"filter_type":"jvm"}""") }

        assertFalse(result.isError, result.text)
        val payload = json.parseToJsonElement(result.text) as JsonObject
        val targets = payload.getValue("targets").jsonArray
        assertTrue(targets.isNotEmpty(), "the test JVM itself is a jvm target")
        assertTrue(
            targets.all { it.jsonObject["runtime"]?.jsonPrimitive?.content == "jvm" },
            "the jvm filter must not leak other runtimes",
        )
        assertTrue(
            targets.any { it.jsonObject["is_boss_host"]?.jsonPrimitive?.content == "true" },
            "the process running the tool must appear and be marked",
        )
    }
}
