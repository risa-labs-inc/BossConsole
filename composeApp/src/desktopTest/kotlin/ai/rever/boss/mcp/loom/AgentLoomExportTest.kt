package ai.rever.boss.mcp.loom

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The artifact itself: that it carries the replay, and that it carries nothing else.
 *
 * "Standalone" is asserted rather than assumed. The viewer has to work from a `file://` path on a
 * machine that has never run BOSS, so a test that only checks the HTML is non-empty would not notice
 * the day someone adds a CDN link or a fetch call.
 */
class AgentLoomExportTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `the artifact embeds the session and references nothing outside itself`() {
        val session = sampleSession()

        val html = AgentLoomExporter.html(session)

        assertFalse(html.contains(AgentLoomViewerTemplate.DATA_PLACEHOLDER), "the data block was filled in")
        assertFalse(html.contains("http://"), "no remote reference of any kind")
        assertFalse(html.contains("https://"), "no remote reference of any kind")
        assertFalse(html.contains("<link "), "no external stylesheet")
        assertFalse(html.contains("src="), "no external script or image")
        assertFalse(html.contains("fetch("), "no network call")
        assertFalse(html.contains("XMLHttpRequest"), "no network call")
        assertFalse(html.contains("sendBeacon"), "no telemetry")
        assertEquals(2, html.split("</script>").size - 1, "one data block and one viewer script")
        assertEquals(session, decodeSession(html))
    }

    @Test
    fun `every replayed call and its governance is present in the artifact`() {
        val session = sampleSession()

        val html = AgentLoomExporter.html(session)

        session.events.forEach { event ->
            assertTrue(html.contains(event.toolName), "the artifact names ${event.toolName}")
            assertTrue(html.contains(event.approvalDisposition), "the artifact records ${event.approvalDisposition}")
        }
        assertEquals(session.events.map { it.toolName }, decodeSession(html).events.map { it.toolName })
    }

    @Test
    fun `an empty ledger exports a valid artifact with an empty state`() {
        val session = AgentLoom.session(AgentLoom.parse("", "mcp-calls.jsonl"), listOf("mcp-calls.jsonl"))

        val html = AgentLoomExporter.html(session)

        assertTrue(html.contains("Nothing to replay"), "the viewer explains the empty session")
        assertFalse(html.contains(AgentLoomViewerTemplate.DATA_PLACEHOLDER))
        val decoded = decodeSession(html)
        assertTrue(decoded.events.isEmpty())
        assertEquals(listOf("mcp-calls.jsonl"), decoded.sources)
        assertTrue(html.startsWith("<!doctype html>"), "still a document a browser will open")
    }

    @Test
    fun `a ledger argument cannot break out of the embedded data block`() {
        val injection = "</script><script>alert(1)</script>"
        val text =
            ledgerRecord(
                toolName = "run_command",
                sanitizedArgs = mapOf("command" to injection),
            )
        val session = AgentLoom.session(AgentLoom.parse(text, "mcp-calls.jsonl"), listOf("mcp-calls.jsonl"))

        val html = AgentLoomExporter.html(session)

        assertFalse(html.contains(injection), "the raw tag sequence is not in the document")
        assertEquals(2, html.split("</script>").size - 1, "the injection did not add a script boundary")
        assertEquals(
            injection,
            decodeSession(html)
                .events
                .single()
                .args
                .getValue("command"),
        )
    }

    @Test
    fun `export writes exactly one file that decodes back to the same session`() {
        val dir = createTempDirectory("agent-loom-export").toFile()
        tempDirs.add(dir)
        val out = File(dir, "agent-loom-session.html")
        val session = sampleSession()

        val exported = AgentLoomExporter.export(session, out)

        assertTrue(out.isFile)
        assertEquals(out.length(), exported.bytes)
        assertEquals(session, decodeSession(out.readText()))
    }

    @Test
    fun `exporting the runtime ledger layout reads rotations and writes the artifact`() {
        val dir = createTempDirectory("agent-loom-ledger").toFile()
        tempDirs.add(dir)
        val older = File(dir, "mcp-calls.jsonl.1")
        val active = File(dir, "mcp-calls.jsonl")
        older.writeText(ledgerRecord(id = "older", timestamp = 1_000L, toolName = "list_workspaces"))
        active.writeText(
            listOf(
                ledgerRecord(id = "active-1", timestamp = 2_000L, toolName = "read_file"),
                "{ a line the ledger should never have held",
                ledgerRecord(
                    id = "active-2",
                    timestamp = 3_000L,
                    toolName = "write_file",
                    approvalDisposition = "DENIED_BY_OPERATOR",
                ),
            ).joinToString("\n"),
        )
        val out = File(File(dir, "out"), "agent-loom-session.html")

        val exported = AgentLoomExporter.exportLedger(active, out, generatedAt = 42L)

        assertEquals(42L, exported.session.generatedAt)
        assertEquals(listOf("mcp-calls.jsonl.1", "mcp-calls.jsonl"), exported.session.sources)
        assertEquals(
            listOf("list_workspaces", "read_file", "write_file"),
            exported.session.events.map { it.toolName },
        )
        assertEquals(1, exported.session.diagnostics.size)
        assertTrue(out.isFile, "the exporter creates the output directory")
        assertEquals(exported.session, decodeSession(out.readText()))
    }

    @Test
    fun `a ledger that is not there exports an empty state naming the file`() {
        val dir = createTempDirectory("agent-loom-absent").toFile()
        tempDirs.add(dir)
        val missing = File(dir, "mcp-calls.jsonl")
        val out = File(dir, "agent-loom-session.html")

        val exported = AgentLoomExporter.exportLedger(missing, out)

        assertTrue(exported.session.events.isEmpty())
        assertEquals(listOf("mcp-calls.jsonl"), exported.session.sources)
        assertTrue(out.readText().contains("Nothing to replay"))
    }

    @Test
    fun `the viewer never renders a ledger value as markup`() {
        val text =
            ledgerRecord(
                toolName = "run_command",
                sanitizedArgs = mapOf("command" to "<img src=x onerror=alert(1)>"),
            )
        val session = AgentLoom.session(AgentLoom.parse(text, "mcp-calls.jsonl"), listOf("mcp-calls.jsonl"))

        val html = AgentLoomExporter.html(session)

        assertFalse(html.contains("<img src=x"), "the value is data, not markup")
        assertTrue(html.contains("esc("), "the viewer escapes ledger values before rendering them")
        assertEquals(
            "<img src=x onerror=alert(1)>",
            decodeSession(html)
                .events
                .single()
                .args
                .getValue("command"),
        )
    }

    private fun sampleSession(): LoomSession {
        val text =
            listOf(
                ledgerRecord(id = "1", timestamp = 1_000L, toolName = "list_workspaces"),
                ledgerRecord(id = "2", timestamp = 2_000L, toolName = "read_file"),
                ledgerRecord(
                    id = "3",
                    timestamp = 3_000L,
                    toolName = "run_command",
                    approvalDisposition = "APPROVED_ONCE",
                    sanitizedArgs = mapOf("command" to "git status --short"),
                ),
                ledgerRecord(
                    id = "4",
                    timestamp = 4_000L,
                    toolName = "secret_create",
                    approvalDisposition = "DENIED_BY_OPERATOR",
                ),
                ledgerRecord(
                    id = "5",
                    timestamp = 5_000L,
                    toolName = "run_command",
                    approvalDisposition = "TIMEOUT",
                    isError = true,
                    errorSnippet = "no approval within 30000ms",
                ),
            ).joinToString("\n")
        return AgentLoom.session(AgentLoom.parse(text, "mcp-calls.jsonl"), listOf("mcp-calls.jsonl"))
    }

    /** The JSON inside the viewer's data block, decoded back into the structure that produced it. */
    private fun decodeSession(html: String): LoomSession {
        val marker = "id=\"" + AgentLoomViewerTemplate.DATA_ELEMENT_ID + "\""
        val start = html.indexOf(marker)
        assertTrue(start >= 0, "the artifact has a data block")
        val open = html.indexOf('>', start)
        val close = html.indexOf("</script>", open)
        assertTrue(close > open, "the data block is closed")
        return Json.decodeFromString(LoomSession.serializer(), html.substring(open + 1, close))
    }
}
