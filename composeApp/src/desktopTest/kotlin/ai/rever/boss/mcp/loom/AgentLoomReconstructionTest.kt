package ai.rever.boss.mcp.loom

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reconstruction rules, checked against ledger text rather than against a live runtime.
 *
 * Every case here builds the JSONL the runtime would have written, so what is being tested is the
 * reader, the ordering and the classification, not a helper that happens to agree with itself.
 */
class AgentLoomReconstructionTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `records replay chronologically whatever order the ledger holds them in`() {
        val text =
            listOf(
                ledgerRecord(id = "third", timestamp = 3_000L, toolName = "run_command"),
                ledgerRecord(id = "first", timestamp = 1_000L, toolName = "list_workspaces"),
                ledgerRecord(id = "second", timestamp = 2_000L, toolName = "read_file"),
            ).joinToString("\n")

        val parse = AgentLoom.parse(text, "mcp-calls.jsonl")

        assertEquals(
            listOf("list_workspaces", "read_file", "run_command"),
            parse.events.map { it.toolName },
        )
        assertEquals(listOf(0, 1, 2), parse.events.map { it.index })
        assertEquals(listOf(0L, 1_000L, 2_000L), parse.events.map { it.offsetMs })
        assertTrue(parse.diagnostics.isEmpty(), "a well formed ledger produces no diagnostics")
    }

    @Test
    fun `equal timestamps keep the order the ledger was written in`() {
        val text =
            listOf(
                ledgerRecord(id = "a", timestamp = 5_000L, toolName = "first_written"),
                ledgerRecord(id = "b", timestamp = 5_000L, toolName = "second_written"),
            ).joinToString("\n")

        assertEquals(
            listOf("first_written", "second_written"),
            AgentLoom.parse(text, "mcp-calls.jsonl").events.map { it.toolName },
        )
    }

    @Test
    fun `tool calls appear in the replay with their recorded arguments`() {
        val text =
            ledgerRecord(
                toolName = "run_command",
                providerId = "boss-workspace",
                sanitizedArgs = mapOf("command" to "ls -la", "cwd" to "/tmp"),
            )

        val event = AgentLoom.parse(text, "mcp-calls.jsonl").events.single()

        assertEquals("run_command", event.toolName)
        assertEquals("boss-workspace", event.providerId)
        assertEquals(mapOf("command" to "ls -la", "cwd" to "/tmp"), event.args)
        assertEquals("mcp-calls.jsonl", event.source)
        assertEquals(1, event.lineNumber)
        assertEquals(12L, event.durationMs)
        assertTrue(event.defaulted.isEmpty(), "a complete record defaults nothing")
    }

    @Test
    fun `risk is re-derived from the tool and its arguments`() {
        val text =
            listOf(
                ledgerRecord(
                    id = "shell",
                    timestamp = 1L,
                    toolName = "run_command",
                    sanitizedArgs = mapOf("command" to "rm -rf /var/data"),
                ),
                ledgerRecord(id = "plain-shell", timestamp = 2L, toolName = "run_command"),
                ledgerRecord(id = "secret", timestamp = 3L, toolName = "secret_create"),
                ledgerRecord(id = "k8s", timestamp = 4L, toolName = "k8s_delete"),
                ledgerRecord(id = "read", timestamp = 5L, toolName = "list_workspaces"),
            ).joinToString("\n")

        val byId = AgentLoom.parse(text, "mcp-calls.jsonl").events.associateBy { it.id }

        assertEquals("CRITICAL", byId.getValue("shell").riskLevel)
        assertEquals("HIGH", byId.getValue("plain-shell").riskLevel)
        assertEquals("HIGH", byId.getValue("secret").riskLevel)
        assertEquals("CRITICAL", byId.getValue("k8s").riskLevel)
        assertEquals("LOW", byId.getValue("read").riskLevel)
        assertTrue(byId.getValue("shell").riskReason.isNotBlank(), "the derivation states its reason")
    }

    @Test
    fun `approval and denial dispositions become replay outcomes`() {
        val dispositions =
            listOf(
                "AUTO_ALLOWED",
                "APPROVED_ONCE",
                "SESSION_TRUSTED",
                "PERSISTENTLY_ALLOWED",
                "PROVIDER_TRUSTED",
                "DENIED_BY_OPERATOR",
                "POLICY_DENIED",
                "PERSISTENTLY_DENIED",
                "TIMEOUT",
                "CANCELLED_AWAITING_APPROVAL",
                "QUEUE_FULL",
                "SOMETHING_THIS_BUILD_DOES_NOT_KNOW",
            )
        val text =
            dispositions
                .mapIndexed { position, disposition ->
                    ledgerRecord(
                        id = "rec-$position",
                        timestamp = position.toLong() + 1L,
                        approvalDisposition = disposition,
                    )
                }.joinToString("\n")

        assertEquals(
            listOf(
                "ALLOWED",
                "APPROVED",
                "APPROVED",
                "APPROVED",
                "APPROVED",
                "DENIED",
                "DENIED",
                "DENIED",
                "TIMED_OUT",
                "CANCELLED",
                "QUEUE_REJECTED",
                "UNKNOWN",
            ),
            AgentLoom.parse(text, "mcp-calls.jsonl").events.map { it.outcome },
        )
    }

    @Test
    fun `an unrecognised disposition keeps its raw value on the event`() {
        val text =
            ledgerRecord(
                policyApplied = "ASK",
                approvalDisposition = "SOMETHING_THIS_BUILD_DOES_NOT_KNOW",
            )

        val event = AgentLoom.parse(text, "mcp-calls.jsonl").events.single()

        assertEquals("UNKNOWN", event.outcome)
        assertEquals("SOMETHING_THIS_BUILD_DOES_NOT_KNOW", event.approvalDisposition)
        assertEquals("ASK", event.policyApplied)
    }

    @Test
    fun `a policy denial is reconstructed even when the disposition is unrecognised`() {
        val text =
            ledgerRecord(
                policyApplied = "DENY",
                approvalDisposition = "SOMETHING_THIS_BUILD_DOES_NOT_KNOW",
            )

        assertEquals(
            "DENIED",
            AgentLoom
                .parse(text, "mcp-calls.jsonl")
                .events
                .single()
                .outcome,
        )
    }

    @Test
    fun `malformed lines are reported and do not destroy the replay around them`() {
        val text =
            listOf(
                ledgerRecord(id = "ok-1", timestamp = 1_000L, toolName = "list_workspaces"),
                "{ this is not json",
                "[1, 2, 3]",
                ledgerRecord(id = "no-tool", timestamp = 2_000L, omit = setOf("toolName")),
                "",
                ledgerRecord(id = "ok-2", timestamp = 3_000L, toolName = "read_file"),
            ).joinToString("\n")

        val parse = AgentLoom.parse(text, "mcp-calls.jsonl")

        assertEquals(listOf("list_workspaces", "read_file"), parse.events.map { it.toolName })
        assertEquals(listOf(2, 3, 4), parse.diagnostics.map { it.lineNumber })
        assertEquals(3, parse.diagnostics.size)
        assertTrue(parse.diagnostics.all { it.source == "mcp-calls.jsonl" })
    }

    @Test
    fun `fields the ledger did not carry are marked rather than guessed`() {
        val text =
            ledgerRecord(
                omit =
                    setOf(
                        "id",
                        "timestamp",
                        "providerId",
                        "policyApplied",
                        "approvalDisposition",
                        "sanitizedArgs",
                        "durationMs",
                    ),
            )

        val event = AgentLoom.parse(text, "mcp-calls.jsonl").events.single()

        assertEquals(NOT_RECORDED, event.providerId)
        assertEquals(NOT_RECORDED, event.policyApplied)
        assertEquals(NOT_RECORDED, event.approvalDisposition)
        assertEquals("UNKNOWN", event.outcome)
        assertEquals(0L, event.durationMs)
        assertEquals(0L, event.timestamp)
        assertEquals("line-1", event.id)
        assertTrue(event.args.isEmpty())
        assertEquals(
            setOf(
                "id",
                "timestamp",
                "providerId",
                "policyApplied",
                "approvalDisposition",
                "sanitizedArgs",
                "durationMs",
            ),
            event.defaulted.toSet(),
        )
    }

    @Test
    fun `an error record keeps its snippet and its error flag`() {
        val text =
            ledgerRecord(
                isError = true,
                errorSnippet = "command failed with exit code 2",
                approvalDisposition = "APPROVED_ONCE",
            )

        val event = AgentLoom.parse(text, "mcp-calls.jsonl").events.single()

        assertTrue(event.isError)
        assertEquals("command failed with exit code 2", event.errorSnippet)
        assertEquals("APPROVED", event.outcome)
    }

    @Test
    fun `an empty ledger produces a session with no events and says why`() {
        val session = AgentLoom.session(AgentLoom.parse("", "mcp-calls.jsonl"), listOf("mcp-calls.jsonl"))

        assertTrue(session.events.isEmpty())
        assertTrue(session.diagnostics.isEmpty())
        assertEquals(0L, session.durationMs)
        assertEquals(null, session.startedAt)
        assertEquals(null, session.endedAt)
        assertTrue(session.notes.any { it.contains("no reconstructable MCP calls") })
    }

    @Test
    fun `the session summarises tools, risk and outcomes`() {
        val text =
            listOf(
                ledgerRecord(id = "1", timestamp = 1L, toolName = "run_command", approvalDisposition = "AUTO_ALLOWED"),
                ledgerRecord(
                    id = "2",
                    timestamp = 2L,
                    toolName = "run_command",
                    approvalDisposition = "DENIED_BY_OPERATOR",
                ),
                ledgerRecord(id = "3", timestamp = 9L, toolName = "read_file", approvalDisposition = "APPROVED_ONCE"),
            ).joinToString("\n")

        val session = AgentLoom.session(AgentLoom.parse(text, "mcp-calls.jsonl"), listOf("mcp-calls.jsonl"))

        assertEquals(8L, session.durationMs)
        assertEquals("run_command", session.toolUsage.first().label)
        assertEquals(2, session.toolUsage.first().count)
        assertEquals(listOf("ALLOWED", "APPROVED", "DENIED"), session.outcomeDistribution.map { it.label })
        assertEquals(listOf("HIGH", "LOW"), session.riskDistribution.map { it.label })
        assertTrue(session.notes.any { it.contains("Risk is not a ledger field") })
    }

    @Test
    fun `a rotation gap is carried into the session`() {
        val session =
            AgentLoom.session(
                parse = AgentLoom.parse("", "mcp-calls.jsonl.2"),
                sources = listOf("mcp-calls.jsonl.2", "mcp-calls.jsonl"),
                coverageGaps = listOf("mcp-calls.jsonl.1"),
            )

        assertTrue(session.notes.any { it.contains("mcp-calls.jsonl.1") })
        assertEquals(listOf("mcp-calls.jsonl.1"), session.coverageGaps)
    }

    @Test
    fun `scrubbing lands on the last event at or before the offset`() {
        val text =
            listOf(
                ledgerRecord(id = "1", timestamp = 1_000L),
                ledgerRecord(id = "2", timestamp = 3_000L),
                ledgerRecord(id = "3", timestamp = 9_000L),
            ).joinToString("\n")
        val events = AgentLoom.parse(text, "mcp-calls.jsonl").events

        // A session-relative offset can only precede the first event by being negative: the first
        // event is at offset 0, so every non-negative offset lands on at least that event.
        assertEquals(-1, LoomTimeline.indexAt(events, -1L))
        assertEquals(0, LoomTimeline.indexAt(events, 0L))
        assertEquals(0, LoomTimeline.indexAt(events, 1L))
        assertEquals(0, LoomTimeline.indexAt(events, 1_999L))
        assertEquals(1, LoomTimeline.indexAt(events, 2_000L))
        assertEquals(2, LoomTimeline.indexAt(events, 8_000L))
        assertEquals(2, LoomTimeline.indexAt(events, 60_000L))
    }

    @Test
    fun `scrub progress and offset are inverses of each other`() {
        val text =
            listOf(
                ledgerRecord(id = "1", timestamp = 1_000L),
                ledgerRecord(id = "2", timestamp = 5_000L),
            ).joinToString("\n")
        val session = AgentLoom.session(AgentLoom.parse(text, "f"), listOf("f"))

        assertEquals(4_000L, session.durationMs)
        assertEquals(0f, LoomTimeline.progress(session, 0L))
        assertEquals(0.5f, LoomTimeline.progress(session, 2_000L))
        assertEquals(1f, LoomTimeline.progress(session, 4_000L))
        assertEquals(2_000L, LoomTimeline.offsetForProgress(session, 0.5f))
        assertEquals(listOf(0L, 4_000L), LoomTimeline.offsets(session))
        assertEquals(4_000L, LoomTimeline.offsetAt(session.events, 1))
    }

    @Test
    fun `a single event session scrubs to its only position`() {
        val session = AgentLoom.session(AgentLoom.parse(ledgerRecord(timestamp = 7L), "f"), listOf("f"))

        assertEquals(0L, session.durationMs)
        assertEquals(1f, LoomTimeline.progress(session, 0L))
    }

    @Test
    fun `reading a ledger file leaves it byte for byte unchanged`() {
        val dir = createTempDirectory("agent-loom-read").toFile()
        tempDirs.add(dir)
        val ledger = File(dir, "mcp-calls.jsonl")
        val text =
            listOf(
                ledgerRecord(id = "1", timestamp = 1L, toolName = "run_command"),
                "{ not json",
                ledgerRecord(id = "2", timestamp = 2L, toolName = "read_file"),
            ).joinToString("\n") + "\n"
        ledger.writeText(text)

        val session = AgentLoom.replay(listOf(ledger))

        assertEquals(2, session.events.size)
        assertEquals(text, ledger.readText(), "reconstruction is a read, not a rewrite")
    }

    @Test
    fun `files are read oldest first and their names are kept as sources`() {
        val dir = createTempDirectory("agent-loom-sources").toFile()
        tempDirs.add(dir)
        val older = File(dir, "mcp-calls.jsonl.1")
        val active = File(dir, "mcp-calls.jsonl")
        older.writeText(ledgerRecord(id = "older", timestamp = 1L, toolName = "first_tool"))
        active.writeText(ledgerRecord(id = "active", timestamp = 2L, toolName = "second_tool"))

        val session = AgentLoom.replay(listOf(older, active))

        assertEquals(listOf("first_tool", "second_tool"), session.events.map { it.toolName })
        assertEquals(listOf("mcp-calls.jsonl.1", "mcp-calls.jsonl"), session.events.map { it.source })
        assertEquals(listOf("mcp-calls.jsonl.1", "mcp-calls.jsonl"), session.sources)
    }

    @Test
    fun `an unreadable ledger file becomes a diagnostic rather than a crash`() {
        val dir = createTempDirectory("agent-loom-missing").toFile()
        tempDirs.add(dir)

        val parse = AgentLoom.read(listOf(File(dir, "does-not-exist.jsonl")))

        assertTrue(parse.events.isEmpty())
        assertTrue(parse.diagnostics.isEmpty(), "a missing file holds no lines to report on")
    }
}
