package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.window.Project
import ai.rever.boss.plugin.window.WindowProjectState
import ai.rever.boss.plugin.window.WorkspaceContextToken
import ai.rever.boss.window.WindowProjectStateRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verification test suite for Causal Context Binding & Stale Agent Execution Fencing.
 *
 * Enforces the core invariant: AUTHORIZED_CONTEXT == EXECUTION_CONTEXT.
 * Verifies mutating fail-closed policy, epoch advancement detection, window isolation,
 * prompt stealing prevention, and honest post-execution drift auditing.
 */
class McpCausalContextFencingTest {
    private val testWindows = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        testWindows.clear()
    }

    @AfterTest
    fun tearDown() {
        testWindows.forEach { WindowProjectStateRegistry.unregister(it) }
        testWindows.clear()
    }

    private fun registerTestWindow(
        windowId: String,
        projectPath: String,
    ): WindowProjectState {
        testWindows.add(windowId)
        val state = WindowProjectStateRegistry.getOrCreate(windowId)
        if (projectPath.isNotEmpty()) {
            state.selectProject(Project(name = "test-proj", path = projectPath))
        }
        return state
    }

    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id
        override fun tools() = defs.toList()
    }

    private fun testTool(
        name: String,
        handler: McpToolHandler = McpToolHandler { McpToolResult("success:$name") },
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)

    @Test
    fun `mutating tool without context fails closed and handler is never called`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "run_command",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("committed")
                        },
                )
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val res = core.invoke("run_command", "{}", contextToken = null)

            assertTrue(McpMutatingToolCatalog.isMutating("run_command"), "run_command must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("git_commit"), "git_commit must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("git_push"), "git_push must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("docker_run"), "docker_run must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("rename_file"), "rename_file must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("update_config"), "update_config must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("patch_resource"), "patch_resource must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("modify_secret"), "modify_secret must be mutating")
            assertTrue(McpMutatingToolCatalog.isMutating("set_permission"), "set_permission must be mutating")
            assertFalse(McpMutatingToolCatalog.isMutating("git_status"), "git_status must be read-only")
            assertFalse(McpMutatingToolCatalog.isMutating("file_read"), "file_read must be read-only")
            assertFalse(McpMutatingToolCatalog.isMutating("codebase_search"), "codebase_search must be read-only")
            assertTrue(res.isError, "Mutating tool must return error when ungrounded")
            assertTrue(
                res.text.contains("rejected", ignoreCase = true),
                "Error must indicate rejection: ${res.text}",
            )
            assertFalse(handlerCalled, "Mutating handler must NEVER be called without context")

            assertEquals(1L, ledger.totalCalls.value)
            assertEquals(1L, ledger.totalErrors.value)
            assertEquals(
                McpApprovalDisposition.CONTEXT_UNBOUND_REJECTED,
                ledger.recentOperations.value.first().approvalDisposition,
            )
        }

    @Test
    fun `mutating tool with empty project path fails closed and handler is never called`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "run_command",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("ran")
                        },
                )
            val windowState = registerTestWindow("win-empty", projectPath = "")
            val token = windowState.currentContextToken()
            assertFalse(token.hasProject, "Token must report hasProject = false")

            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val res = core.invoke("run_command", "{}", contextToken = token)

            assertTrue(res.isError)
            assertTrue(res.text.contains("No project is open"), "Error message must state no project: ${res.text}")
            assertFalse(handlerCalled, "Handler must NEVER execute in a window with no project open")
            assertEquals(
                McpApprovalDisposition.CONTEXT_UNBOUND_REJECTED,
                ledger.recentOperations.value.first().approvalDisposition,
            )
        }

    @Test
    fun `authorized context matching live execution context permits mutating tool execution`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "git_commit",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("commit created successfully")
                        },
                )
            val windowState = registerTestWindow("win-match", projectPath = "/repos/project-alpha")
            val token = windowState.currentContextToken()
            assertEquals(1L, token.generation)
            assertEquals("/repos/project-alpha", token.projectPath)

            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("git_commit", McpPolicyAction.ALLOW)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val res = core.invoke("git_commit", "{}", contextToken = token)

            assertFalse(res.isError, "Invocation must succeed when contexts match: ${res.text}")
            assertTrue(handlerCalled, "Handler must be called")
            assertEquals("commit created successfully", res.text)

            val record = ledger.recentOperations.value.first()
            assertEquals(McpApprovalDisposition.AUTO_ALLOWED, record.approvalDisposition)
            assertEquals("win-match", record.windowId)
            assertEquals("/repos/project-alpha", record.projectPath)
            assertEquals(1L, record.contextGeneration)
        }

    @Test
    fun `read-only tool without context token allows ambient execution for backwards compatibility`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "git_status",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("clean working tree")
                        },
                )
            assertFalse(McpMutatingToolCatalog.isMutating("git_status"), "git_status must be read-only")

            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("git_status", McpPolicyAction.ALLOW)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val res = core.invoke("git_status", "{}", contextToken = null)

            assertFalse(res.isError)
            assertTrue(handlerCalled)
            assertEquals("clean working tree", res.text)
            assertEquals(
                McpApprovalDisposition.AUTO_ALLOWED,
                ledger.recentOperations.value.first().approvalDisposition,
            )
        }

    @Test
    fun `project switch during approval causes pre-dispatch revalidation to abort execution`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "run_command",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("pushed")
                        },
                )
            val windowState = registerTestWindow("win-switch", projectPath = "/repos/project-safe")
            val token = windowState.currentContextToken()
            assertEquals("/repos/project-safe", token.projectPath)
            assertEquals(1L, token.generation)

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            // Invoke asynchronously on background dispatcher with the token for project-safe
            val deferred = async(Dispatchers.Default) { core.invoke("run_command", "{}", contextToken = token) }

            // Wait for approval request to enter queue
            val req =
                withTimeout(10_000L) {
                    approvalBus.pendingList.first { it.isNotEmpty() }.first()
                }
            assertEquals("run_command", req.toolName)
            assertEquals(token, req.contextToken)

            // CRITICAL RACE STEP: User switches project in the active window before approving
            windowState.selectProject(Project(name = "project-danger", path = "/repos/project-danger"))
            assertEquals(2L, windowState.generation)
            assertEquals("/repos/project-danger", windowState.currentProject().path)

            // Now operator naively approves the old pending request
            approvalBus.approve(req.id, trustForSession = false)

            val res = withTimeout(10_000L) { deferred.await() }

            // Pre-dispatch revalidation must abort!
            assertTrue(res.isError, "Execution must be aborted because project switched")
            assertTrue(res.text.contains("Execution aborted", ignoreCase = true))
            assertTrue(res.text.contains("switched", ignoreCase = true))
            assertFalse(handlerCalled, "Handler must NEVER execute against the switched project!")

            val record = ledger.recentOperations.value.first()
            assertTrue(record.isError)
            assertEquals(McpApprovalDisposition.CONTEXT_STALE, record.approvalDisposition)
        }

    @Test
    fun `generation drift during approval causes pre-dispatch revalidation to abort execution`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "run_command",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("deployed")
                        },
                )
            val windowState = registerTestWindow("win-drift", projectPath = "/repos/target-app")
            val token = windowState.currentContextToken()
            assertEquals(1L, token.generation)

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val deferred = async(Dispatchers.Default) { core.invoke("run_command", "{}", contextToken = token) }
            val req =
                withTimeout(10_000L) {
                    approvalBus.pendingList.first { it.isNotEmpty() }.first()
                }

            // Workspace state undergoes re-selection or context reset, bumping generation
            windowState.selectProject(Project(name = "target-app", path = "/repos/target-app"))
            assertEquals(2L, windowState.generation)

            // Operator approves
            approvalBus.approve(req.id)

            val res = withTimeout(10_000L) { deferred.await() }
            assertTrue(res.isError)
            assertTrue(res.text.contains("Execution aborted"), "Expected aborted message: ${res.text}")
            assertFalse(handlerCalled, "Handler must not run when epoch is stale")
            assertEquals(
                McpApprovalDisposition.CONTEXT_STALE,
                ledger.recentOperations.value.first().approvalDisposition,
            )
        }

    @Test
    fun `window closure during approval causes pre-dispatch revalidation to abort execution`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "run_command",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("committed")
                        },
                )
            val windowState = registerTestWindow("win-close", projectPath = "/repos/app")
            val token = windowState.currentContextToken()

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val deferred = async(Dispatchers.Default) { core.invoke("run_command", "{}", contextToken = token) }
            val req =
                withTimeout(10_000L) {
                    approvalBus.pendingList.first { it.isNotEmpty() }.first()
                }

            // Window closes while prompt was waiting
            WindowProjectStateRegistry.unregister("win-close")
            assertTrue(windowState.isClosed)

            // Force approve through bus
            approvalBus.approve(req.id)

            val res = withTimeout(10_000L) { deferred.await() }
            assertTrue(res.isError)
            assertTrue(res.text.contains("Execution aborted") || res.text.contains("closed"))
            assertFalse(handlerCalled, "Handler must not run in a closed window")
            assertEquals(
                McpApprovalDisposition.CONTEXT_STALE,
                ledger.recentOperations.value.first().approvalDisposition,
            )
        }

    @Test
    fun `cross-window prompt stealing is strictly prevented by window claiming`() =
        runBlocking {
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val tokenWindow1 = WorkspaceContextToken(windowId = "win-1", projectPath = "/repo/1", generation = 0L)

            val win1Delivered = java.util.Collections.synchronizedList(mutableListOf<McpApprovalRequest>())
            val win2Delivered = java.util.Collections.synchronizedList(mutableListOf<McpApprovalRequest>())

            val job1 =
                launch(Dispatchers.Default) {
                    approvalBus.consumeApprovals(targetWindowId = "win-1") { req ->
                        if (req != null) win1Delivered.add(req)
                    }
                }
            val job2 =
                launch(Dispatchers.Default) {
                    approvalBus.consumeApprovals(targetWindowId = "win-2") { req ->
                        if (req != null) win2Delivered.add(req)
                    }
                }

            try {
                // Ensure collectors have started listening
                withTimeout(2000L) {
                    while (approvalBus.subscriptionCount.value < 2) {
                        delay(10)
                    }
                }

                // Request approval specifically bound to Window 1
                val reqDeferred =
                    async(Dispatchers.Default) {
                        approvalBus.requestApproval(
                            toolName = "git_commit",
                            providerId = "test-provider",
                            arguments = emptyMap(),
                            contextToken = tokenWindow1,
                        )
                    }

                // Wait for Window 1 to receive the prompt
                withTimeout(2000L) {
                    while (win1Delivered.isEmpty()) {
                        delay(10)
                    }
                }

                assertEquals(1, win1Delivered.size, "Window 1 consumer must receive the request")
                assertEquals(0, win2Delivered.size, "Window 2 consumer must NEVER receive Window 1 prompt (no stealing!)")

                // Operator approves in Window 1
                approvalBus.approve(win1Delivered.first().id)

                val decision = reqDeferred.await()
                assertTrue(decision is McpApprovalDecision.Approved)
            } finally {
                job1.cancel()
                job2.cancel()
            }
        }

    @Test
    fun `reactive approval invalidation on project switch immediately cancels pending request`() =
        runBlocking {
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 10_000L)
            val token = WorkspaceContextToken(windowId = "win-reactive", projectPath = "/repo/old", generation = 0L)

            val deferred =
                async(Dispatchers.Default) {
                    approvalBus.requestApproval(
                        toolName = "git_push",
                        providerId = "test",
                        arguments = emptyMap(),
                        contextToken = token,
                    )
                }

            // Wait for request to register in pendingList
            val req =
                withTimeout(10_000L) {
                    approvalBus.pendingList.first { it.isNotEmpty() }.first()
                }
            assertEquals(token, req.contextToken)

            // Reactive project switch event triggers invalidateForWindow
            approvalBus.invalidateForWindow(windowId = "win-reactive", newGeneration = 1L)

            // Deferred completes immediately with Denied, without waiting 10s!
            val decision = withTimeout(10_000L) { deferred.await() }
            assertTrue(decision is McpApprovalDecision.Denied, "Decision must be Denied due to invalidation")
            assertTrue(
                decision.reason.contains("Workspace context changed"),
                "Reason must explain context shift: ${decision.reason}",
            )
        }

    @Test
    fun `post-execution drift audits disposition as CONTEXT_STALE without claiming rollback`() =
        runBlocking {
            var handlerExecuted = false
            val windowState = registerTestWindow("win-post", projectPath = "/repos/project-initial")
            val token = windowState.currentContextToken()

            val tool =
                testTool(
                    name = "slow_writer",
                    handler =
                        McpToolHandler {
                            // Handler executes and produces real side effects
                            handlerExecuted = true
                            // During execution, user switches project in the window
                            windowState.selectProject(Project(name = "project-other", path = "/repos/project-other"))
                            McpToolResult("File wrote 50 lines")
                        },
                )

            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("slow_writer", McpPolicyAction.ALLOW)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val res = core.invoke("slow_writer", "{}", contextToken = token)

            assertTrue(handlerExecuted, "Handler actually ran and had side-effects")
            assertFalse(res.isError, "Result does not falsely claim the command was not run")
            assertTrue(
                res.text.contains("BOSS CAUTION: Workspace context drifted during execution"),
                "Result must include caution backstop warning: ${res.text}",
            )
            assertFalse(
                res.text.contains("rolled back"),
                "Result must NEVER dishonestly claim to have rolled back external side effects",
            )

            // Ledger disposition audits the drift as CONTEXT_STALE
            val record = ledger.recentOperations.value.first()
            assertEquals(McpApprovalDisposition.CONTEXT_STALE, record.approvalDisposition)
        }

    @Test
    fun `context validation normalizes path separators and trailing slashes seamlessly`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "run_command",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("path normalized successfully")
                        },
                )
            val windowState = registerTestWindow("win-norm", projectPath = "/repos/project-norm/")
            // Token has backslashes and no trailing slash
            val token =
                WorkspaceContextToken(
                    windowId = "win-norm",
                    projectPath = "\\repos\\project-norm",
                    generation = windowState.generation,
                )

            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    ledger = McpOperationLedger(ledgerFile = null),
                )
            core.registerProvider(provider("p1", tool))

            val res = core.invoke("run_command", "{}", contextToken = token)
            assertFalse(res.isError, "Invocation must succeed with normalized path matching: ${res.text}")
            assertTrue(handlerCalled)
        }

    @Test
    fun `mutating tool verb token heuristics properly fence prefix and infix mutating operations when ungrounded`() =
        runBlocking {
            val toolsToTest = listOf("rename_file", "update_config", "patch_resource", "modify_secret", "set_permission")
            for (toolName in toolsToTest) {
                assertTrue(McpMutatingToolCatalog.isMutating(toolName), "$toolName must be recognized as mutating")
                var called = false
                val tool = testTool(name = toolName, handler = McpToolHandler { called = true; McpToolResult("done") })
                val core = McpToolRegistryCore(disabledFile = null, ledger = McpOperationLedger(ledgerFile = null))
                core.registerProvider(provider("p-$toolName", tool))

                val res = core.invoke(toolName, "{}", contextToken = null)
                assertTrue(res.isError, "Ungrounded $toolName must fail closed")
                assertFalse(called, "$toolName handler must never be called without context")
            }
        }

    @Test
    fun `deterministic race project switch before lease acquisition strictly prevents handler execution`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                testTool(
                    name = "git_commit",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("committed")
                        },
                )
            val windowState = registerTestWindow("win-race-prevent", projectPath = "/repos/race-1")
            val token = windowState.currentContextToken()

            val bus = McpApprovalBus()
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = bus,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val deferred =
                async(Dispatchers.Default) {
                    core.invoke("git_commit", "{}", contextToken = token)
                }

            val req = bus.pendingList.first { it.isNotEmpty() }.first()

            // Deterministic interleaving: switch project and approve
            windowState.selectProject(Project(name = "race-2", path = "/repos/race-2"))
            bus.approve(req.id)

            val res = deferred.await()
            assertTrue(res.isError, "Execution must be aborted due to stale context: ${res.text}")
            assertFalse(handlerCalled, "Handler must NEVER execute against switched project")
            assertTrue(res.text.contains("aborted", ignoreCase = true))

            val record = ledger.recentOperations.value.first()
            assertEquals(McpApprovalDisposition.CONTEXT_STALE, record.approvalDisposition)
        }

    @Test
    fun `project switch during in flight execution invalidates lease and audits disposition as CONTEXT_STALE`() =
        runBlocking {
            val handlerStarted = CompletableDeferred<Unit>()
            val switchCompleted = CompletableDeferred<Unit>()
            var handlerCompleted = false
            val tool =
                testTool(
                    name = "run_command",
                    handler =
                        McpToolHandler {
                            handlerStarted.complete(Unit)
                            switchCompleted.await()
                            handlerCompleted = true
                            McpToolResult("finished")
                        },
                )
            val windowState = registerTestWindow("win-inflight-drift", projectPath = "/repos/drift-test")
            val token = windowState.currentContextToken()

            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val deferred =
                async(Dispatchers.Default) {
                    core.invoke("run_command", "{}", contextToken = token)
                }

            handlerStarted.await()

            // Switch project while tool is running in-flight!
            windowState.selectProject(Project(name = "new-proj", path = "/repos/new-proj"))
            switchCompleted.complete(Unit)

            val res = deferred.await()
            assertTrue(handlerCompleted, "Handler finishes safely without crashing host")
            assertTrue(res.text.contains("BOSS CAUTION"), "Result must contain drift caution")

            val record = ledger.recentOperations.value.first()
            assertEquals(McpApprovalDisposition.CONTEXT_STALE, record.approvalDisposition)
        }
}
