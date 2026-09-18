package ai.rever.boss.mcp

import ai.rever.boss.components.workspaces.LAST_SESSION_SET_FILE
import ai.rever.boss.components.workspaces.SPACE_THEMES_FILE
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #926: the workspace MCP file tools take a caller-chosen id and derive the file it writes from
 * it, so an id spelled as a reserved store record's name - `Space_Themes` for the Space-theme
 * store, `Last_Session_Set` for the session-set record - must not overwrite the host's own
 * persisted files. `WorkspaceManager` skips those two files when it scans the workspaces
 * directory because they are not Spaces; these tests pin the mirror image of that skip on the
 * write side: every save route the provider has refuses an id that resolves onto them, and
 * nothing else changes - ordinary workspaces still save, and the near-miss spellings
 * (`.json`-suffixed, path-shaped) land on their own ordinary files or are refused before the
 * write, never on a record.
 */
class WorkspaceReservedStoreFilesTest {
    private val themeSentinel = """{"themes":{"space-1":"ocean"}}"""
    private val sessionSentinel = """{"windows":{"w1":["a","b"]}}"""
    private lateinit var workspaceDir: File
    private lateinit var fileManager: WorkspaceFileManager

    @BeforeTest
    fun setUp() {
        workspaceDir = Files.createTempDirectory("workspace-reserved-store").toFile()
        fileManager = WorkspaceFileManager(directoryOverride = workspaceDir.absolutePath)
        WorkspaceMcpToolProvider.fileManagerProvider = { fileManager }
        // A cold start mints a window; the refusals below fire before anything is persisted,
        // but the createIfAbsent route needs the hook to get past target-window resolution.
        WorkspaceMcpToolProvider.windowCreator = { "test-window-reserved-1" }
        WorkspaceMcpToolProvider.splitViewStateResolver = { null }
        WorkspaceMcpToolProvider.terminalTabOpener = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 50L
    }

    @AfterTest
    fun tearDown() {
        WorkspaceMcpToolProvider.fileManagerProvider = null
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.terminalTabOpener = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 5000L
        workspaceDir.deleteRecursively()
    }

    private fun core(): McpToolRegistryCore {
        val policyEngine = McpPolicyEngine(policyFile = null)
        policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
        val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine)
        core.registerProvider(WorkspaceMcpToolProvider)
        return core
    }

    private suspend fun openWorkspace(argsJson: String): McpToolResult = core().invoke("open_workspace", argsJson)

    private fun seedReservedFiles() {
        assertTrue(fileManager.writeDocumentBlocking(SPACE_THEMES_FILE, themeSentinel))
        assertTrue(fileManager.writeDocumentBlocking(LAST_SESSION_SET_FILE, sessionSentinel))
    }

    @Test
    fun `caller-chosen ids that resolve onto the reserved store files are refused`() =
        runBlocking {
            seedReservedFiles()
            for (id in listOf("Space_Themes", "Last_Session_Set")) {
                val result = openWorkspace("""{"workspaceId":"$id","createIfAbsent":true}""")
                assertTrue(result.isError, "the id '$id' must be refused, got: ${result.text}")
                assertTrue(result.text.contains("reserved"), result.text)
                assertTrue(result.text.contains("different workspaceId"), result.text)
            }
            // The host's own records are byte-for-byte what they were, and no Space file was
            // created for the refused ids - the refusal happens before anything is applied.
            assertEquals(themeSentinel, fileManager.loadDocument(SPACE_THEMES_FILE))
            assertEquals(sessionSentinel, fileManager.loadDocument(LAST_SESSION_SET_FILE))
            assertEquals(
                setOf(SPACE_THEMES_FILE, LAST_SESSION_SET_FILE),
                workspaceDir.list()!!.toSet(),
                "a refused id must leave the directory exactly as it was",
            )
        }

    @Test
    fun `an id that resolves nowhere near a reserved file still creates its workspace`() =
        runBlocking {
            seedReservedFiles()
            val result = openWorkspace("""{"workspaceId":"ordinary-space","createIfAbsent":true}""")
            assertFalse(result.isError, result.text)
            assertTrue(File(workspaceDir, "ordinary-space.json").exists())
            assertEquals(themeSentinel, fileManager.loadDocument(SPACE_THEMES_FILE))
            assertEquals(sessionSentinel, fileManager.loadDocument(LAST_SESSION_SET_FILE))
        }

    @Test
    fun `a json-suffixed id derives to its own file and never to a reserved one`() =
        runBlocking {
            seedReservedFiles()
            val result = openWorkspace("""{"workspaceId":"Space_Themes.json","createIfAbsent":true}""")
            // Not refused - the id derives to Space_Themes.json.json, its own ordinary file - but
            // the theme store it shares a directory with is untouched.
            assertFalse(result.isError, result.text)
            assertTrue(File(workspaceDir, "Space_Themes.json.json").exists())
            assertEquals(themeSentinel, fileManager.loadDocument(SPACE_THEMES_FILE))
            assertEquals(sessionSentinel, fileManager.loadDocument(LAST_SESSION_SET_FILE))
        }

    @Test
    fun `path-shaped spellings of a reserved name are refused before anything is written`() =
        runBlocking {
            seedReservedFiles()
            val ids =
                listOf(
                    "./Space_Themes",
                    "../Space_Themes",
                    "Space/../Space_Themes",
                    "..\\Space_Themes",
                    "/tmp/Space_Themes",
                    "Space_Themes/..\\Last_Session_Set",
                )
            for (id in ids) {
                val json = """{"workspaceId":"${id.replace("\\", "\\\\")}","createIfAbsent":true}"""
                val result = openWorkspace(json)
                assertTrue(result.isError, "the id '$id' must be refused, got: ${result.text}")
            }
            assertEquals(themeSentinel, fileManager.loadDocument(SPACE_THEMES_FILE))
            assertEquals(sessionSentinel, fileManager.loadDocument(LAST_SESSION_SET_FILE))
        }

    @Test
    fun `the reserved-name gate refuses exactly the files the directory scan skips`() {
        // Refused: the two reserved records, via the exact id that derives to each.
        assertNotNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Space_Themes"))
        assertNotNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Last_Session_Set"))
        // Allowed: ordinary ids - this is the guard create_workspace's minted ids pass too.
        assertNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("workspace-1788000000001"))
        assertNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("workspace-disposable-123"))
        // Allowed: a .json-suffixed id derives to a .json.json of its own, never the record.
        assertNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Space_Themes.json"))
        // Allowed: Last_Session is the live last-session AUTOSAVE Space, not the session-set
        // record - the manager's scan loads it, so the write side must not refuse it either.
        assertNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Last_Session"))
        // The gate is the record files' exact names, not a substring match.
        assertFalse(WorkspaceFileManagerCommon.isReservedDocumentFileName("my_Space_Themes.json"))
        assertFalse(WorkspaceFileManagerCommon.isReservedDocumentFileName("space_themes.json"))
        assertEquals(
            setOf(LAST_SESSION_SET_FILE, SPACE_THEMES_FILE),
            WorkspaceFileManagerCommon.reservedDocumentFileNames,
            "the guard list must mirror the files WorkspaceManager's scan skips",
        )
    }
}
