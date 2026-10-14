package ai.rever.boss.mcp

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LAST_SESSION_SET_FILE
import ai.rever.boss.components.workspaces.SPACE_THEMES_FILE
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
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
 * store, `Last_Session_Set` for the session-set record, `Last_Session` for the legacy
 * single-Space session record - must not overwrite the host's own persisted files. The write
 * gate is shared with the import path (#964, #1643): the caller's own `.json` suffix is
 * stripped, the name is derived with the same sanitiser the save uses, and the comparison is
 * case-insensitive because APFS and NTFS fold case. `WorkspaceManager` skips the two document
 * records when it scans the workspaces directory because they are not Spaces; these tests pin
 * the mirror image of that skip on the write side: every save route the provider has refuses
 * an id that resolves onto them, and nothing else changes - ordinary workspaces still save,
 * and path-shaped spellings are refused before the write, never on a record.
 */
class WorkspaceReservedStoreFilesTest {
    private val themeSentinel = """{"themes":{"space-1":"ocean"}}"""
    private val sessionSentinel = """{"windows":{"w1":["a","b"]}}"""
    private val createdSplitViewStates = mutableListOf<SplitViewState>()
    private lateinit var workspaceDir: File
    private lateinit var fileManager: WorkspaceFileManager

    @BeforeTest
    fun setUp() {
        workspaceDir = Files.createTempDirectory("workspace-reserved-store").toFile()
        fileManager = WorkspaceFileManager(directoryOverride = workspaceDir.absolutePath)
        WorkspaceMcpToolProvider.fileManagerProvider = { fileManager }
        // A cold start mints a window; the refusals below fire before anything is persisted,
        // but the createIfAbsent route needs the hook to get past target-window resolution.
        // A real window registers its SplitViewState as it composes; open_workspace awaits
        // that before applying, so the harness window must register too - same shape as
        // WorkspaceMcpToolProviderTest and WorkspaceReservedStoreNameTest.
        WorkspaceMcpToolProvider.windowCreator = {
            "test-window-reserved-1".also { id ->
                if (!SplitViewStateRegistry.isRegistered(id)) {
                    val state = SplitViewState(stubTabRegistry, id)
                    createdSplitViewStates.add(state)
                    SplitViewStateRegistry.register(id, state)
                }
            }
        }
        WorkspaceMcpToolProvider.splitViewStateResolver = { null }
        WorkspaceMcpToolProvider.terminalTabOpener = null
        // 50 ms sat on the CI flake floor: the positive control below waits for the window to
        // register its UI state, and a loaded runner missed it. 500 ms still bounds the wait
        // well under the 5000 ms default the refusals never reach.
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 500L
    }

    @AfterTest
    fun tearDown() {
        WorkspaceMcpToolProvider.fileManagerProvider = null
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.terminalTabOpener = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 5000L
        SplitViewStateRegistry.getAllStates().keys.forEach {
            SplitViewStateRegistry.unregister(it)
        }
        createdSplitViewStates.forEach { it.dispose() }
        createdSplitViewStates.clear()
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
    fun `a json-suffixed reserved name is refused like the bare id`() =
        runBlocking {
            seedReservedFiles()
            // The load path treats a suffixed id as that file name, so the write gate answers
            // the same question rather than the accidental `<id>.json.json` the raw id would
            // produce (#964): the caller's own suffix is stripped before the check.
            val result = openWorkspace("""{"workspaceId":"Space_Themes.json","createIfAbsent":true}""")
            assertTrue(result.isError, "expected a refusal, got: ${result.text}")
            assertTrue(result.text.contains("reserved"), result.text)
            assertEquals(themeSentinel, fileManager.loadDocument(SPACE_THEMES_FILE))
            assertEquals(sessionSentinel, fileManager.loadDocument(LAST_SESSION_SET_FILE))
            assertEquals(
                setOf(SPACE_THEMES_FILE, LAST_SESSION_SET_FILE),
                workspaceDir.list()!!.toSet(),
                "a refused id must leave the directory exactly as it was",
            )
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
    fun `the write gate refuses the scan's skips plus the session-record spellings`() {
        // Refused: the two document records, via the exact id that derives to each.
        assertNotNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Space_Themes"))
        assertNotNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Last_Session_Set"))
        // Refused: the caller's own .json suffix is stripped first (#964) - the load path
        // treats a suffixed id as that file name, so the gate answers the same question.
        assertNotNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Space_Themes.json"))
        // Refused: the single-Space session record's spellings, which the scan still loads
        // as the real record but a caller-chosen id must not overwrite (#964, #1643).
        assertNotNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("Last_Session"))
        assertNotNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("last_session"))
        // Allowed: ordinary ids - this is the guard create_workspace's minted ids pass too.
        assertNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("workspace-1788000000001"))
        assertNull(WorkspaceMcpToolProvider.refusalForReservedStoreFile("workspace-disposable-123"))
        // The gate is the record files' names, not a substring match.
        assertFalse(WorkspaceFileManagerCommon.isReservedDocumentFileName("my_Space_Themes.json"))
        // ...but the SAME record in lower case is refused: APFS/NTFS fold case, so
        // space_themes.json IS Space_Themes.json there (#926).
        assertTrue(WorkspaceFileManagerCommon.isReservedDocumentFileName("space_themes.json"))
        assertTrue(WorkspaceFileManagerCommon.isReservedDocumentFileName("last_session_set.json"))
        assertEquals(
            setOf(LAST_SESSION_SET_FILE, SPACE_THEMES_FILE),
            WorkspaceFileManagerCommon.reservedDocumentFileNames,
            "the directory scan skips exactly the two document records",
        )
        assertEquals(
            WorkspaceFileManagerCommon.reservedDocumentFileNames +
                setOf("Last_Session.json", "last-session.json"),
            WorkspaceFileManagerCommon.reservedRecordFileNames,
            "the write gate extends the scan's skips with the session-record names (#964)",
        )
    }

    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private val stubTabRegistry =
        TabRegistry().apply {
            registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
        }
}
