package ai.rever.boss.mcp.loom

import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.serialization.json.Json
import java.io.File

/** What an export produced, so a caller can report it without re-reading the file. */
data class LoomExport(
    val outputFile: File,
    val session: LoomSession,
    val bytes: Long,
)

/**
 * Turns a persisted MCP ledger into one self-contained HTML artifact.
 *
 * **Read-only, and offline by construction.** Reading goes through the ledger's own file ordering,
 * which is the only thing that knows how rotation names its backups, and nothing here writes to a
 * ledger file. The artifact carries the reconstructed session as embedded JSON, so the viewer needs
 * no BOSS, no login, no server, and no network: it is a file you can email.
 *
 * The one thing the artifact does *not* do is parse a raw `mcp-calls.jsonl` in the browser. Ordering,
 * outcome classification and risk derivation live in [AgentLoom] on the JVM; re-implementing them in
 * the viewer's JavaScript would create two definitions of the same reconstruction that can disagree,
 * and a replay that disagrees with the ledger is worse than no replay. Import is therefore the
 * export command, not the viewer.
 */
object AgentLoomExporter {
    private val json = Json { encodeDefaults = true }

    /** The runtime ledger, resolved through the same directory the application uses. */
    fun defaultLedgerFile(): File = BossDirectories.resolve(AgentLoom.LEDGER_FILE_NAME)

    /**
     * The ledger files for [ledgerFile], oldest first: the highest-numbered rotated backup through to
     * the active file. Rotation naming is the ledger's, not this package's.
     */
    fun ledgerFiles(ledgerFile: File): List<File> = McpOperationLedger(ledgerFile).ledgerFilesOldestFirst()

    /** Rotated backups that are missing while an older one is present, so history has a hole in it. */
    fun coverageGaps(ledgerFile: File): List<String> = McpOperationLedger(ledgerFile).coverageGaps()

    /** Render [session] as one standalone HTML document. */
    fun html(session: LoomSession): String =
        AgentLoomViewerTemplate.HTML.replace(
            AgentLoomViewerTemplate.DATA_PLACEHOLDER,
            embed(session),
        )

    /** Write [session] to [outputFile] as one standalone HTML document. */
    fun export(
        session: LoomSession,
        outputFile: File,
    ): LoomExport {
        val html = html(session)
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(html)
        return LoomExport(outputFile, session, outputFile.length())
    }

    /**
     * Read [ledgerFile] and its rotations, reconstruct the session, and write the artifact.
     *
     * An unreadable ledger file is not an error here: it produces an empty-state artifact that names
     * the source and says the ledger holds nothing, which is what an operator needs to see.
     */
    fun exportLedger(
        ledgerFile: File,
        outputFile: File,
        generatedAt: Long = System.currentTimeMillis(),
    ): LoomExport {
        val files = ledgerFiles(ledgerFile)
        val session =
            AgentLoom.session(
                parse = AgentLoom.read(files),
                sources = if (files.isEmpty()) listOf(ledgerFile.name) else files.map { it.name },
                coverageGaps = coverageGaps(ledgerFile),
                generatedAt = generatedAt,
            )
        return export(session, outputFile)
    }

    /**
     * The session as JSON for the viewer's `loom-data` block.
     *
     * `<` is escaped because the block is a `<script>` element and the ledger's arguments are
     * arbitrary text: an argument containing `</script>` must not be able to end the block. The two
     * JavaScript line separators are escaped for the same reason, since a JSON parser is happy with
     * them but a `<script>` body is not.
     */
    private fun embed(session: LoomSession): String =
        json
            .encodeToString(LoomSession.serializer(), session)
            .replace("<", "\\u003c")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
}
