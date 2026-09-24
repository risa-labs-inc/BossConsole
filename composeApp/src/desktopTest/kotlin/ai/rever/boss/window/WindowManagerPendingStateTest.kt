package ai.rever.boss.window

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WindowManagerPendingStateTest {
    private val createdWindowIds = mutableListOf<String>()

    private data class TestTabInfo(
        override val id: String,
        override val typeId: TabTypeId = TabTypeId("test"),
        override val title: String,
    ) : TabInfo {
        override val icon: ImageVector get() = Icons.Outlined.Language
    }

    private fun project(name: String) = Project(name = name, path = "/tmp/$name", lastOpened = 0L)

    @AfterTest
    fun cleanup() {
        createdWindowIds.forEach(WindowManager::closeWindow)
        createdWindowIds.clear()
    }

    @Test
    fun closeWindow_removesWindow_andCleansUpUnconsumedPendingTab() {
        val window = WindowManager.createNewWindowWithTab(TestTabInfo("tab-unconsumed", title = "Test Tab"))
        createdWindowIds.add(window.id)

        assertNotNull(WindowManager.getWindow(window.id))
        WindowManager.closeWindow(window.id)

        assertNull(WindowManager.getWindow(window.id))
        assertNull(WindowManager.consumePendingTab(window.id))
    }

    @Test
    fun closeWindow_removesWindow_andCleansUpUnconsumedPendingProject() {
        val window = WindowManager.createNewWindowWithProject(project("project-unconsumed"))
        createdWindowIds.add(window.id)

        assertNotNull(WindowManager.getWindow(window.id))
        WindowManager.closeWindow(window.id)

        assertNull(WindowManager.getWindow(window.id))
        assertNull(WindowManager.consumePendingProject(window.id))
    }

    @Test
    fun closeWindow_doesNotRetireAnotherWindowsPendingState() {
        val tabWindow = WindowManager.createNewWindowWithTab(TestTabInfo("tab-kept", title = "Kept Tab"))
        val projectWindow = WindowManager.createNewWindowWithProject(project("project-kept"))
        createdWindowIds.add(tabWindow.id)
        createdWindowIds.add(projectWindow.id)

        WindowManager.closeWindow(tabWindow.id)

        assertNull(WindowManager.consumePendingTab(tabWindow.id))
        assertNotNull(WindowManager.consumePendingProject(projectWindow.id))
    }

    @Test
    fun consumePendingTab_beforeClose_isSafeAndLeavesNoStrandedState() {
        val tab = TestTabInfo("tab-consumed", title = "Consumed Tab")
        val window = WindowManager.createNewWindowWithTab(tab)
        createdWindowIds.add(window.id)

        assertEquals(tab, WindowManager.consumePendingTab(window.id))
        WindowManager.closeWindow(window.id)

        assertNull(WindowManager.consumePendingTab(window.id))
    }

    @Test
    fun consumePendingProject_beforeClose_isSafeAndLeavesNoStrandedState() {
        val project = project("project-consumed")
        val window = WindowManager.createNewWindowWithProject(project)
        createdWindowIds.add(window.id)

        assertEquals(project, WindowManager.consumePendingProject(window.id))
        WindowManager.closeWindow(window.id)

        assertNull(WindowManager.consumePendingProject(window.id))
    }

    @Test
    fun closeWindow_withUnknownId_isSafeNoOp() {
        val initialCount = WindowManager.windowCount

        WindowManager.closeWindow("unknown-window-id-9999")

        assertEquals(initialCount, WindowManager.windowCount)
    }
}
