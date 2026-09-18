package ai.rever.boss.cli

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpPolicyAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `boss mcp ledger verify --anchor`.
 *
 * A hash chain proves nothing was edited, inserted or removed from the middle, but a chain with its
 * newest records deleted is still a valid chain. So before this, truncating the end of the audit
 * trail - the records documenting what an agent just did - verified as `intact`. An anchor is a head
 * hash the operator wrote down outside the file; the chain no longer containing it is the one signal
 * truncation cannot forge.
 */
class McpLedgerAnchorTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempLedgerFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-ledger-anchor-test")
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
    ) {
        ledger.record(
            toolName = toolName,
            providerId = "provider",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
            durationMs = 7L,
            isError = false,
            rawArgs = mapOf("path" to "/project"),
        )
    }

    private fun recordMany(
        ledger: McpOperationLedger,
        prefix: String,
        count: Int,
    ) = repeat(count) { record(ledger, "${prefix}_${it + 1}") }

    private fun hashOfRecord(
        ledger: McpOperationLedger,
        oneBased: Int,
    ): String = assertNotNull(ledger.readEntries()[oneBased - 1].record.hash)

    private fun okText(outcome: McpLedgerOutcome): String = assertIs<McpLedgerOutcome.Ok>(outcome).text

    private fun failureMessage(outcome: McpLedgerOutcome): String = assertIs<McpLedgerOutcome.Failed>(outcome).message

    private fun verify(
        file: File,
        anchor: String? = null,
        json: Boolean = false,
    ) = McpLedgerCli.verify(file.absolutePath, json, anchor)

    @Test
    fun `verify prints the head hash so an operator has something to anchor to`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 3)
        val head = hashOfRecord(ledger, 3)

        val text = okText(verify(file))
        val json = Json.parseToJsonElement(okText(verify(file, json = true))).jsonObject

        assertTrue(text.contains("Head:    $head (record 3 of 3)"), text)
        assertEquals(head, json.getValue("headHash").jsonPrimitive.content)
        assertEquals("3", json.getValue("headRecordIndex").jsonPrimitive.content)
    }

    @Test
    fun `an anchor still in the chain verifies and says how many records were written since`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 5)

        val text = okText(verify(file, anchor = hashOfRecord(ledger, 3)))

        assertTrue(text.contains("Anchor:  found at record 3, with 2 verified record(s) written since"), text)
    }

    @Test
    fun `truncating the end of the ledger still verifies as intact without an anchor`() {
        // This is the gap. It is asserted so the anchor tests below are known to be closing something
        // real, and so the day a plain verify learns to see this the test says so.
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 5)
        file.writeText(file.readLines().dropLast(2).joinToString("\n") + "\n")

        assertTrue(okText(verify(file)).contains("intact"), "a valid chain missing its newest records looks fine")
    }

    @Test
    fun `an anchor detects records removed from the end of the ledger`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 5)
        val anchor = hashOfRecord(ledger, 5)
        file.writeText(file.readLines().dropLast(2).joinToString("\n") + "\n")

        val message = failureMessage(verify(file, anchor = anchor))

        assertTrue(message.contains("ANCHOR MISSING"), message)
        assertTrue(message.contains("NOT FOUND"), message)
        assertTrue(message.contains("Head:"), "the surviving head is still reported:\n$message")
    }

    @Test
    fun `an anchor detects a ledger whose whole history was replaced`() {
        val file = createTempLedgerFile()
        val original = McpOperationLedger(ledgerFile = file)
        recordMany(original, "tool", 4)
        val anchor = hashOfRecord(original, 4)
        file.delete()
        // A perfectly valid chain, written by the real writer, of records that never happened.
        recordMany(McpOperationLedger(ledgerFile = file), "innocent", 4)

        assertTrue(okText(verify(file)).contains("intact"), "the replacement is a valid chain in its own right")
        assertTrue(failureMessage(verify(file, anchor = anchor)).contains("ANCHOR MISSING"))
    }

    @Test
    fun `an anchor in a rotated backup is found`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 20)
        recordMany(ledger, "tool", 12)
        assertTrue(File(file.absolutePath + ".1").exists(), "the test needs a rotation to mean anything")

        val text = okText(verify(file, anchor = hashOfRecord(ledger, 1)))

        assertTrue(text.contains("Anchor:  found at record 1, with 11 verified record(s) written since"), text)
    }

    @Test
    fun `an anchor cannot rescue a broken chain`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 4)
        val anchor = hashOfRecord(ledger, 4)
        val lines = file.readLines().toMutableList()
        lines[1] = lines[1].replace("\"tool_2\"", "\"tool_2_forged\"")
        file.writeText(lines.joinToString("\n") + "\n")

        val message = failureMessage(verify(file, anchor = anchor))

        assertTrue(message.contains("BROKEN"), message)
        assertTrue(message.contains("Anchor:  not checked"), "a break is not called a truncation:\n$message")
    }

    @Test
    fun `a full uppercase hash is accepted and a short prefix is refused`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 2)
        val head = hashOfRecord(ledger, 2)

        assertTrue(okText(verify(file, anchor = head.uppercase())).contains("Anchor:  found"))
        for (bad in listOf(head.take(12), head + "0", "not-a-hash", "", head.replaceRange(0, 1, "z"))) {
            val message = failureMessage(verify(file, anchor = bad))
            assertTrue(message.contains("--anchor"), "refused before the ledger is read: '$bad' -> $message")
        }
    }

    @Test
    fun `the JSON report carries the anchor result`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 3)
        val anchor = hashOfRecord(ledger, 2)

        val json = Json.parseToJsonElement(okText(verify(file, anchor = anchor, json = true))).jsonObject

        assertEquals(anchor, json.getValue("anchorHash").jsonPrimitive.content)
        assertEquals("2", json.getValue("anchorRecordIndex").jsonPrimitive.content)
        assertEquals("intact", json.getValue("verdict").jsonPrimitive.content)
    }

    @Test
    fun `a missing anchor reports the anchor-missing verdict in JSON and exits as a failure`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        recordMany(ledger, "tool", 3)
        val anchor = hashOfRecord(ledger, 3)
        file.writeText(file.readLines().dropLast(1).joinToString("\n") + "\n")

        val json = Json.parseToJsonElement(failureMessage(verify(file, anchor = anchor, json = true))).jsonObject

        assertEquals("anchor-missing", json.getValue("verdict").jsonPrimitive.content)
    }
}
