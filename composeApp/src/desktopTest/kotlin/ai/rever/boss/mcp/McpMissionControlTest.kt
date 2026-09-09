package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpMissionControlTest {
    private val tempFiles = mutableListOf<File>()

    private fun tempDisabledFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-mission-control-test")
                .toFile()
        return File(dir, "mcp-disabled-tools.json").also { tempFiles.add(it) }
    }

    @BeforeTest
    fun setup() {
        McpTelemetryRecorder.toolsRequiringApproval.value.forEach {
            McpTelemetryRecorder.setToolRequiresApproval(it, false)
        }
        assertTrue(McpTelemetryRecorder.pendingApprovals.value.isEmpty())
        McpTelemetryRecorder.clear()
        McpTelemetryRecorder.setGlobalSafeMode(false)
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
        McpTelemetryRecorder.toolsRequiringApproval.value.forEach {
            McpTelemetryRecorder.setToolRequiresApproval(it, false)
        }
        assertTrue(McpTelemetryRecorder.pendingApprovals.value.isEmpty())
        McpTelemetryRecorder.clear()
        McpTelemetryRecorder.setGlobalSafeMode(false)
    }

    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(
        name: String,
        handler: McpToolHandler = McpToolHandler { args -> McpToolResult("echo:${args.raw}") },
    ) = McpToolDefinition(name = name, description = "Test tool $name", handler = handler)

    @Test
    fun `ring buffer retains maximum 200 records and evicts oldest first`() {
        // Record 250 calls
        for (i in 1..250) {
            val callId = "call-$i"
            McpTelemetryRecorder.recordStart(callId, "tool-$i", """{"i":$i}""")
            McpTelemetryRecorder.recordComplete(callId, McpToolResult("ok"), durationMs = 5L)
        }

        val records = McpTelemetryRecorder.records.value
        assertEquals(McpTelemetryRecorder.MAX_RECORDS, records.size)

        // Newest should be call-250 (at head of list)
        assertEquals("call-250", records.first().callId)
        // Oldest retained should be call-51 (at tail of list)
        assertEquals("call-51", records.last().callId)

        val stats = McpTelemetryRecorder.stats.value
        assertEquals(200L, stats.totalCalls)
        assertEquals(200L, stats.successCount)
        assertEquals(0L, stats.errorCount)
    }

    @Test
    fun `successful invocation records duration, payload, and status`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile())
            core.registerProvider(provider("p1", echoTool("calculate")))

            val result = core.invoke("calculate", """{"x":10,"y":20}""")
            assertFalse(result.isError)

            val record = McpTelemetryRecorder.records.value.first()
            assertEquals("calculate", record.toolName)
            assertEquals(McpCallStatus.SUCCESS, record.status)
            assertTrue(record.durationMs >= 0L)
            assertNotNull(record.resultPayload)
            assertTrue(record.resultPayload!!.contains("omitted"))
        }

    @Test
    fun `blocked invocation records BLOCKED status when tool is disabled`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile())
            core.registerProvider(provider("p1", echoTool("dangerous_delete")))
            core.setToolEnabled("dangerous_delete", false)

            val result = core.invoke("dangerous_delete", """{"file":"/root"}""")
            assertTrue(result.isError)

            val record = McpTelemetryRecorder.records.value.first()
            assertEquals("dangerous_delete", record.toolName)
            assertEquals(McpCallStatus.BLOCKED, record.status)
            assertTrue(record.errorMessage!!.contains("Unknown or disabled"))
        }

    @Test
    fun `timeout invocation records TIMEOUT status`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile(), invokeTimeoutMs = 50L)
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "slow_tool",
                        handler =
                            McpToolHandler {
                                delay(200L)
                                McpToolResult("done")
                            },
                    ),
                ),
            )

            val result = core.invoke("slow_tool", "{}")
            assertTrue(result.isError)
            assertTrue(result.text.contains("timed out"))

            val record = McpTelemetryRecorder.records.value.first()
            assertEquals("slow_tool", record.toolName)
            assertEquals(McpCallStatus.TIMEOUT, record.status)
            assertTrue(record.durationMs >= 50L)
        }

    @Test
    fun `human in the loop approval flow - operator approves call`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile())
            core.registerProvider(provider("p1", echoTool("deploy_prod")))

            McpTelemetryRecorder.setToolRequiresApproval("deploy_prod", true)
            assertTrue(McpTelemetryRecorder.isApprovalRequired("deploy_prod"))

            // Run invocation asynchronously
            val deferredResult =
                async {
                    core.invoke("deploy_prod", """{"version":"1.0.0"}""")
                }

            // Wait until pending approval is registered
            while (McpTelemetryRecorder.pendingApprovals.value.isEmpty()) {
                delay(10L)
            }

            val pending = McpTelemetryRecorder.pendingApprovals.value.first()
            assertEquals("deploy_prod", pending.toolName)

            // Operator approves the action
            McpTelemetryRecorder.resolveApproval(pending.callId, ApprovalDecision.Approved())

            val result = deferredResult.await()
            assertFalse(result.isError)

            val record = McpTelemetryRecorder.records.value.first()
            assertEquals(McpCallStatus.SUCCESS, record.status)
            assertTrue(record.requiresApproval)
            assertEquals(1, McpTelemetryRecorder.records.value.count { it.callId == pending.callId })
        }

    @Test
    fun `human in the loop approval flow - operator denies call`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile())
            core.registerProvider(provider("p1", echoTool("rm_rf")))

            McpTelemetryRecorder.setToolRequiresApproval("rm_rf", true)

            val deferredResult =
                async {
                    core.invoke("rm_rf", """{"path":"/etc"}""")
                }

            while (McpTelemetryRecorder.pendingApprovals.value.isEmpty()) {
                delay(10L)
            }

            val pending = McpTelemetryRecorder.pendingApprovals.value.first()
            McpTelemetryRecorder.resolveApproval(
                pending.callId,
                ApprovalDecision.Denied("Dangerous path deletion rejected by operator"),
            )

            val result = deferredResult.await()
            assertTrue(result.isError)
            assertTrue(result.text.contains("Dangerous path deletion rejected"))

            val record = McpTelemetryRecorder.records.value.first()
            assertEquals(McpCallStatus.DENIED, record.status)
            assertEquals("Approval denied by operator", record.errorMessage)
        }

    @Test
    fun `human in the loop approval flow - operator modifies arguments`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile())
            var executedArgs: String? = null
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "write_config",
                        handler =
                            McpToolHandler { args ->
                                executedArgs = args.raw
                                McpToolResult("ok")
                            },
                    ),
                ),
            )

            McpTelemetryRecorder.setToolRequiresApproval("write_config", true)

            val deferredResult =
                async {
                    core.invoke("write_config", """{"env":"production"}""")
                }

            while (McpTelemetryRecorder.pendingApprovals.value.isEmpty()) {
                delay(10L)
            }

            val pending = McpTelemetryRecorder.pendingApprovals.value.first()
            // Operator modifies arguments to staging
            McpTelemetryRecorder.resolveApproval(
                pending.callId,
                ApprovalDecision.Approved(modifiedArgs = """{"env":"staging"}"""),
            )

            deferredResult.await()
            assertEquals("""{"env":"staging"}""", executedArgs)
        }

    @Test
    fun `secret redaction masks passwords, tokens, and credentials in arguments`() {
        val raw = """{"username":"alice","password":"mypassword123","apiKey":"secret-token-xyz","port":8080}"""
        val masked = McpTelemetryRecorder.maskSecrets(raw)

        assertFalse(masked.contains("mypassword123"))
        assertFalse(masked.contains("secret-token-xyz"))
        assertTrue(masked.contains("***REDACTED***"))
        assertTrue(masked.contains("alice"))
        assertTrue(masked.contains("8080"))
    }

    @Test
    fun `concurrent invocations maintain thread safety and accurate stats`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile())
            core.registerProvider(provider("p1", echoTool("concurrent_tool")))

            coroutineScope {
                val jobs =
                    (1..50).map { i ->
                        async {
                            core.invoke("concurrent_tool", """{"index":$i}""")
                        }
                    }
                jobs.awaitAll()
            }

            val stats = McpTelemetryRecorder.stats.value
            assertEquals(50L, stats.totalCalls)
            assertEquals(50L, stats.successCount)
            assertEquals(0L, stats.errorCount)
            assertEquals(0, stats.activeInFlight)
            assertEquals(50, McpTelemetryRecorder.records.value.size)
        }

    @Test
    fun `agent self-awareness tools report history and failure diagnosis`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = tempDisabledFile())
            core.registerProvider(McpMissionControlToolProvider)
            core.updateAccess(true, emptySet())

            // Seed one successful and one failed tool call
            val s1 = McpTelemetryRecorder.nextCallId()
            McpTelemetryRecorder.recordStart(s1, "git_status", "{}")
            McpTelemetryRecorder.recordComplete(s1, McpToolResult("clean"), durationMs = 12L)

            val s2 = McpTelemetryRecorder.nextCallId()
            McpTelemetryRecorder.recordStart(s2, "k8s_deploy", "{}")
            McpTelemetryRecorder.recordComplete(s2, McpToolResult("ImagePullBackOff", isError = true), durationMs = 45L)

            // Agent queries history
            val historyResult = core.invoke("get_tool_history", """{"limit":5}""")
            assertFalse(historyResult.isError)
            assertTrue(historyResult.text.contains("git_status"))
            assertTrue(historyResult.text.contains("k8s_deploy"))

            // Agent diagnoses last failure
            val diagResult = core.invoke("diagnose_last_failure", "{}")
            assertFalse(diagResult.isError)
            assertTrue(diagResult.text.contains("k8s_deploy"))
            assertTrue(diagResult.text.contains("omitted"))
            assertTrue(diagResult.text.contains("recommendedAction"))
        }

    @Test
    fun `cancelled approval is removed and cannot execute later`() =
        runBlocking {
            val job = async { McpTelemetryRecorder.requestApproval("cancel-test", "write", "{}") }
            while (McpTelemetryRecorder.pendingApprovals.value.isEmpty()) delay(1)
            val pending = McpTelemetryRecorder.pendingApprovals.value.first()
            job.cancel()
            job.join()
            assertTrue(McpTelemetryRecorder.pendingApprovals.value.isEmpty())
            assertTrue(pending.deferredResponse.isCompleted)
            assertEquals(
                McpCallStatus.CANCELLED,
                McpTelemetryRecorder.records.value
                    .first()
                    .status,
            )
        }

    @Test
    fun `approved call has one history record and respects revocation`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            var called = false
            core.registerProvider(
                provider(
                    "p",
                    echoTool("revoke-test") {
                        called = true
                        McpToolResult("ok")
                    },
                ),
            )
            McpTelemetryRecorder.setToolRequiresApproval("revoke-test", true)
            val job = async { core.invoke("revoke-test", "{}") }
            while (McpTelemetryRecorder.pendingApprovals.value.isEmpty()) delay(1)
            val request = McpTelemetryRecorder.pendingApprovals.value.first()
            core.setToolEnabled("revoke-test", false)
            McpTelemetryRecorder.resolveApproval(request.callId, ApprovalDecision.Approved())
            assertTrue(job.await().isError)
            assertFalse(called)
            assertEquals(1, McpTelemetryRecorder.records.value.size)
        }

    @Test
    fun `history omits unstructured output and malformed and nested secrets`() {
        assertFalse(McpTelemetryRecorder.maskSecrets("{password:sentinel}").contains("sentinel"))
        assertFalse(McpTelemetryRecorder.maskSecrets("""{"auth":{"value":"sentinel"}}""").contains("sentinel"))
        McpTelemetryRecorder.recordStart("sensitive", "secret_get", "{}")
        McpTelemetryRecorder.recordComplete("sensitive", McpToolResult("sentinel"), 1)
        assertFalse(McpTelemetryRecorder.exportAsJson().contains("sentinel"))
    }

    @Test
    fun `operator edits reject redacted or invalid JSON while preserving ordinary paths`() {
        assertFalse(McpTelemetryRecorder.canUseEditedArguments("{bad}"))
        assertFalse(McpTelemetryRecorder.canUseEditedArguments("[]"))
        assertFalse(McpTelemetryRecorder.canUseEditedArguments("""{"token":"***REDACTED***"}"""))
        val path = """{"path":"/Users/alice/project/config.json"}"""
        assertTrue(McpTelemetryRecorder.maskSecrets(path).contains("/Users/alice/project/config.json"))
        assertTrue(McpTelemetryRecorder.canUseEditedArguments(path))
    }

    @Test
    fun `cross tool diagnostics are withheld from non admins`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(McpMissionControlToolProvider)
            assertTrue(core.invoke("get_tool_history", "{}").isError)
            assertTrue(core.invoke("diagnose_last_failure", "{}").isError)
        }

    @Test
    fun `deep JSON is omitted before recursive parsing and strings do not count as nesting`() {
        val deep = "[".repeat(2000) + "0" + "]".repeat(2000)
        assertTrue(McpTelemetryRecorder.maskSecrets(deep).contains("too deeply nested"))
        assertFalse(McpTelemetryRecorder.canUseEditedArguments(deep))
        val quoted = """{"text":"[[[[\"[[["}"""
        assertTrue(McpTelemetryRecorder.canUseEditedArguments(quoted))
    }

    @Test
    fun `approval overflow is recorded and cancellation frees all slots`() =
        runBlocking {
            val jobs =
                (1..McpTelemetryRecorder.MAX_PENDING_APPROVALS).map { index ->
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        McpTelemetryRecorder.requestApproval("pending-$index", "write", "{}")
                    }
                }
            try {
                val decision = McpTelemetryRecorder.requestApproval("overflow", "write", "{}")
                assertTrue(decision is ApprovalDecision.Denied)
                val record = McpTelemetryRecorder.records.value.first { it.callId == "overflow" }
                assertEquals(McpCallStatus.BLOCKED, record.status)
                assertTrue(record.errorMessage!!.contains("Too many pending"))
            } finally {
                jobs.forEach { it.cancel() }
                jobs.forEach { it.join() }
            }
            assertTrue(McpTelemetryRecorder.pendingApprovals.value.isEmpty())
        }

    @Test
    fun `approval timeout leaves a final timeout row`() =
        runBlocking {
            val decision = McpTelemetryRecorder.requestApproval("timeout", "write", "{}", timeoutMs = 1)
            assertTrue(decision is ApprovalDecision.Denied)
            assertEquals(
                McpCallStatus.TIMEOUT,
                McpTelemetryRecorder.records.value
                    .single()
                    .status,
            )
            assertTrue(McpTelemetryRecorder.pendingApprovals.value.isEmpty())
        }

    @Test
    fun `clear history retains active rows until completion`() {
        McpTelemetryRecorder.recordStart("running", "read", "{}")
        McpTelemetryRecorder.recordStart("done", "read", "{}")
        McpTelemetryRecorder.recordComplete("done", McpToolResult("ok"), 1)
        McpTelemetryRecorder.clear()
        assertEquals(
            "running",
            McpTelemetryRecorder.records.value
                .single()
                .callId,
        )
        McpTelemetryRecorder.recordComplete("running", McpToolResult("ok"), 2)
        assertEquals(
            McpCallStatus.SUCCESS,
            McpTelemetryRecorder.records.value
                .single()
                .status,
        )
    }

    @Test
    fun `invalid edited approval preserves actionable host rejection in history`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            var calls = 0
            core.registerProvider(
                provider(
                    "p",
                    echoTool("edit-reject") {
                        calls++
                        McpToolResult("ok")
                    },
                ),
            )
            McpTelemetryRecorder.setToolRequiresApproval("edit-reject", true)
            val result = async { core.invoke("edit-reject", "{}") }
            while (McpTelemetryRecorder.pendingApprovals.value.isEmpty()) delay(1)
            val request = McpTelemetryRecorder.pendingApprovals.value.first()
            McpTelemetryRecorder.resolveApproval(request.callId, ApprovalDecision.Approved("{bad}"))
            assertTrue(result.await().isError)
            assertEquals(0, calls)
            val record = McpTelemetryRecorder.records.value.single()
            assertEquals(McpCallStatus.BLOCKED, record.status)
            assertTrue(record.errorMessage!!.contains("valid JSON"))
        }
}
