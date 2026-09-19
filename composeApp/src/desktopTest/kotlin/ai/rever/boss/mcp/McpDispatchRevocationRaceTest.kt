package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Revocation has to reach a call that was authorized but had not started executing, and has to
 * leave alone one that already had.
 *
 * `docs/release-notes/v9.5.16.md` recorded the gap this closes: the provider-wide write already
 * rechecked revocation and DENY under the policy lock, "but calls already authorized to execute
 * are still not cancelled". Consulting the epoch at the approval boundary was never the whole
 * job. The invocation dispatched after that lock was released and after a coroutine resumption,
 * so a reset landing in the window between the check and the act withdrew an authorization the
 * call ran through anyway.
 *
 * The seam is now [McpPolicyEngine.beginDispatch]: taken under the same lock a reset takes, and
 * adjacent to the handler with nothing suspending in between. These tests pin both directions -
 * a claim that loses the race is refused, and a claim that wins is never retracted - plus the
 * structural property that makes the first one true, which no black-box test can observe
 * because a correct implementation has no window to park a coroutine in.
 */
class McpDispatchRevocationRaceTest {
    private fun provider(handler: McpToolHandler) =
        object : McpToolProvider {
            override val providerId = "revocation-race"

            override fun tools() =
                listOf(
                    McpToolDefinition(
                        name = "run_command",
                        description = "Disposable test tool",
                        handler = handler,
                    ),
                )
        }

    private fun core(
        engine: McpPolicyEngine,
        handler: McpToolHandler,
        ledger: McpOperationLedger,
        bus: McpApprovalBus = McpApprovalBus(),
    ): McpToolRegistryCore =
        McpToolRegistryCore(
            disabledFile = null,
            policyEngine = engine,
            approvalBus = bus,
            ledger = ledger,
        ).also { it.registerProvider(provider(handler)) }

