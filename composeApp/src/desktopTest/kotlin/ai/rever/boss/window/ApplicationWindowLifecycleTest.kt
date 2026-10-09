package ai.rever.boss.window

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ApplicationWindowLifecycleTest {
    @BeforeTest
    @AfterTest
    fun clearWindows() {
        WindowManager.windows.toList().forEach { WindowManager.closeWindow(it.id) }
    }

    @Test
    fun windowlessUpdateLaunchWaitsForDock() {
        val lifecycle = ApplicationWindowLifecycle()
        lifecycle.openInitialWindow(startWithoutWindow = true)
        assertEquals(0, WindowManager.windowCount)

        lifecycle.reopen()
        val window = WindowManager.windows.single()
        repeat(3) { lifecycle.reopen() }
        assertEquals(listOf(window), WindowManager.windows)
    }

    @Test
    fun closingAllWindowsThenReopeningCreatesFreshWindow() {
        val lifecycle = ApplicationWindowLifecycle()
        lifecycle.openInitialWindow()
        val initial = WindowManager.windows.single()
        lifecycle.openInitialWindow()
        assertEquals(listOf(initial), WindowManager.windows)

        WindowManager.closeWindow(initial.id)
        lifecycle.reopen()
        assertNotEquals(initial.id, WindowManager.windows.single().id)
    }
}
