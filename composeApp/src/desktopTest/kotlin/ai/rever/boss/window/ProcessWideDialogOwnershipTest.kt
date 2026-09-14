package ai.rever.boss.window

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessWideDialogOwnershipTest {
    @Test
    fun `both capture flows are collected only inside the owner gate`() {
        val source = File("src/desktopMain/kotlin/ai/rever/boss/window/BossWindow.kt").readText()
        val captureSection = source.substringAfter("// Capture state is process-global.")
        val gate = "if (isDialogOwner) {"
        assertTrue(captureSection.contains(gate))
        val body = captureSection.substringAfter(gate)
        var depth = 1
        val end = body.indexOfFirst { character ->
            if (character == '{') depth++
            if (character == '}') depth--
            depth == 0
        }
        assertTrue(end >= 0)
        val ownedBody = body.substring(0, end)
        for (flow in listOf("captureRequest", "permissionRationale")) {
            val collection = "ScreenCaptureNotifier.$flow.collectAsState()"
            assertTrue(ownedBody.contains(collection), "$flow must be collected by the owner")
            assertTrue(source.indexOf(collection) == source.lastIndexOf(collection), "$flow has another collector")
        }
        assertTrue(ownedBody.contains("composeWindowState.isMinimized = false"))
        assertTrue(ownedBody.contains("WindowFocusManager.focusWindow(windowState.id)"))
    }

    @Test
    fun `only the first window owns process-wide dialogs`() {
        val windows = listOf("primary", "secondary", "third")

        assertTrue(ownsProcessWideDialogs("primary", windows))
        assertFalse(ownsProcessWideDialogs("secondary", windows))
        assertFalse(ownsProcessWideDialogs("third", windows))
    }

    @Test
    fun `ownership passes to the next window when the first closes`() {
        assertTrue(ownsProcessWideDialogs("secondary", listOf("secondary", "third")))
        assertFalse(ownsProcessWideDialogs("missing", emptyList()))
    }
}
