package ai.rever.boss.window

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source wiring checks: exercising main itself would start native services and user UI. */
class MacOSWindowStartupWiringTest {
    @Test
    fun `every successful Chromium download enables Dock window creation`() {
        val main = File(repoRoot(), "composeApp/src/desktopMain/kotlin/ai/rever/boss/main.kt").readText()
        val completionPaths = main.split("if (progress.isComplete) {").drop(1)
        assertEquals(2, completionPaths.size, "Cover both the initial download and retry")
        completionPaths.forEachIndexed { index, path ->
            val completion = path.substringBefore("isDownloadingChromium = false")
            val create = completion.indexOf("WindowManager.createNewWindow()")
            val enableReopen = completion.indexOf("canCreateMainWindow.set(true)")
            assertTrue(
                create >= 0 && enableReopen > create,
                "Download completion path $index must create its window before enabling Dock creation",
            )
        }
    }
}
