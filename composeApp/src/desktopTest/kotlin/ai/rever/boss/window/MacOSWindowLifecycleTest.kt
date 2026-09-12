package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals

class MacOSWindowLifecycleTest {
    @Test
    fun `final macOS main window is retained`() {
        assertEquals(
            WindowCloseDisposition.HIDE_AND_RETAIN,
            decideWindowCloseDisposition(
                isMacOS = true,
                closingWindowType = WindowType.MAIN,
                hasOtherMainWindows = false,
            ),
        )
    }

    @Test
    fun `non-final macOS main window is disposed`() {
        assertEquals(
            WindowCloseDisposition.DISPOSE,
            decideWindowCloseDisposition(
                isMacOS = true,
                closingWindowType = WindowType.MAIN,
                hasOtherMainWindows = true,
            ),
        )
    }

    @Test
    fun `final non-macOS main window keeps existing dispose behavior`() {
        assertEquals(
            WindowCloseDisposition.DISPOSE,
            decideWindowCloseDisposition(
                isMacOS = false,
                closingWindowType = WindowType.MAIN,
                hasOtherMainWindows = false,
            ),
        )
    }

    @Test
    fun `auxiliary macOS window is disposed`() {
        assertEquals(
            WindowCloseDisposition.DISPOSE,
            decideWindowCloseDisposition(
                isMacOS = true,
                closingWindowType = WindowType.SETTINGS,
                hasOtherMainWindows = false,
            ),
        )
    }

    @Test
    fun `reopen reveals a retained main window`() {
        assertEquals(
            AppReopenAction.Reveal("retained"),
            decideAppReopen(
                windows =
                    listOf(
                        WindowLifecycleSnapshot(
                            id = "retained",
                            windowType = WindowType.MAIN,
                            isVisible = false,
                        ),
                    ),
                preferredWindowId = null,
            ),
        )
    }

    @Test
    fun `reopen focuses the preferred visible main window`() {
        assertEquals(
            AppReopenAction.Focus("second"),
            decideAppReopen(
                windows =
                    listOf(
                        WindowLifecycleSnapshot("first", WindowType.MAIN, isVisible = true),
                        WindowLifecycleSnapshot("second", WindowType.MAIN, isVisible = true),
                    ),
                preferredWindowId = "second",
            ),
        )
    }

    @Test
    fun `reopen creates a main window when none remains`() {
        assertEquals(
            AppReopenAction.CreateMainWindow,
            decideAppReopen(
                windows = emptyList(),
                preferredWindowId = null,
            ),
        )
    }

    @Test
    fun `reopen waits while initial main window creation is deferred`() {
        assertEquals(
            AppReopenAction.Ignore,
            decideAppReopen(
                windows = emptyList(),
                preferredWindowId = null,
                canCreateMainWindow = false,
            ),
        )
    }

    @Test
    fun `auxiliary windows do not satisfy an application reopen`() {
        assertEquals(
            AppReopenAction.CreateMainWindow,
            decideAppReopen(
                windows =
                    listOf(
                        WindowLifecycleSnapshot("settings", WindowType.SETTINGS, isVisible = true),
                        WindowLifecycleSnapshot("auth", WindowType.AUTH, isVisible = false),
                    ),
                preferredWindowId = "settings",
            ),
        )
    }
}
