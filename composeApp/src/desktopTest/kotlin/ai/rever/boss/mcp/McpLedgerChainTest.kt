package ai.rever.boss.mcp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integrity tests for the hash chain [McpOperationLedger] writes and [McpOperationLedger.verifyChain]
 * reads back. The cases are the ones an operator actually cares about: an untouched ledger reads
 * intact, a record edited, deleted or reordered after the fact does not, and the things that
 * legitimately break the chain's continuity - a rotation, a discarded oldest backup, a ledger
 * written before integrity tracking - do not read as tampering.
 */
class McpLedgerChainTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempLedgerFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-ledger-chain-test")
                .toFile()
        return File(dir, "mcp-calls.jsonl").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    private fun record(
        ledger: McpOperationLedger,
        toolName: String,
        escalated: Boolean = false,
    ) {
        ledger.record(
            toolName = toolName,
            providerId = "provider",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 1L,
            isError = false,
            rawArgs = mapOf("path" to "/project"),
            escalated = escalated,
        )
        // Persistence is asynchronous: drain the writer so the file asserts below see it.
        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `canonical hash includes every non-chain record field`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "field-coverage")
        val entry = storedRecords(file).single()
        val descriptor = McpOperationRecord.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        // secretRefs and escalated are emitted only when set, so a record without them keeps the
        // pre-feature hash (see the two `preserve the pre-feature canonical hash` tests); coverage
        // of those fields is asserted on a record that carries both.
        val withRefs = entry.copy(secretRefs = listOf("id.password"), escalated = true)
        val canonical = Json.parseToJsonElement(withRefs.canonicalFormForHashing()) as JsonObject
        assertEquals(fields - setOf("hash", "parentHash"), canonical.keys)
    }

    private fun verify(file: File): McpLedgerVerification = McpOperationLedger(ledgerFile = file).verifyChain()

    private fun storedRecords(file: File): List<McpOperationRecord> =
        file
            .readLines()
            .filter { it.isNotBlank() }
            .map { Json.decodeFromString<McpOperationRecord>(it) }

    private fun rewrite(
        file: File,
        lines: List<String>,
    ) {
        file.writeText(lines.joinToString("\n") + "\n")
    }

    private fun backupOf(
        file: File,
        index: Int,
    ): File = File(file.parentFile, "${file.name}.$index")

    @Test
    fun `sha256Hex produces the published SHA-256 vectors`() {
        // Pins the digest to real SHA-256 rather than something that merely looks like a hash, so a
        // hash written by this build is comparable with sha256sum and MessageDigest output.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            McpLedgerChain.sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            McpLedgerChain.sha256Hex("abc"),
        )
    }

    @Test
    fun `each record is chained to the hash of the one before it`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "tool_1")
        record(ledger, "tool_2")
        record(ledger, "tool_3")

        val records = storedRecords(file)
        assertEquals(3, records.size)
        assertTrue(records.all { it.hash != null }, "every persisted record must carry a hash")

        // The first record of a file chains to genesis, and each later one to its predecessor. A
        // record's own hash is excluded from its canonical form, so recomputing from the decoded
        // record reproduces exactly what the writer stored.
        assertEquals(McpLedgerChain.GENESIS_HASH, records[0].parentHash)
        assertEquals(McpLedgerChain.linkHash(McpLedgerChain.GENESIS_HASH, records[0]), records[0].hash)
        assertEquals(records[0].hash, records[1].parentHash)
        assertEquals(McpLedgerChain.linkHash(assertNotNull(records[0].hash), records[1]), records[1].hash)
        assertEquals(records[1].hash, records[2].parentHash)
        assertEquals(McpLedgerChain.linkHash(assertNotNull(records[1].hash), records[2]), records[2].hash)
    }

    @Test
    fun `empty secret references preserve the pre-feature canonical hash`() {
        val record =
            McpOperationRecord(
                id = "legacy-hashed",
                timestamp = 1L,
                toolName = "tool",
                providerId = "provider",
                policyApplied = McpPolicyAction.ALLOW,
                approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                durationMs = 1L,
                isError = false,
                sanitizedArgs = emptyMap(),
            )

        assertTrue("secretRefs" !in record.canonicalFormForHashing())
        assertTrue("secretRefs" in record.copy(secretRefs = listOf("id.password")).canonicalFormForHashing())
    }

    @Test
    fun `an unescalated record preserves the pre-feature canonical hash`() {
        val record =
            McpOperationRecord(
                id = "legacy-hashed",
                timestamp = 1L,
                toolName = "tool",
                providerId = "provider",
                policyApplied = McpPolicyAction.ALLOW,
                approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                durationMs = 1L,
                isError = false,
                sanitizedArgs = emptyMap(),
            )

        // Pinned as text: every record written before the field existed must hash exactly as it did.
        assertEquals(
            """{"id":"legacy-hashed","timestamp":1,"toolName":"tool","providerId":"provider",""" +
                """"policyApplied":"ALLOW","approvalDisposition":"AUTO_ALLOWED","durationMs":1,""" +
                """"isError":false,"sanitizedArgs":{},"errorSnippet":null}""",
            record.canonicalFormForHashing(),
        )
        assertTrue("\"escalated\":true" in record.copy(escalated = true).canonicalFormForHashing())
    }

    @Test
    fun `flipping escalated on a stored record is reported as a break`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "run_command", escalated = true)
        record(ledger, "after")

        val lines = file.readLines().toMutableList()
        val stored = Json.decodeFromString<McpOperationRecord>(lines[0])
        assertTrue(stored.escalated, "the flag must survive the round trip to disk: ${lines[0]}")
        assertEquals("intact", verify(file).verdict)

        // Hiding that a destructive call was escalated is exactly the edit an audit must catch.
        lines[0] = Json.encodeToString(stored.copy(escalated = false))
        rewrite(file, lines)

        val broken = assertNotNull(verify(file).firstBreak)
        assertEquals(McpLedgerBreakReason.RECORD_ALTERED, broken.reason)
        assertEquals(1, broken.lineNumber)
        assertEquals(stored.id, broken.recordId)
    }

    @Test
    fun `an untouched ledger verifies intact`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        repeat(5) { record(ledger, "tool_$it") }

        val verification = verify(file)
        assertEquals("intact", verification.verdict)
        assertEquals(5, verification.totalRecords)
        assertEquals(5, verification.chainedRecords)
        assertEquals(0, verification.unverifiableRecords)
        assertNull(verification.firstBreak)
        assertEquals(file.name, verification.oldestVerifiableFile)
        assertEquals(1, verification.oldestVerifiableLine)
    }

    @Test
    fun `a record edited after the fact is reported as the first break`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        repeat(4) { record(ledger, "tool_$it") }

        val lines = file.readLines().toMutableList()
        val forged = Json.decodeFromString<McpOperationRecord>(lines[1]).copy(toolName = "tool_forged")
        lines[1] = Json.encodeToString(forged)
        rewrite(file, lines)

        val verification = verify(file)
        assertEquals("broken", verification.verdict)
        val broken = assertNotNull(verification.firstBreak)
        assertEquals(McpLedgerBreakReason.RECORD_ALTERED, broken.reason)
        assertEquals(2, broken.recordIndex)
        assertEquals(2, broken.lineNumber)
        assertEquals(file.name, broken.fileName)
        assertEquals(forged.id, broken.recordId)
    }

    @Test
    fun `a record deleted after the fact is reported as the first break`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        repeat(4) { record(ledger, "tool_$it") }

        rewrite(file, file.readLines().filterIndexed { index, _ -> index != 1 })

        val verification = verify(file)
        assertEquals("broken", verification.verdict)
        val broken = assertNotNull(verification.firstBreak)
        // Nothing was edited; the record now sitting where the deleted one was chains elsewhere.
        assertEquals(McpLedgerBreakReason.LINK_BROKEN, broken.reason)
        assertEquals(2, broken.recordIndex)
    }

    @Test
    fun `two swapped records are reported as the first break`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        repeat(4) { record(ledger, "tool_$it") }

        val lines = file.readLines().toMutableList()
        val second = lines[1]
        lines[1] = lines[2]
        lines[2] = second
        rewrite(file, lines)

        val verification = verify(file)
        assertEquals("broken", verification.verdict)
        assertEquals(2, assertNotNull(verification.firstBreak).recordIndex)
    }

    @Test
    fun `the chain continues across a rotation`() {
        val file = createTempLedgerFile()
        // A low threshold rotates every few records, so the chain has to cross real file boundaries.
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 3)
        repeat(12) { record(ledger, "tool_$it") }
        assertTrue(backupOf(file, 1).exists(), "rotation should have happened")

        val verification = verify(file)
        assertNull(verification.firstBreak, "a rotation must not read as a break")
        assertEquals("intact", verification.verdict)
        assertTrue(verification.files.size > 1, "verification must span the rotated backups")
        assertTrue(verification.totalRecords >= 2, "records should survive the rotation")
        assertEquals(
            verification.totalRecords,
            verification.chainedRecords,
            "every surviving record must verify, including the first one in the oldest backup",
        )
        assertEquals(0, verification.unverifiableRecords)
    }

    @Test
    fun `a discarded oldest backup does not read as a break`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 2)
        repeat(12) { record(ledger, "tool_$it") }

        // Rotation overwrites the oldest backup, so the oldest surviving record's predecessor is
        // gone. That is lost history, not tampering, and the rest of the chain must still check.
        val verification = verify(file)
        assertNull(verification.firstBreak, "a truncated history must not read as tampering")
        assertEquals("intact", verification.verdict)
        assertEquals(verification.totalRecords, verification.chainedRecords)
    }

    @Test
    fun `tampering with the oldest surviving record is still detected`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 3)
        repeat(12) { record(ledger, "tool_$it") }

        // The oldest surviving record's predecessor has been rotated away, so nothing on disk
        // establishes what its parent was. It is still checkable, because it records the parent it
        // chained to - without that, an edit here would be indistinguishable from lost history.
        val oldest = File(file.parentFile, "${file.name}.3")
        assertTrue(oldest.exists(), "the oldest backup should exist")
        val lines = oldest.readLines().toMutableList()
        val forged = Json.decodeFromString<McpOperationRecord>(lines.first()).copy(toolName = "tool_forged")
        lines[0] = Json.encodeToString(forged)
        oldest.writeText(lines.joinToString("\n") + "\n")

        val verification = verify(file)
        assertEquals("broken", verification.verdict)
        val broken = assertNotNull(verification.firstBreak)
        assertEquals(McpLedgerBreakReason.RECORD_ALTERED, broken.reason)
        assertEquals(1, broken.recordIndex)
        assertEquals(oldest.name, broken.fileName)
    }

    @Test
    fun `the chain survives a second ledger instance reading the file back`() {
        val file = createTempLedgerFile()
        val first = McpOperationLedger(ledgerFile = file)
        record(first, "tool_before_restart")
        record(first, "tool_before_restart_2")

        // A second instance stands in for a restarted process: it holds no in-memory chain head, so
        // it has to recover one from the file to avoid starting a second chain.
        val second = McpOperationLedger(ledgerFile = file)
        record(second, "tool_after_restart")

        val verification = verify(file)
        assertEquals("intact", verification.verdict)
        assertEquals(3, verification.totalRecords)
        assertEquals(3, verification.chainedRecords)
        assertEquals(0, verification.unverifiableRecords)
        assertNull(verification.firstBreak)
    }

    @Test
    fun `records written before integrity tracking are unverifiable rather than a break`() {
        val file = createTempLedgerFile()
        // A line from a build that predates the hash field: no "hash" key at all.
        file.writeText(
            """{"id":"legacy","timestamp":1,"toolName":"tool_old","providerId":"provider",""" +
                """"policyApplied":"ALLOW","approvalDisposition":"AUTO_ALLOWED","durationMs":1,""" +
                """"isError":false,"sanitizedArgs":{}}""" + "\n",
        )

        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "tool_new_1")
        record(ledger, "tool_new_2")

        val verification = verify(file)
        assertEquals("intact", verification.verdict)
        assertEquals(3, verification.totalRecords)
        assertEquals(1, verification.unverifiableRecords)
        assertEquals(2, verification.chainedRecords)
        // Both the writer and the verifier treat "my predecessor has no hash" as genesis, so the
        // first record written after the upgrade is verifiable, not merely tolerated.
        assertEquals(2, verification.oldestVerifiableLine)
    }

    @Test
    fun `removing hashes from a chained suffix is not reported intact`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        repeat(3) { record(ledger, "tool_$it") }

        val lines = file.readLines().toMutableList()
        val stripped = Json.decodeFromString<McpOperationRecord>(lines.last()).copy(hash = null, parentHash = null)
        lines[lines.lastIndex] = Json.encodeToString(stripped)
        rewrite(file, lines)

        val verification = verify(file)
        assertEquals("unverifiable", verification.verdict)
        assertEquals(1, verification.unverifiableSuffixRecords)
    }

    @Test
    fun `an empty ledger is not reported intact`() {
        val file = createTempLedgerFile().apply { writeText("") }

        val verification = verify(file)
        assertEquals("unverifiable", verification.verdict)
        assertEquals(0, verification.chainedRecords)
    }

    @Test
    fun `a malformed line fails verification instead of being skipped`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "tool_1")
        record(ledger, "tool_2")

        val lines = file.readLines().toMutableList()
        lines[1] = "{not json"
        rewrite(file, lines)

        val failure = assertFailsWith<McpLedgerReadException> { verify(file) }
        assertTrue(
            failure.message?.contains("line 2") == true,
            "the message must name the offending line: ${failure.message}",
        )
    }

    @Test
    fun `a missing rotation is reported as incomplete coverage`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 3)
        repeat(12) { record(ledger, "tool_$it") }
        assertTrue(backupOf(file, 2).exists(), "two rotations should exist")
        backupOf(file, 2).delete()

        val verification = verify(file)
        // A hole in the rotation sequence must never be reported as intact: the records either side
        // of it were checked, but the link between them was not.
        assertEquals("incomplete", verification.verdict)
        assertEquals(listOf("${file.name}.2"), verification.coverageGaps)
        assertNull(verification.firstBreak)
        assertTrue(verification.chainedRecords > 0, "records after the gap must still be checked")
    }

    @Test
    fun `a missing active ledger is reported as incomplete when backups survive`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 3)
        repeat(12) { record(ledger, "tool_$it") }
        assertTrue(backupOf(file, 1).exists(), "a rotation should exist")
        file.delete()

        val verification = verify(file)

        assertEquals("incomplete", verification.verdict)
        assertEquals(listOf(file.name), verification.coverageGaps)
        assertTrue(verification.totalRecords > 0, "surviving backups must still be read")
    }
}
