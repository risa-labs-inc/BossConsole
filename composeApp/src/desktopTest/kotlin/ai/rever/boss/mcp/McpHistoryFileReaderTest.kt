package ai.rever.boss.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpHistoryFileReaderTest {
    private val tempFiles = mutableListOf<File>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun tempLedgerFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-history-reader-test")
                .toFile()
        return File(dir, "mcp-calls.jsonl").also { tempFiles.add(it) }
    }

    private fun record(
        id: String,
        toolName: String = "git_status",
        timestamp: Long = 0L,
    ) = McpOperationRecord(
        id = id,
        timestamp = timestamp,
        toolName = toolName,
        providerId = "test-provider",
        policyApplied = McpPolicyAction.ALLOW,
        approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
        durationMs = 10L,
        isError = false,
        sanitizedArgs = emptyMap(),
    )

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `returns empty list when no ledger files exist`() {
        val file = tempLedgerFile() // never written
        assertEquals(emptyList(), readHistoricalMcpRecords(file))
    }

    @Test
    fun `reads records from the active file, newest first`() {
        val file = tempLedgerFile()
        file.writeText(
            json.encodeToString(record(id = "a", timestamp = 100L)) + "\n" +
                json.encodeToString(record(id = "b", timestamp = 300L)) + "\n" +
                json.encodeToString(record(id = "c", timestamp = 200L)) + "\n",
        )

        val result = readHistoricalMcpRecords(file)

        assertEquals(listOf("b", "c", "a"), result.map { it.id })
    }

    @Test
    fun `malformed lines are skipped without failing the read`() {
        val file = tempLedgerFile()
        file.writeText(
            json.encodeToString(record(id = "a", timestamp = 100L)) + "\n" +
                "{not valid json at all\n" +
                json.encodeToString(record(id = "b", timestamp = 200L)) + "\n",
        )

        val result = readHistoricalMcpRecords(file)

        assertEquals(listOf("b", "a"), result.map { it.id })
    }

    @Test
    fun `combines active file and rotated backups, deduplicated by id`() {
        val file = tempLedgerFile()
        file.writeText(json.encodeToString(record(id = "active-1", timestamp = 300L)) + "\n")

        val backup1 = File(file.parentFile, "${file.name}.1").also { tempFiles.add(it) }
        backup1.writeText(
            json.encodeToString(record(id = "backup-1", timestamp = 100L)) + "\n" +
                // Same id as the active file's record - simulates a record present in
                // both during a rotation race; must appear only once in the result.
                json.encodeToString(record(id = "active-1", timestamp = 300L)) + "\n",
        )

        val result = readHistoricalMcpRecords(file)

        assertEquals(listOf("active-1", "backup-1"), result.map { it.id })
    }

    @Test
    fun `respects maxRecords cap`() {
        val file = tempLedgerFile()
        file.writeText(
            (1..5).joinToString("\n") { i -> json.encodeToString(record(id = "r$i", timestamp = i.toLong())) } + "\n",
        )

        val result = readHistoricalMcpRecords(file, maxRecords = 2)

        assertEquals(2, result.size)
        assertTrue(result.map { it.id }.containsAll(listOf("r5", "r4")))
    }
}
