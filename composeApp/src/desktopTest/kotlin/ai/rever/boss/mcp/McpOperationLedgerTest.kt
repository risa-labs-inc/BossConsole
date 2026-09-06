package ai.rever.boss.mcp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpOperationLedgerTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempLedgerFile(): File {
        val dir = kotlin.io.path.createTempDirectory("mcp-ledger-test").toFile()
        return File(dir, "mcp-calls.jsonl").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `record writes valid JSONL line and updates ring buffer`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, ringBufferCapacity = 10)

        val record =
            ledger.record(
                toolName = "git_status",
                providerId = "git",
                policyApplied = McpPolicyAction.ALLOW,
                approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                durationMs = 45L,
                isError = false,
                rawArgs = mapOf("path" to "/project"),
            )

        assertEquals(1L, ledger.totalCalls.value)
        assertEquals(0L, ledger.totalErrors.value)
        assertEquals(1, ledger.recentOperations.value.size)
        assertEquals(record.id, ledger.recentOperations.value.first().id)

        // Verify JSONL on disk
        val lines = file.readLines()
        assertEquals(1, lines.size)
        val decoded = Json.decodeFromString<McpOperationRecord>(lines.first())
        assertEquals("git_status", decoded.toolName)
        assertEquals("/project", decoded.sanitizedArgs["path"])
        assertFalse(decoded.isError)
    }

    @Test
    fun `record redacts sensitive argument values via LogSanitizer`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        ledger.record(
            toolName = "secret_get",
            providerId = "secret-manager",
            policyApplied = McpPolicyAction.ASK,
            approvalDisposition = McpApprovalDisposition.APPROVED_ONCE,
            durationMs = 20L,
            isError = false,
            rawArgs =
                mapOf(
                    "secret_key" to "my-super-secret-password-12345",
                    "safe_param" to "cluster-east",
                    "auth_token" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
                ),
        )

        val lines = file.readLines()
        val decoded = Json.decodeFromString<McpOperationRecord>(lines.first())
        assertEquals("cluster-east", decoded.sanitizedArgs["safe_param"])

        // Sensitive fields masked
        val secretVal = decoded.sanitizedArgs["secret_key"] ?: ""
        assertTrue(secretVal == "[REDACTED]" || secretVal.contains("***") || secretVal.contains("..."), "secret_key should be sanitized: $secretVal")
    }

    @Test
    fun `file rotates when size threshold is reached`() {
        val file = createTempLedgerFile()
        // Low threshold of 200 bytes to trigger rotation quickly
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 3)

        // Write several records to exceed size
        for (i in 1..10) {
            ledger.record(
                toolName = "tool_$i",
                providerId = "provider",
                policyApplied = McpPolicyAction.ALLOW,
                approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                durationMs = i.toLong(),
                isError = false,
                rawArgs = mapOf("index" to i),
            )
        }

        // Backup file .1 should exist
        val backup1 = File(file.parentFile, "${file.name}.1")
        assertTrue(backup1.exists(), "Backup .1 file should exist after rotation")
    }

    @Test
    fun `ring buffer adheres to capacity limit`() {
        val ledger = McpOperationLedger(ledgerFile = null, ringBufferCapacity = 5)

        for (i in 1..10) {
            ledger.record(
                toolName = "tool_$i",
                providerId = "provider",
                policyApplied = McpPolicyAction.ALLOW,
                approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                durationMs = 10L,
                isError = false,
                rawArgs = emptyMap(),
            )
        }

        assertEquals(10L, ledger.totalCalls.value)
        assertEquals(5, ledger.recentOperations.value.size)
        // Most recent first
        assertEquals("tool_10", ledger.recentOperations.value.first().toolName)
    }
}
