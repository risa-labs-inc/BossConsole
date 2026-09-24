package ai.rever.boss.mcp.context

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.topofmind.ActiveTab
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceContextMcpProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class StubTabInfo(
        override val id: String,
        override val typeId: TabTypeId,
        override val title: String,
        val filePath: String? = null,
        val currentUrl: String? = null,
    ) : TabInfo {
        override val icon: ImageVector get() = error("Not needed for tests")
        override val tabIcon: TabIcon? get() = null
    }

    private fun createActiveTab(
        id: String,
        typeIdString: String,
        title: String,
        filePath: String? = null,
        currentUrl: String? = null,
        workspaceId: String = "ws-1",
        workspaceName: String = "Main",
        panelId: String = "panel-1",
        windowId: String = "window-1",
    ): ActiveTab {
        val tabInfo =
            StubTabInfo(
                id = id,
                typeId = TabTypeId(typeIdString, "test.plugin"),
                title = title,
                filePath = filePath,
                currentUrl = currentUrl,
            )
        return ActiveTab(
            tabInfo = tabInfo,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            panelId = panelId,
            windowId = windowId,
        )
    }

    @Test
    fun `empty tabs and null project path returns valid empty snapshot`() =
        runBlocking {
            val provider =
                WorkspaceContextMcpProvider(
                    snapshotSupplier = {
                        WorkspaceSnapshotCollector.collect(
                            activeTabsSupplier = { emptyList() },
                            projectPathSupplier = { null },
                        )
                    },
                )

            val tool = provider.tools().first { it.name == "get_workspace_context" }
            assertFalse(tool.requiresAdmin)
            assertTrue(tool.requiredPermissions.isEmpty())
            assertTrue(tool.readOnly)

            val result = tool.handler.call(McpToolArgs(emptyMap(), "{}"))
            assertFalse(result.isError)

            val snapshot = json.decodeFromString(WorkspaceSnapshot.serializer(), result.text)
            assertNull(snapshot.activeProjectPath)
            assertTrue(snapshot.openTabs.isEmpty())
            assertEquals(0, snapshot.tabCounts.total)
            assertEquals(0, snapshot.tabCounts.editor)
            assertEquals(0, snapshot.tabCounts.terminal)
            assertEquals(0, snapshot.tabCounts.browser)
            assertEquals(0, snapshot.tabCounts.other)
            assertNull(snapshot.activeEditorFile)
        }

    @Test
    fun `categorizes tabs into editor, terminal, browser and other`() =
        runBlocking {
            val tabs =
                listOf(
                    createActiveTab("t1", "editor", "Main.kt", filePath = "/path/to/project/src/Main.kt"),
                    createActiveTab("t2", "terminal", "Terminal 1"),
                    createActiveTab("t3", "fluck", "Google", currentUrl = "https://google.com"),
                    createActiveTab("t4", "custom_plugin_tab", "Custom View"),
                )

            val provider =
                WorkspaceContextMcpProvider(
                    snapshotSupplier = {
                        WorkspaceSnapshotCollector.collect(
                            activeTabsSupplier = { tabs },
                            projectPathSupplier = { "/path/to/project" },
                        )
                    },
                )

            val tool = provider.tools().first { it.name == "get_workspace_context" }
            val result = tool.handler.call(McpToolArgs(emptyMap(), "{}"))
            val snapshot = json.decodeFromString(WorkspaceSnapshot.serializer(), result.text)

            assertEquals(4, snapshot.tabCounts.total)
            assertEquals(1, snapshot.tabCounts.editor)
            assertEquals(1, snapshot.tabCounts.terminal)
            assertEquals(1, snapshot.tabCounts.browser)
            assertEquals(1, snapshot.tabCounts.other)

            val editorTab = snapshot.openTabs.first { it.type == "editor" }
            assertEquals("Main.kt", editorTab.title)
            assertEquals("/path/to/project/src/Main.kt", editorTab.filePath)
            assertEquals("src/Main.kt", editorTab.relativePath)

            val browserTab = snapshot.openTabs.first { it.type == "browser" }
            assertEquals("https://google.com", browserTab.browserUrl)

            val activeEditorFile = assertNotNull(snapshot.activeEditorFile)
            assertEquals("/path/to/project/src/Main.kt", activeEditorFile.absolutePath)
            assertEquals("src/Main.kt", activeEditorFile.relativePath)
            assertEquals("Main.kt", activeEditorFile.fileName)
        }

    @Test
    fun `computes safe relative paths without cross-drive crash on Windows`() {
        val winProj = "C:\\Users\\dev\\project"
        val winFileSameDrive = "C:\\Users\\dev\\project\\src\\App.kt"
        val winFileOtherDrive = "D:\\data\\Other.kt"

        val relSame = WorkspaceSnapshotCollector.computeSafeRelativePath(winFileSameDrive, winProj)
        assertEquals("src/App.kt", relSame)

        val relOther = WorkspaceSnapshotCollector.computeSafeRelativePath(winFileOtherDrive, winProj)
        assertNull(relOther)

        val unixProj = "/home/user/project"
        val unixFile = "/home/user/project/README.md"
        val relUnix = WorkspaceSnapshotCollector.computeSafeRelativePath(unixFile, unixProj)
        assertEquals("README.md", relUnix)
    }

    @Test
    fun `get_active_editor_file tool returns focused file snapshot or null`() =
        runBlocking {
            val providerWithFile =
                WorkspaceContextMcpProvider(
                    activeEditorSupplier = {
                        ActiveEditorFileSnapshot(
                            absolutePath = "C:/repo/File.kt",
                            relativePath = "File.kt",
                            fileName = "File.kt",
                        )
                    },
                )

            val fileTool = providerWithFile.tools().first { it.name == "get_active_editor_file" }
            assertFalse(fileTool.requiresAdmin)
            assertTrue(fileTool.requiredPermissions.isEmpty())

            val resultWithFile = fileTool.handler.call(McpToolArgs(emptyMap(), "{}"))
            assertFalse(resultWithFile.isError)
            val decoded = json.decodeFromString(ActiveEditorFileSnapshot.serializer(), resultWithFile.text)
            assertEquals("File.kt", decoded.fileName)

            val providerWithoutFile =
                WorkspaceContextMcpProvider(
                    activeEditorSupplier = { null },
                )
            val resultWithoutFile =
                providerWithoutFile
                    .tools()
                    .first { it.name == "get_active_editor_file" }
                    .handler
                    .call(McpToolArgs(emptyMap(), "{}"))
            assertFalse(resultWithoutFile.isError)
            assertTrue(resultWithoutFile.text.contains("null"))
        }
}