    /**
     * The reset loses this race, and that is the intended outcome: the claim was taken first, so
     * the call had already dispatched. Cancelling it here is what would leave a partial write
     * behind, which is why the fix withdraws authorization from calls that have not started
     * rather than reaching into the ones that have.
     */
    @Test
    fun `a reset landing while the tool is already executing does not cancel the call`() =
        runTest {
            val engine = McpPolicyEngine(policyFile = null)
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            val ledger = McpOperationLedger(ledgerFile = null)
            var resetLanded = false
            val registry =
                core(
                    engine = engine,
                    ledger = ledger,
                    handler =
                        McpToolHandler {
                            // The operator resets this tool from "Persisted MCP policies" while
                            // the handler is mid-flight.
                            resetLanded = engine.revokePersistedPolicy("run_command")
                            McpToolResult("completed")
                        },
                )

            val result = registry.invoke("run_command", "{}")

            assertTrue(resetLanded, "the reset itself must have taken effect")
            assertFalse(
                result.isError,
                "a call that had already dispatched must not be cancelled by a later reset",
            )
            assertEquals("completed", result.text)
            assertEquals(
                McpApprovalDisposition.AUTO_ALLOWED,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
            // The reset is real, not a no-op the call happened to survive: the next call asks.
            assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "revocation-race"))
        }

    /**
     * The reset wins this race. The call captured its epoch, was authorized by the operator, and
     * had not dispatched when the reset landed, so it never starts.
     *
     * Virtual time is what makes the interleaving deterministic rather than a coin flip: the
     * approval completes the deferred but cannot resume the invocation until this test yields,
     * so the reset below is guaranteed to land in the window.
     */
    @Test
    fun `a reset landing after the approval but before the dispatch withholds the call`() =
        runTest {
            val engine = McpPolicyEngine(policyFile = null)
            engine.setToolPolicy("run_command", McpPolicyAction.ASK)
            val bus = McpApprovalBus()
            val ledger = McpOperationLedger(ledgerFile = null)
            var executions = 0
            val registry =
                core(
                    engine = engine,
                    ledger = ledger,
                    bus = bus,
                    handler =
                        McpToolHandler {
                            executions++
                            McpToolResult("ran")
                        },
                )

            val call = async { registry.invoke("run_command", "{}") }
            val request = bus.pendingList.first { it.isNotEmpty() }.single()
            bus.approve(request.id)
            assertTrue(engine.revokePersistedPolicy("run_command"))

            val result = call.await()

            assertTrue(result.isError, "a call the reset overtook must not run")
            assertEquals(0, executions, "the handler must never be reached")
            assertEquals(
                McpApprovalDisposition.POLICY_DENIED,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    /**
     * The epoch is the whole gate, in both directions: the epoch a call captured stops working
     * the moment a reset supersedes it, and a reset invalidates *older* epochs rather than the
     * tool, so a call that captures the new one dispatches normally.
     */
    @Test
    fun `a claim is refused once the epoch it captured is superseded, and only that epoch`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
        val captured = engine.revocationVersion("run_command", "revocation-race")

        assertTrue(
            engine.beginDispatch("run_command", captured, grantSessionTrust = false, providerId = "revocation-race"),
        )
        assertTrue(engine.revokePersistedPolicy("run_command"))

        assertFalse(
            engine.beginDispatch("run_command", captured, grantSessionTrust = false, providerId = "revocation-race"),
            "the epoch this call captured is stale, so it must not dispatch",
        )
        assertTrue(
            engine.beginDispatch(
                "run_command",
                engine.revocationVersion("run_command", "revocation-race"),
                grantSessionTrust = false,
                providerId = "revocation-race",
            ),
            "a reset supersedes older epochs; it does not withdraw the tool",
        )
    }

    /** The provider-wide reset is the path the release note named, and it gates the same way. */
    @Test
    fun `a provider reset reaches a call the same way a per-tool reset does`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.setProviderPolicy("revocation-race", McpPolicyAction.ALLOW)
        val captured = engine.revocationVersion("run_command", "revocation-race")

        assertTrue(engine.revokeProviderPolicy("revocation-race"))

        assertFalse(
            engine.beginDispatch("run_command", captured, grantSessionTrust = false, providerId = "revocation-race"),
        )
    }

    /**
     * The property the three tests above cannot reach from outside: with the seam correct there
     * is no suspension point between the claim and the execution, so there is no window to park
     * a coroutine in and nothing for a black-box test to interleave. Removing the `withContext`
     * hop that used to sit here is the fix, so this reads the source and says so - the same way
     * [ai.rever.boss.WindowsArm64SourceIsolationTest] pins a build property it cannot run.
     */
    @Test
    fun `the dispatch claim is taken with nothing suspending between it and the handler`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val source = File(root, REGISTRY_SOURCE).readText()

        val seam =
            assertNotNull(
                dispatchSeam(source),
                "the dispatch seam moved: expected exactly one '$CLAIM_ANCHOR' and one " +
                    "'$EXECUTE_ANCHOR' in $REGISTRY_SOURCE, in that order",
            )

        assertTrue(
            seam.problems.isEmpty(),
            "The dispatch claim and the execution it authorizes must be one atomic step. " +
                "Anything that suspends between them is a window a reset can land in with the " +
                "call already past the claim, and the call runs anyway: ${seam.problems}",
        )
    }

    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }

    companion object {
        private const val REGISTRY_SOURCE =
            "composeApp/src/commonMain/kotlin/ai/rever/boss/mcp/McpToolRegistryImpl.kt"

        /**
         * Includes the `->` deliberately. Pinning the branch arrow means the claim is a `when`
         * condition, so a later `withContext(Dispatchers.IO) { beginDispatch(...) }` would stop
         * matching this anchor rather than slipping past the region check below.
         */
        private const val CLAIM_ANCHOR = "!beginDispatch(tool, revocation, disposition) ->"
        private const val EXECUTE_ANCHOR = "executeAuthorized(tool, args)"

        /**
         * A plain declaration is the property, not a style preference: a non-suspend function
         * cannot call `withContext`, so pinning the declaration is what makes a hop between
         * the claim and the handler checkable at all. The region check below would miss a
         * helper that quietly became `suspend`.
         */
        private const val HELPER_DECLARATION = "private fun beginDispatch("
        private const val SUSPENDING_HELPER_DECLARATION = "private suspend fun beginDispatch("

        /** Every one of these resumes the invocation between the claim and the handler. */
        private val suspendingTokens = listOf("withContext", "suspend", "delay(", ".await(", "yield(")

        /** What is wrong with the dispatch seam. */
        internal data class DispatchSeam(
            val problems: List<String>,
        )

        /**
         * What is wrong with the dispatch seam in [source], or null when the anchors are
         * missing, duplicated or out of order.
         *
         * Null rather than an empty problem list on purpose: a guard that silently passes
         * because it found nothing to check is how this kind of test rots into decoration.
         */
        internal fun dispatchSeam(source: String): DispatchSeam? {
            val claim = source.indexOf(CLAIM_ANCHOR)
            val execute = source.indexOf(EXECUTE_ANCHOR)
            val located =
                source.split(CLAIM_ANCHOR).size == 2 &&
                    source.split(EXECUTE_ANCHOR).size == 2 &&
                    claim < execute
            if (!located) return null

            val problems = mutableListOf<String>()
            if (source.contains(SUSPENDING_HELPER_DECLARATION)) {
                problems += "'$SUSPENDING_HELPER_DECLARATION' is suspend, so the claim can hop"
            }
            if (!source.contains(HELPER_DECLARATION)) {
                problems += "no '$HELPER_DECLARATION' declaration"
            }
            val between = source.substring(claim, execute)
            problems +=
                suspendingTokens
                    .filter { between.contains(it) }
                    .map { "'$it' between the claim and the execution" }
            return DispatchSeam(problems)
        }
    }
}
