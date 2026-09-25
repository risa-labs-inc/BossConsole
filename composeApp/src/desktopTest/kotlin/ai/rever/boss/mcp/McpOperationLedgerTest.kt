package ai.rever.boss.mcp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [McpOperationLedger]: JSONL persistence, argument sanitization, ring-buffer
 * telemetry, rotation, and the owner-only file permissions the ledger file must be
 * created with - its rows carry sanitized operator arguments (paths, URLs,
 * commands), which other local accounts must not be able to read.
 */
class McpOperationLedgerTest {
    private val tempFiles = mutableListOf<File>()

    @Test
    fun `large error transcripts are omitted before regex work`() {
        val ledger = McpOperationLedger()
        val record =
            ledger.record(
                toolName = "test",
                providerId = "test",
                policyApplied = McpPolicyAction.ALLOW,
                approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                durationMs = 0,
                isError = true,
                rawArgs = emptyMap(),
                errorSnippet = "curl ".repeat(30_000),
            )
        assertEquals("[OMITTED: error too large]", record.errorSnippet)
    }

    @Test
    fun `basic auth redaction preserves the original flag separator`() {
        assertEquals("curl --user=[REDACTED]", McpArgumentSanitizer.sanitizeMessage("curl --user=alice:password"))
        assertEquals("curl -u  [REDACTED]", McpArgumentSanitizer.sanitizeMessage("curl -u  alice:password"))
    }

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
    fun `concurrent telemetry order matches durable chain order`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, ringBufferCapacity = 100)
        val workers =
            java.util.concurrent.Executors
                .newFixedThreadPool(8)
        try {
            val writes =
                (1..80).map { index ->
                    workers.submit {
                        ledger.record(
                            toolName = "tool_$index",
                            providerId = "test",
                            policyApplied = McpPolicyAction.ALLOW,
                            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                            durationMs = 1L,
                            isError = false,
                            rawArgs = emptyMap(),
                        )
                    }
                }
            writes.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(ledger.awaitIdle(), "ledger writer never drained")
            val durable = file.readLines().map { Json.decodeFromString<McpOperationRecord>(it).id }
            assertEquals(durable.reversed(), ledger.recentOperations.value.map { it.id })
            assertEquals(80L, ledger.totalCalls.value)
        } finally {
            workers.shutdownNow()
        }
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
        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
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

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
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

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
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

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
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

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
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

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
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

    @Test
    fun `a fresh ledger file is created with owner-only permissions`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        ledger.record(
            toolName = "git_status",
            providerId = "git",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 45L,
            isError = false,
            rawArgs = mapOf("path" to "/project"),
        )

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
        assertTrue(file.isFile, "The first record should have created the ledger file")
        if (posixPermissionsSupported(file)) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
                "The audit ledger must be readable and writable by its owner only",
            )
        } else {
            // No POSIX attribute view (e.g. Windows): the JVM exposes no permission
            // bits to assert there, so the honest fallback is that the append still works.
            assertEquals(1, file.readLines().size, "The append itself must still work")
        }
    }

    @Test
    fun `appending further records keeps the ledger owner-only without losing history`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        repeat(2) { index ->
            ledger.record(
                toolName = "tool_$index",
                providerId = "provider",
                policyApplied = McpPolicyAction.ALLOW,
                approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                durationMs = index.toLong(),
                isError = false,
                rawArgs = mapOf("index" to index),
            )
        }

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
        assertEquals(2, file.readLines().size, "Append semantics: both records must be persisted")
        if (posixPermissionsSupported(file)) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
                "Appends must leave the ledger owner-only",
            )
        }
    }

    @Test
    fun `the first append repairs a permissive legacy ledger`() {
        val file = createTempLedgerFile()
        if (!posixPermissionsSupported(file)) return
        file.writeText("")
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        val ledger = McpOperationLedger(ledgerFile = file)
        ledger.record(
            toolName = "git_status",
            providerId = "git",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 1L,
            isError = false,
            rawArgs = emptyMap(),
        )

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file.toPath()),
            "A ledger created by an older build must not remain readable by other accounts",
        )
        assertEquals(1, file.readLines().size)
    }

    @Test
    fun `the active ledger recreated after rotation is owner-only as well`() {
        val file = createTempLedgerFile()
        // Low threshold of 200 bytes to trigger rotation quickly
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 3)

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

        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
        val backup1 = File(file.parentFile, "${file.name}.1")
        assertTrue(backup1.exists(), "Backup .1 file should exist after rotation")
        assertTrue(file.isFile, "The active ledger should have been recreated after rotation")
        if (posixPermissionsSupported(file)) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
                "A recreated active ledger must be owner-only",
            )
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(backup1.toPath()),
                "A renamed backup keeps the owner-only mode",
            )
        }
    }

    private fun recordCall(
        ledger: McpOperationLedger,
        name: String,
    ) {
        ledger.record(
            toolName = name,
            providerId = "provider",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 1L,
            isError = false,
            rawArgs = emptyMap(),
        )
    }

    @Test
    fun `a burst of records does not serialize callers behind file writes`() {
        val file = createTempLedgerFile()
        val hold = CountDownLatch(1)
        // The writer parks on the gate before its first take, so the file is untouched for
        // the whole burst. A record() that still did its own encode + mkdirs + stat +
        // appendText could not be held this way, and one that blocked on a busy writer would
        // sit here for the full 30s.
        val ledger = McpOperationLedger(ledgerFile = file, writeGate = { hold.await(30, TimeUnit.SECONDS) })
        try {
            val elapsed = measureTimeMillis { repeat(500) { recordCall(ledger, "tool_$it") } }
            assertTrue(
                elapsed < 5_000,
                "500 records took ${elapsed}ms with the writer parked - callers are behind the file again",
            )
        } finally {
            hold.countDown()
        }

        // Everything queued eventually lands, in chain order.
        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
        val lines = file.readLines()
        assertEquals(500, lines.size)
        assertEquals("tool_499", Json.decodeFromString<McpOperationRecord>(lines.last()).toolName)
    }

    @Test
    fun `a flooded writer drops pending records rather than queueing unbounded`() {
        val file = createTempLedgerFile()
        val hold = CountDownLatch(1)
        val ledger =
            McpOperationLedger(
                ledgerFile = file,
                maxPendingWrites = 4,
                writeGate = { hold.await(30, TimeUnit.SECONDS) },
            )
        try {
            repeat(100) { recordCall(ledger, "flood_$it") }
        } finally {
            hold.countDown()
        }

        // 4 queued, 96 dropped - the pending work stays bounded no matter the burst size.
        assertEquals(96L, ledger.droppedWrites.value)
        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
        assertEquals(4, file.readLines().size)
        // The dropped drafts never entered the chain: what is on disk verifies contiguous,
        // not LINK_BROKEN. A loss is lost coverage, not tampering.
        assertEquals("intact", ledger.verifyChain().verdict)
        assertEquals(4, ledger.verifyChain().chainedRecords)
    }

    @Test
    fun `flush persists records queued just before shutdown`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        recordCall(ledger, "tool_at_quit")

        // What the shutdown hook's "flushing MCP operation ledger" step does: a bounded
        // drain so the JVM does not exit with the record still queued.
        assertTrue(ledger.flush(2_000), "ledger writer did not drain in time")
        assertEquals(1, file.readLines().size)
        assertEquals("tool_at_quit", Json.decodeFromString<McpOperationRecord>(file.readLines().single()).toolName)
    }

    @Test
    fun `awaitIdle starts the writer when nothing has been recorded`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)

        // No record() ever ran: the flush must still drain rather than park on a queue
        // take() nobody serves - this is the shape the shutdown step hits on a session
        // that never invoked an MCP tool.
        assertTrue(ledger.awaitIdle(1_000), "awaitIdle stalled with no writer running")
    }

    @Test
    fun `a failed append leaves the on-disk chain contiguous`() {
        val file = createTempLedgerFile()
        val failWrites = AtomicBoolean(false)
        val ledger =
            McpOperationLedger(
                ledgerFile = file,
                writeFailureProbe = {
                    if (failWrites.get()) throw IOException("simulated disk failure")
                },
            )

        recordCall(ledger, "tool_1")
        recordCall(ledger, "tool_2")
        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
        assertEquals(2, file.readLines().size)

        failWrites.set(true)
        recordCall(ledger, "tool_lost_1")
        recordCall(ledger, "tool_lost_2")
        assertTrue(ledger.awaitIdle(), "ledger writer never drained after failed append")
        failWrites.set(false)

        // The failed batch's records are gone from disk and from pending, and counted.
        assertEquals(2L, ledger.droppedWrites.value)
        assertEquals(2, file.readLines().size, "the failed batch must not have appended")
        assertTrue(ledger.pendingWriteIds.value.isEmpty(), "lost records must not stay queued")

        recordCall(ledger, "tool_3")
        assertTrue(ledger.awaitIdle(), "ledger writer never drained after recovery")

        // tool_3 re-chained from the real disk tail (tool_2), so the journal still verifies
        // end to end: a lost write is coverage loss, not a break.
        val verification = ledger.verifyChain()
        assertEquals("intact", verification.verdict)
        assertEquals(3, verification.totalRecords)
        assertEquals(3, verification.chainedRecords)
        assertNull(verification.firstBreak)

        // And the telemetry tells the two states apart: the lost draft kept hash == null
        // while the persisted successor carries the chain link.
        val recent = ledger.recentOperations.value
        val lost = recent.first { it.toolName == "tool_lost_1" }
        assertNull(lost.hash, "a dropped record must not display an integrity hash")
        assertNotNull(recent.first { it.toolName == "tool_3" }.hash)
    }

    /**
     * Whether the JVM exposes a POSIX permission view for this path - the same probe
     * `MicrokernelModePreference.writeModeFile` uses before touching POSIX attributes.
     * False on Windows, where profile-directory ACLs carry the protection instead.
     */
    private fun posixPermissionsSupported(file: File): Boolean =
        Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) != null
}
