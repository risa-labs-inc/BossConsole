package ai.rever.boss.mcp

import kotlin.collections.emptyList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpHistoryStatsTest {
    private fun fakeRecord(
        toolName: String,
        durationMs: Long,
        isError: Boolean = false,
    ) = McpOperationRecord(
        id = "id-$toolName-$durationMs-${System.nanoTime()}",
        timestamp = 0L,
        toolName = toolName,
        providerId = "test-provider",
        policyApplied = McpPolicyAction.ALLOW,
        approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
        durationMs = durationMs,
        isError = isError,
        sanitizedArgs = emptyMap(),
    )

    @Test
    fun `empty input yields empty stats`() {
        assertEquals(emptyList(), computeToolStats(emptyList()))
    }

    @Test
    fun `single record produces one stats row with matching count and duration`() {
        val stats = computeToolStats(listOf(fakeRecord("git_status", durationMs = 42L)))

        assertEquals(1, stats.size)
        val row = stats.first()
        assertEquals("git_status", row.toolName)
        assertEquals(1, row.callCount)
        assertEquals(0, row.errorCount)
        assertEquals(0.0, row.errorRate)
        assertEquals(42L, row.p50DurationMs)
        assertEquals(42L, row.p95DurationMs)
    }

    @Test
    fun `records for the same tool are grouped together`() {
        val records =
            listOf(
                fakeRecord("git_status", durationMs = 10L),
                fakeRecord("git_status", durationMs = 20L),
                fakeRecord("read_file", durationMs = 5L),
            )

        val stats = computeToolStats(records)

        assertEquals(2, stats.size)
        val gitStats = stats.first { it.toolName == "git_status" }
        assertEquals(2, gitStats.callCount)
    }

    @Test
    fun `error rate reflects fraction of failed calls for that tool`() {
        val records =
            listOf(
                fakeRecord("flaky_tool", durationMs = 10L, isError = true),
                fakeRecord("flaky_tool", durationMs = 10L, isError = false),
                fakeRecord("flaky_tool", durationMs = 10L, isError = false),
                fakeRecord("flaky_tool", durationMs = 10L, isError = false),
            )

        val stats = computeToolStats(records)

        assertEquals(1, stats.first().errorCount)
        assertEquals(0.25, stats.first().errorRate)
    }

    @Test
    fun `p50 and p95 are computed via nearest-rank over that tool's durations`() {
        // 10 records, durations 10..100 in steps of 10 -> nearest-rank p50 = 50, p95 = 100
        val records = (1..10).map { fakeRecord("bench_tool", durationMs = it * 10L) }

        val stats = computeToolStats(records)

        assertEquals(50L, stats.first().p50DurationMs)
        assertEquals(100L, stats.first().p95DurationMs)
    }

    @Test
    fun `results are sorted by error rate descending then call count descending`() {
        val records =
            listOf(
                fakeRecord("reliable_popular", durationMs = 1L),
                fakeRecord("reliable_popular", durationMs = 1L),
                fakeRecord("reliable_popular", durationMs = 1L),
                fakeRecord("flaky_rare", durationMs = 1L, isError = true),
            )

        val stats = computeToolStats(records)

        assertEquals("flaky_rare", stats[0].toolName)
        assertEquals("reliable_popular", stats[1].toolName)
        assertTrue(stats[0].errorRate > stats[1].errorRate)
    }
}
