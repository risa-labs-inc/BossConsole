package ai.rever.boss.components.workspaces

import ai.rever.boss.mcp.secrets.captureHostLogs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Saved Spaces carry the most privacy-sensitive local-state mix: project paths, browser URLs and
 * terminal commands. kotlinx serialization includes the source document in decoder messages, so
 * every workspace decode boundary must publish structured diagnostics without attaching the raw
 * exception. These tests exercise the three distinct boundaries rather than only [decodeFailure].
 */
class WorkspaceDecodePrivacyTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a corrupt Space file is logged without its layout data`() =
        runBlocking {
            val secret = "private-space-url-${System.nanoTime()}"
            val fileName = "corrupt-space.json"
            File(dir, fileName).writeText("{\"name\":\"Space\",\"url\":\"https://$secret.example/")
            val fileManager = WorkspaceFileManager(dir.absolutePath)

            val (loaded, logged) = captureHostLogs { runBlocking { fileManager.loadWorkspace(fileName) } }

            assertNull(loaded)
            assertDecodeFailureRedacted(logged, "Failed to load workspace file", secret)
        }

    @Test
    fun `a corrupt Last Session set is logged without its saved tabs`() =
        runBlocking {
            val secret = "private-session-tab-${System.nanoTime()}"
            val fileManager = WorkspaceFileManager(dir.absolutePath)
            fileManager.writeDocumentBlocking(
                LAST_SESSION_SET_FILE,
                "{\"spaces\":[{\"workspaceId\":\"one\",\"tabUrl\":\"https://$secret.example/",
            )
            val manager = WorkspaceManager(fileManager)

            val (loaded, logged) = captureHostLogs { runBlocking { manager.loadLastSessionSet() } }

            assertNull(loaded)
            assertDecodeFailureRedacted(logged, "Last Session set could not be read", secret)
        }

    @Test
    fun `a rejected Space import is logged without caller JSON`() {
        val secret = "private-import-command-${System.nanoTime()}"
        val manager = WorkspaceManager(WorkspaceFileManager(dir.absolutePath))
        val document = "{\"name\":\"Imported\",\"command\":\"echo $secret"

        val (loaded, logged) = captureHostLogs { manager.importWorkspace(document) }

        assertNull(loaded)
        assertDecodeFailureRedacted(logged, "Failed to import workspace from JSON", secret)
    }

    private fun assertDecodeFailureRedacted(
        logged: List<ai.rever.boss.utils.logging.LogEntry>,
        message: String,
        secret: String,
    ) {
        val failure = logged.single { it.message == message }
        assertNull(failure.error, "the decoder exception includes workspace JSON and must not be attached")
        assertEquals("JsonDecodingException", failure.data?.get("decodeFailure"))
        for (entry in logged) {
            assertFalse(secret in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
        }
    }
}
