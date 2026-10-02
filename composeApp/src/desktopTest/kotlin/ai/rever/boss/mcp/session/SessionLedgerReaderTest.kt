package ai.rever.boss.mcp.session

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpPolicyAction
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading back the append only ledger: tolerance, bounds, and the in memory merge. */
class SessionLedgerReaderTest {
    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        temps.forEach { it.parentFile?.deleteRecursively() }
        temps.clear()
    }

    private fun ledgerFile(contents: String): File {
        val dir = createTempDirectory("session-ledger-test").toFile()
        return File(dir, "mcp-calls.jsonl").also {
            it.writeText(contents)
            temps += it
        }
    }

    private fun line(
        id: String,
        tool: String,
        timestamp: Long,
        isError: Boolean = false,
    ) = """{"id":"$id","timestamp":$timestamp,"toolName":"$tool","providerId":"p",""" +
        """"policyApplied":"ALLOW","approvalDisposition":"AUTO_ALLOWED","durationMs":5,""" +
        """"isError":$isError,"sanitizedArgs":{"a":"1"}}"""

    @Test
    fun `a real ledger line from disk parses into every field`() {
        // Copied from the actual mcp-calls.jsonl on a running install.
        val raw =
            """{"id":"f492778b-b6c8-46af-8b7c-86961ec81d5c","timestamp":1789759314337,""" +
                """"toolName":"codebase_tree","providerId":"ai.rever.boss.plugin.dynamic.codebase",""" +
                """"policyApplied":"ALLOW","approvalDisposition":"AUTO_ALLOWED","durationMs":0,""" +
                """"isError":true,"sanitizedArgs":{"depth":"2"},"errorSnippet":"Could not scan: "}"""

        val record = assertNotNull(SessionLedgerReader.parse(raw))

        assertEquals("codebase_tree", record.toolName)
        assertEquals(1789759314337L, record.timestamp)
        assertEquals(McpPolicyAction.ALLOW, record.policyApplied)
        assertEquals(McpApprovalDisposition.AUTO_ALLOWED, record.approvalDisposition)
        assertTrue(record.isError)
        assertEquals("2", record.sanitizedArgs["depth"])
        assertEquals("Could not scan: ", record.errorSnippet)
    }

    @Test
    fun `a torn final line is skipped and counted, not thrown`() {
        // The writer appends while this reads, so a half written last line is normal.
        val file = ledgerFile(line("a", "t1", 100) + "\n" + """{"id":"b","timest""")

        val window = SessionLedgerReader.read(file, emptyList(), sinceMs = 0)

        assertEquals(1, window.records.size)
        assertEquals(1, window.malformedLines)
    }

    @Test
    fun `unknown fields and unknown enum values do not lose the record`() {
        // A ledger written by a newer build must still be readable by this one.
        val raw =
            """{"id":"x","timestamp":5,"toolName":"t","providerId":"p","policyApplied":"ALLOW",""" +
                """"approvalDisposition":"SOME_FUTURE_VALUE","durationMs":1,"isError":false,""" +
                """"sanitizedArgs":{},"somethingNew":{"nested":true}}"""

        val record = assertNotNull(SessionLedgerReader.parse(raw))
        assertEquals(
            McpApprovalDisposition.AUTO_ALLOWED,
            record.approvalDisposition,
            "an unknown disposition must not be reported as a governance block",
        )
    }

    @Test
    fun `a record missing its identity is refused rather than guessed at`() {
        assertNull(SessionLedgerReader.parse("""{"timestamp":1,"toolName":"t"}"""))
        assertNull(SessionLedgerReader.parse("""{"id":"a","toolName":"t"}"""))
        assertNull(SessionLedgerReader.parse("not json"))
        assertNull(SessionLedgerReader.parse(""))
    }

    @Test
    fun `records before the window are dropped`() {
        val file = ledgerFile(listOf(line("old", "t", 100), line("new", "t", 900)).joinToString("\n"))

        val window = SessionLedgerReader.read(file, emptyList(), sinceMs = 500)

        assertEquals(listOf("new"), window.records.map { it.id })
    }

    @Test
    fun `in memory records are merged and win over the disk copy`() {
        // The ledger writes to disk asynchronously, so the newest call may exist only in memory.
        // Without this merge, session_review run right after a failure would not see it.
        val file = ledgerFile(line("on-disk", "t", 100))
        val inMemory =
            listOf(
                McpOperationRecord(
                    id = "in-memory-only",
                    timestamp = 200,
                    toolName = "fresh",
                    providerId = "p",
                    policyApplied = McpPolicyAction.ALLOW,
                    approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
                    durationMs = 1,
                    isError = true,
                    sanitizedArgs = emptyMap(),
                    errorSnippet = "just happened",
                ),
            )

        val window = SessionLedgerReader.read(file, inMemory, sinceMs = 0)

        assertEquals(listOf("on-disk", "in-memory-only"), window.records.map { it.id })
    }

    @Test
    fun `a record present both on disk and in memory is counted once`() {
        val file = ledgerFile(line("dup", "t", 100))
        val inMemory = listOf(assertNotNull(SessionLedgerReader.parse(line("dup", "t", 100))))

        val window = SessionLedgerReader.read(file, inMemory, sinceMs = 0)

        assertEquals(1, window.records.size, "the same call must not be double counted")
    }

    @Test
    fun `only the tail of a large ledger is read and the result says so`() {
        // The real file rotates at 10 MB; the host must not pull that in to answer a tool call.
        val builder = StringBuilder()
        var i = 0
        while (builder.length < SessionLedgerReader.MAX_TAIL_BYTES + 200_000) {
            builder.append(line("id-$i", "tool$i", 1_000L + i)).append('\n')
            i++
        }
        val file = ledgerFile(builder.toString())

        val window = SessionLedgerReader.read(file, emptyList(), sinceMs = 0)

        assertTrue(window.truncated, "reading a file over the cap must be reported as truncated")
        assertTrue(window.records.isNotEmpty())
        assertTrue(window.records.size <= SessionLedgerReader.MAX_RECORDS)
        // The newest records are the ones kept.
        assertEquals("id-${i - 1}", window.records.last().id)
    }

    @Test
    fun `a missing or unreadable ledger is an empty session, not a failure`() {
        val missing = File(createTempDirectory("session-none").toFile().also { temps += File(it, "x") }, "nope.jsonl")

        val window = SessionLedgerReader.read(missing, emptyList(), sinceMs = 0)

        assertTrue(window.records.isEmpty())
        assertEquals(0, window.malformedLines)
    }

    @Test
    fun `blank lines are not counted as malformed`() {
        val file = ledgerFile("\n\n" + line("a", "t", 100) + "\n\n")

        val window = SessionLedgerReader.read(file, emptyList(), sinceMs = 0)

        assertEquals(1, window.records.size)
        assertEquals(0, window.malformedLines, "a trailing newline is not a parse error")
    }
}
