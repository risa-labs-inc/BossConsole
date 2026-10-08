package ai.rever.boss.plugin.browser

import org.junit.Test
import kotlin.test.assertEquals

class BrowserPopupCleanupTest {
    @Test
    fun `dead subscriptions and failed detach do not strand popup window`() {
        val order = mutableListOf<String>()
        val cleanup =
            BrowserPopupCleanup(
                unsubscribe = {
                    listOf(
                        {
                            order += "unsubscribe failed"
                            error("native transport closed")
                        },
                        { order += "unsubscribe remaining" },
                    )
                },
                detachView = {
                    order += "detach"
                    error("closed view")
                },
                disposeWindow = { order += "dispose" },
                closeBrowser = { order += "browser" },
            )
        cleanup.close()
        cleanup.close()
        assertEquals(listOf("unsubscribe failed", "unsubscribe remaining", "detach", "dispose", "browser"), order)
    }

    @Test
    fun `browser close failure follows window disposal and reentrant close is harmless`() {
        val order = mutableListOf<String>()
        lateinit var cleanup: BrowserPopupCleanup
        cleanup =
            BrowserPopupCleanup(
                unsubscribe = { emptyList() },
                detachView = { order += "detach" },
                disposeWindow = { order += "dispose" },
                closeBrowser = {
                    order += "browser"
                    cleanup.close()
                    error("native close failed")
                },
            )
        cleanup.close()
        assertEquals(listOf("detach", "dispose", "browser"), order)
    }
}
