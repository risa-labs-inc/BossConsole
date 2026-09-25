package ai.rever.boss.mcp

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LAST_SESSION_SET_FILE
import ai.rever.boss.components.workspaces.SPACE_THEMES_FILE
import ai.rever.boss.components.workspaces.WorkspaceFileManager
import ai.rever.boss.components.workspaces.WorkspaceFileManagerCommon
import ai.rever.boss.components.workspaces.reservedWorkspaceStoreFileName
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #926: the MCP create path took a caller-chosen workspace id and saved `<id>.json` with no
 * knowledge of the reserved names `WorkspaceManager`'s load scan deliberately skips by name.
 *
 * `Last_Session_Set.json` and `Space_Themes.json` are records that live beside the Spaces without
 * being one - so they never deserialize as a Space, and an open_workspace with `createIfAbsent`
 * and a matching id fell through every lookup into the CREATE branch and saved straight over the
 * record. Silently, twice over: the file was never in the Space list, and the record it destroyed
 * was the theme store or the session restore.
 *
 * Driven through the provider's real file manager on a temp directory so the assertion is about
 * the seam the defect lives in - that a refused save leaves the record's bytes untouched on disk
 * - not just the error string.
 */
class WorkspaceReservedStoreNameTest {
    private val tempDirs = mutableListOf<File>()
    private val createdSplitViewStates = mutableListOf<SplitViewState>()
    private lateinit var workspaceDir: File
    private lateinit var fileManager: WorkspaceFileManager

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("workspace-reserved-name-test").toFile()
        tempDirs.add(dir)
        workspaceDir = dir
        fileManager = WorkspaceFileManager(directoryOverride = dir.absolutePath)
        WorkspaceMcpToolProvider.fileManagerProvider = { fileManager }
        // The reserved-name gate sits in the CREATE branch, which is reached after window
        // resolution - same harness as WorkspaceMcpToolProviderTest.
        // A real window registers its SplitViewState as it composes; open_workspace now
        // awaits that, so the harness window must register too - same shape as
        // WorkspaceMcpToolProviderTest.
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
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 50L
    }

    @AfterTest
    fun tearDown() {
        WorkspaceMcpToolProvider.fileManagerProvider = null
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 5000L
        SplitViewStateRegistry.getAllStates().keys.forEach {
            SplitViewStateRegistry.unregister(it)
        }
        createdSplitViewStates.forEach { it.dispose() }
        createdSplitViewStates.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun createCore(): McpToolRegistryCore {
        val policyEngine = McpPolicyEngine(policyFile = null)
        policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
        val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine)
        core.registerProvider(WorkspaceMcpToolProvider)
        return core
    }

    /**
     * A live record whose bytes a refused save must leave untouched: what the theme store holds
     * before the MCP tool is asked to save a Space named after it.
     */
    private fun seedRecord(name: String): String {
        val content = """{"written-by":"the-host","not-a":"workspace"}"""
        assertTrue(fileManager.writeDocumentBlocking(name, content), "seeding $name must succeed")
        return content
    }

    @Test
    fun `saving Space_Themes over the theme record is refused`() =
        runBlocking {
            val themes = seedRecord(SPACE_THEMES_FILE)

            val result =
                createCore().invoke(
                    "open_workspace",
                    """{"workspaceId":"Space_Themes","name":"Hijack","createIfAbsent":true}""",
                )

            assertTrue(result.isError, "expected a refusal, got: ${result.text}")
            assertTrue(result.text.contains("reserved"), result.text)
            // The record's bytes are untouched: every Space theme assignment survives.
            assertEquals(themes, fileManager.loadDocument(SPACE_THEMES_FILE))
        }

    @Test
    fun `saving the last-session-set record is refused`() =
        runBlocking {
            val record = seedRecord(LAST_SESSION_SET_FILE)

            val result =
                createCore().invoke(
                    "open_workspace",
                    """{"workspaceId":"Last_Session_Set","name":"Hijack","createIfAbsent":true}""",
                )

            assertTrue(result.isError, "expected a refusal, got: ${result.text}")
            assertTrue(result.text.contains("reserved"), result.text)
            // The record's bytes are untouched: the next launch still restores the session.
            assertEquals(record, fileManager.loadDocument(LAST_SESSION_SET_FILE))
        }

    @Test
    fun `the id's own json suffix is stripped before the reserved-name check`() =
        runBlocking {
            val record = seedRecord(LAST_SESSION_SET_FILE)

            val result =
                createCore().invoke(
                    "open_workspace",
                    """{"workspaceId":"Last_Session_Set.json","name":"Hijack","createIfAbsent":true}""",
                )

            assertTrue(result.isError, "expected a refusal, got: ${result.text}")
            assertEquals(record, fileManager.loadDocument(LAST_SESSION_SET_FILE))
        }

    @Test
    fun `the reserved names are refused case-insensitively`() =
        runBlocking {
            val record = seedRecord(SPACE_THEMES_FILE)

            val result =
                createCore().invoke(
                    "open_workspace",
                    """{"workspaceId":"space_themes","name":"Hijack","createIfAbsent":true}""",
                )

            assertTrue(result.isError, "expected a refusal, got: ${result.text}")
            assertEquals(record, fileManager.loadDocument(SPACE_THEMES_FILE))
        }

    @Test
    fun `the last-session id and the legacy Last_Session record are refused`() =
        runBlocking {
            // `last-session` is the slot id isSpaceSlot already refuses; the legacy `Last_Session.json`
            // is on real disks from pre-id installs, and `last_session` differs from it only in case
            // and separator, which is the same file on NTFS and default APFS.
            for (id in listOf(LAST_SESSION_ID, "Last_Session", "last_session")) {
                val result =
                    createCore().invoke(
                        "open_workspace",
                        """{"workspaceId":"$id","name":"Hijack","createIfAbsent":true}""",
                    )
                assertTrue(result.isError, "expected a refusal for $id, got: ${result.text}")
            }
        }

    @Test
    fun `a normal id still saves and is neither a record nor refused`() =
        runBlocking {
            val result =
                createCore().invoke(
                    "open_workspace",
                    """{"workspaceId":"ordinary-space","name":"Ordinary","createIfAbsent":true}""",
                )

            assertFalse(result.isError, "expected success, got: ${result.text}")
            assertTrue(
                fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("ordinary-space")) != null,
                "the ordinary Space must be on disk",
            )
        }

    @Test
    fun `an id that merely resembles a reserved name passes`() =
        runBlocking {
            val result =
                createCore().invoke(
                    "open_workspace",
                    """{"workspaceId":"Space_Themes_2","name":"Lookalike","createIfAbsent":true}""",
                )

            assertFalse(result.isError, "resembling a reserved name must not refuse: ${result.text}")
            assertTrue(
                fileManager.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId("Space_Themes_2")) != null,
                "the lookalike Space must be on disk",
            )
            assertNull(File(workspaceDir, SPACE_THEMES_FILE).takeIf { it.exists() })
        }

    @Test
    fun `the gate derives the file with the same sanitizer the save uses`() {
        // Both sides of the comparison run through WorkspaceFileManagerCommon.fileNameForId, so
        // every id that writes a reserved file is caught - with the caller's own `.json` suffix,
        // with a space, and differing only in case.
        assertEquals(SPACE_THEMES_FILE, reservedWorkspaceStoreFileName("Space_Themes"))
        assertEquals(SPACE_THEMES_FILE, reservedWorkspaceStoreFileName("Space_Themes.json"))
        assertEquals(SPACE_THEMES_FILE, reservedWorkspaceStoreFileName("space themes"))
        assertEquals(LAST_SESSION_SET_FILE, reservedWorkspaceStoreFileName("Last_Session_Set"))
        assertEquals(LAST_SESSION_SET_FILE, reservedWorkspaceStoreFileName("last_session_set"))
        assertEquals("Last_Session.json", reservedWorkspaceStoreFileName("Last Session"))
        assertEquals("Last_Session.json", reservedWorkspaceStoreFileName("Last_Session"))
        assertNull(reservedWorkspaceStoreFileName("Space_Themes_2"))
        assertNull(reservedWorkspaceStoreFileName("workspace-1788000000001"))
        assertNull(reservedWorkspaceStoreFileName(""))
        assertNull(reservedWorkspaceStoreFileName(".json"))
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
