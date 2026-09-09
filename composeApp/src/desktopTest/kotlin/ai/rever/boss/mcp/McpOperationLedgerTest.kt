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
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-ledger-test")
                .toFile()
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
        assertEquals(
            record.id,
            ledger.recentOperations.value
                .first()
                .id,
        )

        // Verify JSONL on disk
        val lines = file.readLines()
        assertEquals(1, lines.size)
        val decoded = Json.decodeFromString<McpOperationRecord>(lines.first())
        assertEquals("git_status", decoded.toolName)
        assertEquals("/project", decoded.sanitizedArgs["path"])
        assertFalse(decoded.isError)
    }

    @Test
    fun `record redacts sensitive argument values via McpArgumentSanitizer`() {
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
        assertFalse(secretVal.contains("my-super-secret-password-12345"), "secret_key must be redacted")
        val tokenVal = decoded.sanitizedArgs["auth_token"] ?: ""
        assertFalse(tokenVal.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), "auth_token must be redacted")
    }

    @Test
    fun `a credential-shaped value is masked even under a non-sensitive key name`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        ledger.record(
            toolName = "run_command",
            providerId = "terminal",
            policyApplied = McpPolicyAction.ASK,
            approvalDisposition = McpApprovalDisposition.APPROVED_ONCE,
            durationMs = 5L,
            isError = false,
            rawArgs =
                mapOf(
                    // "id" names nothing sensitive, but the value is a JWT by shape.
                    "id" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc",
                ),
        )

        val lines = file.readLines()
        val decoded = Json.decodeFromString<McpOperationRecord>(lines.first())
        val idVal = decoded.sanitizedArgs["id"] ?: ""
        assertFalse(idVal.contains("eyJzdWIiOiIxMjM0NTY3ODkwIn0"), "JWT must be redacted")
    }

    @Test
    fun `long file paths and commands survive without blind length-based redaction`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        val longFilePath = "/Users/alice/projects/boss-console/composeApp/src/commonMain/App.kt"
        val shellCommand = "git status --porcelain && cargo check --workspace"

        ledger.record(
            toolName = "codebase_read",
            providerId = "codebase",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 12L,
            isError = false,
            rawArgs =
                mapOf(
                    "path" to longFilePath,
                    "command" to shellCommand,
                ),
        )

        val lines = file.readLines()
        val decoded = Json.decodeFromString<McpOperationRecord>(lines.first())
        assertEquals(longFilePath, decoded.sanitizedArgs["path"], "Long file path must be preserved for audit")
        assertEquals(shellCommand, decoded.sanitizedArgs["command"], "Shell command must be preserved for audit")
    }

    @Test
    fun `errorSnippet is sanitized before ledger persistence`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        ledger.record(
            toolName = "k8s_delete",
            providerId = "kubernetes",
            policyApplied = McpPolicyAction.ASK,
            approvalDisposition = McpApprovalDisposition.DENIED_BY_OPERATOR,
            durationMs = 0L,
            isError = true,
            rawArgs = emptyMap(),
            errorSnippet = "Failed connecting with Bearer secret-token-ey1234567890",
        )

        val lines = file.readLines()
        val decoded = Json.decodeFromString<McpOperationRecord>(lines.first())
        val errorText = decoded.errorSnippet ?: ""
        assertFalse(errorText.contains("secret-token-ey1234567890"), "errorSnippet must have credentials sanitized")
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
        assertEquals(
            "tool_10",
            ledger.recentOperations.value
                .first()
                .toolName,
        )
    }

    @Test
    fun `nested JSON secrets and malformed input never enter audit arguments`() {
        val nested = McpArgumentSanitizer.parseArguments("""{"config":{"password":"sentinel"},"auth":["sentinel"]}""")
        assertFalse(McpArgumentSanitizer.sanitize(nested).toString().contains("sentinel"))
        val malformed = McpArgumentSanitizer.parseArguments("{password:sentinel}")
        assertFalse(McpArgumentSanitizer.sanitize(malformed).toString().contains("sentinel"))
    }
}
