package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpPolicyRevocationTest {
    private fun provider(onExecute: () -> Unit) =
        object : McpToolProvider {
            override val providerId = "revocation-test"

            override fun tools() =
                listOf(
                    McpToolDefinition(
                        name = "run_command",
                        description = "Disposable test tool",
                        handler =
                            McpToolHandler {
                                onExecute()
                                McpToolResult("ok")
                            },
                    ),
                )
        }

    @Test
    fun `reset invalidates a queued one call approval`() = staleApproval("once")

    @Test
    fun `reset prevents a queued session approval from restoring trust`() = staleApproval("session")

    @Test
    fun `reset prevents a queued persistent approval from restoring ALLOW`() = staleApproval("persistent")

    private fun staleApproval(scope: String) =
        runBlocking {
            withTimeout(5_000) {
                val dir = Files.createTempDirectory("mcp-revoke-queued").toFile()
                try {
                    val file = dir.resolve("policy.json")
                    val engine = McpPolicyEngine(file)
                    val bus = McpApprovalBus()
                    val ledger = McpOperationLedger()
                    var executions = 0
                    val core =
                        McpToolRegistryCore(
                            disabledFile = null,
                            policyEngine = engine,
                            approvalBus = bus,
                            ledger = ledger,
                        )
                    core.registerProvider(provider { executions++ })
                    val first = async { core.invoke("run_command", "{}") }
                    val firstRequest = bus.pendingList.first { it.size == 1 }.single()
                    val second = async { core.invoke("run_command", "{}") }
                    val secondRequest = bus.pendingList.first { it.size == 2 }.first { it.id != firstRequest.id }
                    bus.approve(firstRequest.id, persistPolicy = true)
                    assertFalse(first.await().isError)
                    engine.trustForSession("run_command")
                    assertTrue(engine.setToolPolicy("docker_rm", McpPolicyAction.DENY))

                    // Deliver the old answer, then reset before its invocation coroutine resumes.
                    bus.approve(
                        secondRequest.id,
                        trustForSession = scope == "session",
                        persistPolicy = scope == "persistent",
                    )
                    assertTrue(engine.revokePersistedPolicy("run_command"))
                    assertTrue(second.await().isError, scope)
                    assertEquals(1, executions, scope)
                    assertTrue(engine.sessionTrustedTools.value.isEmpty(), scope)
                    assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"), scope)
                    assertFalse(
                        engine.config.value.rules
                            .containsKey("run_command"),
                        scope,
                    )
                    assertEquals(McpPolicyAction.ASK, McpPolicyEngine(file).policyFor("run_command"), scope)
                    assertEquals(McpPolicyAction.DENY, McpPolicyEngine(file).policyFor("docker_rm"), scope)
                    assertEquals(
                        McpApprovalDisposition.POLICY_DENIED,
                        ledger.recentOperations.value
                            .first()
                            .approvalDisposition,
                    )

                    // A new invocation must actually prompt; reading policyFor alone cannot prove this.
                    val fresh = async { core.invoke("run_command", "{}") }
                    val freshRequest = bus.pendingList.first { it.isNotEmpty() }.single()
                    bus.approve(freshRequest.id)
                    assertFalse(fresh.await().isError)
                    assertEquals(2, executions)
                } finally {
                    dir.deleteRecursively()
                }
            }
        }

    @Test
    fun `failed resets retain existing durable rules and clear only the selected trust`() {
        for (action in listOf(McpPolicyAction.ALLOW, McpPolicyAction.DENY)) {
            val dir = Files.createTempDirectory("mcp-revoke-failed").toFile()
            try {
                val file = dir.resolve("policy.json")
                val engine = McpPolicyEngine(file)
                assertTrue(engine.setToolPolicy("run_command", action))
                assertTrue(engine.setToolPolicy("docker_rm", McpPolicyAction.DENY))
                engine.trustForSession("run_command")
                engine.trustForSession("helm_upgrade")
                val backup = dir.resolve("saved.json")
                Files.move(file.toPath(), backup.toPath())
                assertTrue(file.mkdir())
                file.resolve("blocker").writeText("prevents replacing the directory")

                assertFalse(engine.revokePersistedPolicy("run_command"))
                assertIs<McpPolicyFault.PolicyPersistFailed>(engine.fault.value)
                assertEquals(action, engine.config.value.rules["run_command"])
                assertEquals(action, engine.policyFor("run_command"))
                assertEquals(setOf("helm_upgrade"), engine.sessionTrustedTools.value)
                assertEquals(McpPolicyAction.DENY, engine.policyFor("docker_rm"))
                assertEquals(action, McpPolicyEngine(backup).policyFor("run_command"))
                assertEquals(McpPolicyAction.DENY, McpPolicyEngine(backup).policyFor("docker_rm"))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `reset removes the rule and restores permissive or hardened defaults on reload`() {
        for (default in listOf(McpPolicyAction.ALLOW, McpPolicyAction.DENY)) {
            val dir = Files.createTempDirectory("mcp-reset-default").toFile()
            try {
                val file = dir.resolve("policy.json")
                file.writeText("""{"defaultMutatingAction":"$default","defaultReadOnlyAction":"$default"}""")
                val engine = McpPolicyEngine(file)
                for (tool in listOf("run_command", "git_status")) {
                    assertTrue(engine.setToolPolicy(tool, McpPolicyAction.ALLOW))
                    assertTrue(engine.revokePersistedPolicy(tool))
                    assertFalse(
                        engine.config.value.rules
                            .containsKey(tool),
                    )
                    assertEquals(default, engine.policyFor(tool))
                    val reloaded = McpPolicyEngine(file)
                    assertFalse(
                        reloaded.config.value.rules
                            .containsKey(tool),
                    )
                    assertEquals(default, reloaded.policyFor(tool))
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `stale persistent decisions cannot write after reset even when no DENY remains`() {
        val dir = Files.createTempDirectory("mcp-reset-guard").toFile()
        try {
            val file = dir.resolve("policy.json")
            val engine = McpPolicyEngine(file)
            assertTrue(engine.setToolPolicy("run_command", McpPolicyAction.ALLOW))
            val version = engine.revocationVersion("run_command")
            assertTrue(engine.revokePersistedPolicy("run_command"))
            val resetBytes = file.readText()
            for (action in listOf(McpPolicyAction.ALLOW, McpPolicyAction.DENY)) {
                assertFalse(engine.setToolPolicy("run_command", action, expectedRevocation = version))
                assertEquals(resetBytes, file.readText())
            }
            assertTrue(
                engine.setToolPolicy(
                    "run_command",
                    McpPolicyAction.ALLOW,
                    expectedRevocation = engine.revocationVersion("run_command"),
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
