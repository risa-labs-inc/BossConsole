package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpTelemetryServiceTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempLedgerFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-telemetry-test")
                .toFile()
        return File(dir, "mcp-calls.jsonl").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `rolling window eviction correctly discards oldest latencies and errors`() {
        val service = McpTelemetryService(defaultWindowCapacity = 5)
        val tool = "test_tool"

        // Insert 5 successful invocations: 10, 20, 30, 40, 50 ms
        listOf(10L, 20L, 30L, 40L, 50L).forEach { duration ->
            service.recordInvocation(tool, duration, isError = false)
        }

        var stats = service.getStats(tool)
        assertEquals(5L, stats.totalInvocations)
        assertEquals(0L, stats.totalErrors)
        assertEquals(0.0, stats.errorPercentage)
        assertEquals(30L, stats.p50Ms)
        assertEquals(5, stats.windowSize)

        // Insert 6th invocation: 100 ms with error = true (evicts oldest: 10 ms)
        service.recordInvocation(tool, 100L, isError = true)

        stats = service.getStats(tool)
        assertEquals(6L, stats.totalInvocations)
        assertEquals(1L, stats.totalErrors)
        // Window now contains: 20, 30, 40, 50, 100 ms (1 error out of 5 = 20.0%)
        assertEquals(20.0, stats.errorPercentage)
        assertEquals(40L, stats.p50Ms)
        assertEquals(100L, stats.p99Ms)
        assertEquals(5, stats.windowSize)

        // Insert 5 more successful invocations (evicts the error)
        repeat(5) {
            service.recordInvocation(tool, 15L, isError = false)
        }

        stats = service.getStats(tool)
        assertEquals(11L, stats.totalInvocations)
        assertEquals(1L, stats.totalErrors)
        // The error has been evicted from the sliding window
        assertEquals(0.0, stats.errorPercentage)
        assertEquals(15L, stats.p50Ms)
        assertEquals(5, stats.windowSize)
    }

    @Test
    fun `slice-aware percentile sorting ignores unwritten buffer slots`() {
        // Capacity is 100, but we only record 10 elements
        val service = McpTelemetryService(defaultWindowCapacity = 100)
        val tool = "partial_tool"

        val latencies = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)
        latencies.forEach { service.recordInvocation(tool, it, isError = false) }

        val stats = service.getStats(tool)
        assertEquals(10L, stats.totalInvocations)
        assertEquals(10, stats.windowSize)
        // If unwritten 0L slots were sorted, p50 would be 0L. Slice-aware sorting gives 50L!
        assertEquals(50L, stats.p50Ms)
        assertEquals(100L, stats.p99Ms)
    }

    @Test
    fun `percentile accuracy across uniform distribution`() {
        val service = McpTelemetryService(defaultWindowCapacity = 100)
        val tool = "dist_tool"

        // 1 to 100 ms
        for (i in 1L..100L) {
            service.recordInvocation(tool, i, isError = false)
        }

        val stats = service.getStats(tool)
        assertEquals(100L, stats.totalInvocations)
        assertEquals(50L, stats.p50Ms)
        assertEquals(99L, stats.p99Ms)
    }

    @Test
    fun `single element percentile is exact`() {
        val service = McpTelemetryService(defaultWindowCapacity = 50)
        val tool = "single_tool"

        service.recordInvocation(tool, 42L, isError = false)

        val stats = service.getStats(tool)
        assertEquals(1L, stats.totalInvocations)
        assertEquals(42L, stats.p50Ms)
        assertEquals(42L, stats.p99Ms)
        assertEquals(1, stats.windowSize)
    }

    @Test
    fun `safe zero-division handling for inactive tools`() {
        val service = McpTelemetryService()

        val stats = service.getStats("never_invoked_tool")
        assertEquals("never_invoked_tool", stats.tool)
        assertEquals(0L, stats.totalInvocations)
        assertEquals(0L, stats.totalErrors)
        assertEquals(0.0, stats.errorPercentage)
        assertEquals(0L, stats.p50Ms)
        assertEquals(0L, stats.p99Ms)
        assertEquals(0, stats.windowSize)
        assertFalse(stats.errorPercentage.isNaN())
        assertFalse(stats.errorPercentage.isInfinite())
    }

    @Test
    fun `thread-safe concurrent recording preserves counts and does not corrupt state`() {
        val service = McpTelemetryService(defaultWindowCapacity = 50)
        val threadCount = 8
        val invocationsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val tools = listOf("tool_a", "tool_b", "tool_c", "tool_d")

        try {
            val tasks =
                (0 until threadCount).map { threadIdx ->
                    executor.submit {
                        for (i in 1..invocationsPerThread) {
                            val tool = tools[(threadIdx + i) % tools.size]
                            val isError = (i % 5 == 0)
                            val duration = (i % 100).toLong() + 1
                            service.recordInvocation(tool, duration, isError)
                        }
                    }
                }

            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val totalExpectedCalls = (threadCount * invocationsPerThread).toLong()
        val allStats = service.getAllStats()
        val actualTotalCalls = allStats.sumOf { it.totalInvocations }
        assertEquals(totalExpectedCalls, actualTotalCalls)

        for (stats in allStats) {
            assertTrue(stats.windowSize <= 50)
            assertTrue(stats.p50Ms >= 0)
            assertTrue(stats.p99Ms >= stats.p50Ms)
            assertTrue(stats.errorPercentage in 0.0..100.0)
        }
    }

    @Test
    fun `subscribes to McpOperationLedger and records telemetry events`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        val service = McpTelemetryService(ledger = ledger, defaultWindowCapacity = 50)

        ledger.record(
            toolName = "ledger_tool",
            providerId = "test",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 75L,
            isError = false,
            rawArgs = emptyMap(),
        )

        val stats = service.getStats("ledger_tool")
        assertEquals(1L, stats.totalInvocations)
        assertEquals(75L, stats.p50Ms)
        assertEquals(0.0, stats.errorPercentage)
    }

    @Test
    fun `mcp tool_stats handler returns structured JSON for single and all tools`() =
        runBlocking {
            val service = McpTelemetryService()
            service.recordInvocation("tool_alpha", 50L, isError = false)
            service.recordInvocation("tool_alpha", 150L, isError = true)
            service.recordInvocation("tool_beta", 20L, isError = false)

            val toolDef = assertNotNull(service.tools().firstOrNull { it.name == "tool_stats" })

            // Query specific tool
            val singleResult =
                toolDef.handler.call(
                    McpToolArgs(mapOf("tool" to "tool_alpha"), """{"tool":"tool_alpha"}"""),
                )
            assertFalse(singleResult.isError)
            val singleJson = Json.parseToJsonElement(singleResult.text).jsonObject
            assertEquals("tool_alpha", singleJson["tool"]?.jsonPrimitive?.content)
            assertEquals(2L, singleJson["totalInvocations"]?.jsonPrimitive?.content?.toLong())
            assertEquals(50.0, singleJson["errorPercentage"]?.jsonPrimitive?.content?.toDouble())

            // Query with mcp__boss__ prefix normalization
            val prefixedResult =
                toolDef.handler.call(
                    McpToolArgs(mapOf("tool" to "mcp__boss__tool_beta"), """{"tool":"mcp__boss__tool_beta"}"""),
                )
            assertFalse(prefixedResult.isError)
            val prefixedJson = Json.parseToJsonElement(prefixedResult.text).jsonObject
            assertEquals("tool_beta", prefixedJson["tool"]?.jsonPrimitive?.content)
            assertEquals(1L, prefixedJson["totalInvocations"]?.jsonPrimitive?.content?.toLong())

            // Query all tools
            val allResult = toolDef.handler.call(McpToolArgs(emptyMap(), "{}"))
            assertFalse(allResult.isError)
            val allJson = Json.parseToJsonElement(allResult.text).jsonObject
            val toolsArray = assertNotNull(allJson["tools"]?.jsonArray)
            assertEquals(2, toolsArray.size)

            // Query inactive tool
            val inactiveResult =
                toolDef.handler.call(
                    McpToolArgs(mapOf("tool" to "unseen"), """{"tool":"unseen"}"""),
                )
            assertFalse(inactiveResult.isError)
            val inactiveJson = Json.parseToJsonElement(inactiveResult.text).jsonObject
            assertEquals(0L, inactiveJson["totalInvocations"]?.jsonPrimitive?.content?.toLong())
            assertEquals(0.0, inactiveJson["errorPercentage"]?.jsonPrimitive?.content?.toDouble())
        }

    @Test
    fun `computePercentile safely handles empty array and does not throw coerceIn exception`() {
        val emptyArray = LongArray(0)
        assertEquals(0L, computePercentile(emptyArray, 50.0))
        assertEquals(0L, computePercentile(emptyArray, 99.0))
        assertEquals(0L, computePercentile(emptyArray, 0.0))
        assertEquals(0L, computePercentile(emptyArray, 100.0))

        val singleElement = longArrayOf(42L)
        assertEquals(42L, computePercentile(singleElement, 50.0))
        assertEquals(42L, computePercentile(singleElement, 99.0))
        assertEquals(42L, computePercentile(singleElement, 0.0))
    }

    @Test
    fun `enforces MAX_TRACKED_TOOLS cap to prevent unbounded memory allocation`() {
        val service = McpTelemetryService(defaultWindowCapacity = 10)

        // Record up to the limit of 256 unique tools
        for (i in 1..McpTelemetryService.MAX_TRACKED_TOOLS) {
            service.recordInvocation("tool_$i", durationMs = 10L, isError = false)
        }

        val allStatsBefore = service.getAllStats()
        assertEquals(McpTelemetryService.MAX_TRACKED_TOOLS, allStatsBefore.size)

        // Attempt to record for 257th distinct rogue/unbounded tool name
        service.recordInvocation("rogue_tool_overflow", durationMs = 50L, isError = false)

        val allStatsAfter = service.getAllStats()
        // Must still be capped at 256 tools
        assertEquals(McpTelemetryService.MAX_TRACKED_TOOLS, allStatsAfter.size)

        // The overflow tool should return safe zero-state stats without allocating a buffer
        val overflowStats = service.getStats("rogue_tool_overflow")
        assertEquals(0L, overflowStats.totalInvocations)
        assertEquals(0, overflowStats.windowSize)
    }

    @Test
    fun `ledger listener dispatch with volatile copy-on-write operates correctly`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        val capturedRecords = mutableListOf<McpOperationRecord>()
        val listener: (McpOperationRecord) -> Unit = { capturedRecords.add(it) }

        ledger.addListener(listener)

        ledger.record(
            toolName = "test_hotpath_tool",
            providerId = "test",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 25L,
            isError = false,
            rawArgs = emptyMap(),
        )

        assertEquals(1, capturedRecords.size)
        assertEquals("test_hotpath_tool", capturedRecords[0].toolName)

        ledger.removeListener(listener)

        ledger.record(
            toolName = "test_hotpath_tool_2",
            providerId = "test",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 30L,
            isError = false,
            rawArgs = emptyMap(),
        )

        // No new records should be captured after removal
        assertEquals(1, capturedRecords.size)
    }
}
