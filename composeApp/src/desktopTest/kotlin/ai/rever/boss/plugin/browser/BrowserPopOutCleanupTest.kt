package ai.rever.boss.plugin.browser

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserPopOutCleanupTest {
    @Test
    fun `view and always-on-top failures still attempt frame disposal in order`() {
        val order = mutableListOf<String>()
        val released =
            closeBrowserPopOutResources(
                handleId = "test",
                detachView = {
                    order += "detach"
                    error("closed native view")
                },
                disableAlwaysOnTop = {
                    order += "always-on-top"
                    error("frame peer failed")
                },
                disposeWindow = { order += "dispose" },
            )
        assertTrue(released)
        assertEquals(listOf("detach", "always-on-top", "dispose"), order)
    }

    @Test
    fun `failed frame disposal retains ownership for a later attempt`() {
        assertFalse(
            closeBrowserPopOutResources(
                handleId = "test",
                detachView = {},
                disableAlwaysOnTop = {},
                disposeWindow = { error("frame disposal failed") },
            ),
        )
    }
}
