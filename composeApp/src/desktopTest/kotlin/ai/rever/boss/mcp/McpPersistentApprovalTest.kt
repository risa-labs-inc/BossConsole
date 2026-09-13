package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises durable operator choices, failed writes and revoked access at the registry boundary. */
class McpPersistentApprovalTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(
        name: String,
        handler: McpToolHandler = McpToolHandler { McpToolResult("ok:$name") },
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)

    @Test
    fun `guarded update refuses a deny without changing persisted bytes`() {
        val dir = Files.createTempDirectory("mcp-policy-deny").toFile()
        try {
            val file = dir.resolve("policy.json")
            val engine = McpPolicyEngine(file)
            assertTrue(engine.setToolPolicy("run_command", McpPolicyAction.DENY))
            val before = file.readText()
            assertFalse(engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, preserveDeny = true))
            assertEquals(before, file.readText())
            assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `persistent choices survive a fresh engine reading the same file`() =
        runBlocking {
            for (allow in listOf(true, false)) {
                val dir = Files.createTempDirectory("mcp-policy-reload").toFile()
                try {
                    val file = dir.resolve("policy.json")
                    val bus = McpApprovalBus()
                    val engine = McpPolicyEngine(file)
                    val core = McpToolRegistryCore(disabledFile = null, policyEngine = engine, approvalBus = bus)
                    core.registerProvider(provider("p", echoTool("run_command")))
                    val call = async { core.invoke("run_command", "{}") }
                    val request = bus.pendingList.first { it.isNotEmpty() }.first()
                    if (allow) {
                        bus.approve(request.id, persistPolicy = true)
                    } else {
                        bus.deny(request.id, persistPolicy = true)
                    }
                    assertEquals(!allow, call.await().isError)
                    val expected = if (allow) McpPolicyAction.ALLOW else McpPolicyAction.DENY
                    assertEquals(expected, McpPolicyEngine(file).policyFor("run_command"))
                    assertTrue(engine.sessionTrustedTools.value.isEmpty())
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

    @Test
    fun `failed persistent choices withhold execution and never claim a saved rule`() =
        runBlocking {
            for (allow in listOf(true, false)) {
                val dir = Files.createTempDirectory("mcp-policy-failure").toFile()
                try {
                    val parent = dir.resolve("parent")
                    val file = parent.resolve("policy.json")
                    val bus = McpApprovalBus()
                    val engine = McpPolicyEngine(file)
                    parent.writeText("blocks directory creation")
                    val ledger = McpOperationLedger()
                    var calls = 0
                    val core =
                        McpToolRegistryCore(
                            disabledFile = null,
                            policyEngine = engine,
                            approvalBus = bus,
                            ledger = ledger,
                        )
                    core.registerProvider(
                        provider(
                            "p",
                            echoTool(
                                "run_command",
                                handler =
                                    McpToolHandler {
                                        calls++
                                        McpToolResult("unexpected")
                                    },
                            ),
                        ),
                    )
                    val call = async { core.invoke("run_command", "{}") }
                    val request = bus.pendingList.first { it.isNotEmpty() }.first()
                    if (allow) {
                        bus.approve(request.id, persistPolicy = true)
                    } else {
                        bus.deny(request.id, persistPolicy = true)
                    }
                    assertTrue(call.await().isError)
                    assertEquals(0, calls)
                    assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
                    assertTrue(engine.fault.value is McpPolicyFault.PolicyPersistFailed)
                    assertEquals(
                        McpApprovalDisposition.POLICY_PERSIST_FAILED,
                        ledger.recentOperations.value
                            .single()
                            .approvalDisposition,
                    )
                    assertFalse(file.exists())
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

    @Test
    fun `persistent approval cannot change policy after access is revoked`() =
        runBlocking {
            for (change in listOf("deny", "disable", "unload")) {
                val bus = McpApprovalBus()
                val engine = McpPolicyEngine()
                val ledger = McpOperationLedger()
                var calls = 0
                val core =
                    McpToolRegistryCore(
                        disabledFile = null,
                        policyEngine = engine,
                        approvalBus = bus,
                        ledger = ledger,
                    )
                core.registerProvider(
                    provider(
                        "p",
                        echoTool(
                            "run_command",
                            handler =
                                McpToolHandler {
                                    calls++
                                    McpToolResult("unexpected")
                                },
                        ),
                    ),
                )
                val call = async { core.invoke("run_command", "{}") }
                val request = bus.pendingList.first { it.isNotEmpty() }.first()
                when (change) {
                    "deny" -> engine.setToolPolicy("run_command", McpPolicyAction.DENY)
                    "disable" -> core.setToolEnabled("run_command", false)
                    "unload" -> core.unregisterProvider("p")
                }
                bus.approve(request.id, persistPolicy = true)
                assertTrue(call.await().isError, change)
                assertEquals(0, calls, change)
                val expected = if (change == "deny") McpPolicyAction.DENY else McpPolicyAction.ASK
                assertEquals(expected, engine.policyFor("run_command"), change)
                assertEquals(
                    McpApprovalDisposition.POLICY_DENIED,
                    ledger.recentOperations.value
                        .single()
                        .approvalDisposition,
                )
            }
        }
}
