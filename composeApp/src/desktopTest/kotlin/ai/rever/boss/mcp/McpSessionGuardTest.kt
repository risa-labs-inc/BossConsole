package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpSessionGuardTest {
    private fun provider(vararg definitions: McpToolDefinition) =
        object : McpToolProvider {
            override val providerId = "session-guard-test"

            override fun tools() = definitions.toList()
        }

    private fun tool(
        name: String,
        readOnly: Boolean,
        handler: McpToolHandler,
    ) = McpToolDefinition(
        name = name,
        description = "session guard test tool",
        readOnly = readOnly,
        handler = handler,
    )

    @Test
    fun `pause blocks mutating declarations and names while read-only inspection remains available`() {
        val guard = McpSessionGuard()
        guard.setPaused(true)

        assertNull(guard.tryAcquire("innocent_sync", declaredReadOnly = false))
        assertNull(guard.tryAcquire("k8s_delete", declaredReadOnly = true), "a mutating name must win")
        val readPermit = assertNotNull(guard.tryAcquire("workspace_status", declaredReadOnly = true))
        readPermit.close()

        assertEquals(
            McpSessionGuardState(
                mutatingActionsPaused = true,
                inFlightMutatingActions = 0,
                blockedMutatingActions = 2,
            ),
            guard.state.value,
        )
    }

    @Test
    fun `pause brakes high-risk names the mutating catalog does not list`() {
        val guard = McpSessionGuard()
        guard.setPaused(true)

        // docker_run is CRITICAL by name in the risk evaluator but matches no
        // KNOWN_MUTATING_TOOLS entry or suffix, and an honest provider may declare it
        // readOnly = true. Classification is the shared predicate, so the paused bucket
        // matches the policy engine's ASK bucket and the brake still wins.
        assertNull(guard.tryAcquire("docker_run", declaredReadOnly = true))
        assertNull(guard.tryAcquire("secret_create", declaredReadOnly = true))
        assertTrue(guard.blockIfPaused("k8s_use_context", declaredReadOnly = true))

        val state = guard.state.value
        assertEquals(0, state.inFlightMutatingActions)
        assertEquals(3L, state.blockedMutatingActions)
    }

    @Test
    fun `pause reports admitted work as finishing and rejects later admissions`() {
        val guard = McpSessionGuard()
        val admitted = assertNotNull(guard.tryAcquire("env_sync", declaredReadOnly = false))
        assertEquals(1, guard.state.value.inFlightMutatingActions)

        guard.setPaused(true)
        assertEquals(1, guard.state.value.inFlightMutatingActions)
        assertNull(guard.tryAcquire("env_sync", declaredReadOnly = false))
        assertEquals(1L, guard.state.value.blockedMutatingActions)

        admitted.close()
        admitted.close()
        assertEquals(0, guard.state.value.inFlightMutatingActions, "permit cleanup must be idempotent")

        guard.setPaused(false)
        assertNotNull(guard.tryAcquire("env_sync", declaredReadOnly = false)).close()
    }

    @Test
    fun `paused registry audits a mutating refusal and still executes a read-only tool`() =
        runBlocking {
            var mutationCalls = 0
            var readCalls = 0
            val ledger = McpOperationLedger(ledgerFile = null)
            val core = McpToolRegistryCore(disabledFile = null, ledger = ledger)
            core.registerProvider(
                provider(
                    tool("env_sync", readOnly = false) {
                        mutationCalls++
                        McpToolResult("mutated")
                    },
                    tool("workspace_status", readOnly = true) {
                        readCalls++
                        McpToolResult("ready")
                    },
                ),
            )
            core.setMutatingActionsPaused(true)

            val blocked = core.invoke("env_sync", "{}")
            val read = core.invoke("workspace_status", "{}")

            assertTrue(blocked.isError)
            assertTrue(blocked.text.contains("paused by the operator"))
            assertEquals(0, mutationCalls)
            assertFalse(read.isError)
            assertEquals(1, readCalls)
            assertEquals(1L, core.sessionGuardState.value.blockedMutatingActions)
            assertEquals(
                McpApprovalDisposition.SESSION_GUARD_BLOCKED,
                ledger.recentOperations.value
                    .last()
                    .approvalDisposition,
            )
        }

    @Test
    fun `pause after an approval is queued wins at the final execution seam`() =
        runBlocking {
            var handlerCalled = false
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5_000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    tool("env_sync", readOnly = false) {
                        handlerCalled = true
                        McpToolResult("synced")
                    },
                ),
            )

            val invocation = async { core.invoke("env_sync", "{}") }
            val request = approvalBus.pendingList.first { it.isNotEmpty() }.single()
            core.setMutatingActionsPaused(true)
            approvalBus.approve(request.id)

            val result = invocation.await()
            assertTrue(result.isError)
            assertFalse(handlerCalled)
            assertEquals(
                McpApprovalDisposition.SESSION_GUARD_BLOCKED,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `pause does not cancel a mutating handler that already owns a permit`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val policy = McpPolicyEngine(policyFile = null)
            policy.setToolPolicy("env_sync", McpPolicyAction.ALLOW)
            val core = McpToolRegistryCore(disabledFile = null, policyEngine = policy)
            core.registerProvider(
                provider(
                    tool("env_sync", readOnly = false) {
                        started.complete(Unit)
                        finish.await()
                        McpToolResult("done")
                    },
                ),
            )

            val running = async { core.invoke("env_sync", "{}") }
            started.await()
            core.setMutatingActionsPaused(true)

            assertEquals(1, core.sessionGuardState.value.inFlightMutatingActions)
            assertTrue(core.invoke("env_sync", "{}").isError)
            finish.complete(Unit)
            assertFalse(running.await().isError)
            assertEquals(0, core.sessionGuardState.value.inFlightMutatingActions)
        }
}
