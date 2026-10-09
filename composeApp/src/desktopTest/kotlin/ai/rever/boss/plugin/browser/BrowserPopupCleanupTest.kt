package ai.rever.boss.plugin.browser

import org.junit.Test
import kotlin.test.assertEquals

class BrowserPopupCleanupTest {
    @Test
    fun `dead subscriptions and failed detach do not strand popup window`() {
        val order = mutableListOf<String>()
        var captures = 0
        val cleanup =
            BrowserPopupCleanup(
                unsubscribe = {
                    captures++
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
        assertEquals(listOf("unsubscribe failed", "unsubscribe remaining", "detach", "dispose", "browser"), order)
        cleanup.close()
        assertEquals(
            listOf(
                "unsubscribe failed",
                "unsubscribe remaining",
                "detach",
                "dispose",
                "browser",
                "unsubscribe failed",
                "detach",
            ),
            order,
        )
        assertEquals(1, captures, "Successful subscription capture must clear the owner's list only once")
    }

    @Test
    fun `browser close failure follows window disposal and reentrant close is harmless`() {
        val order = mutableListOf<String>()
        var attempts = 0
        lateinit var cleanup: BrowserPopupCleanup
        cleanup =
            BrowserPopupCleanup(
                unsubscribe = { emptyList() },
                detachView = { order += "detach" },
                disposeWindow = { order += "dispose" },
                closeBrowser = {
                    attempts++
                    order += "browser"
                    cleanup.close()
                    if (attempts == 1) error("native close failed")
                },
            )
        cleanup.close()
        assertEquals(listOf("detach", "dispose", "browser"), order)
        cleanup.close()
        cleanup.close()
        assertEquals(listOf("detach", "dispose", "browser", "browser"), order)
        assertEquals(2, attempts)
    }

    @Test
    fun `failed frame disposal retries without repeating successful releases`() {
        val order = mutableListOf<String>()
        var frameAttempts = 0
        var captures = 0
        val cleanup =
            BrowserPopupCleanup(
                unsubscribe = {
                    captures++
                    listOf({ order += "unsubscribe" })
                },
                detachView = { order += "detach" },
                disposeWindow = {
                    frameAttempts++
                    order += "dispose"
                    if (frameAttempts == 1) error("frame peer failed")
                },
                closeBrowser = { order += "browser" },
            )
        cleanup.close()
        cleanup.close()
        cleanup.close()
        assertEquals(listOf("unsubscribe", "detach", "dispose", "browser", "dispose"), order)
        assertEquals(2, frameAttempts)
        assertEquals(1, captures)
    }

    @Test
    fun `successful normal close is once-only even when every release reenters`() {
        val order = mutableListOf<String>()
        lateinit var cleanup: BrowserPopupCleanup
        cleanup =
            BrowserPopupCleanup(
                unsubscribe = {
                    order += "capture"
                    cleanup.close()
                    listOf({
                        order += "unsubscribe"
                        cleanup.close()
                    })
                },
                detachView = {
                    order += "detach"
                    cleanup.close()
                },
                disposeWindow = {
                    order += "dispose"
                    cleanup.close()
                },
                closeBrowser = {
                    order += "browser"
                    cleanup.close()
                },
            )
        repeat(3) { cleanup.close() }
        assertEquals(listOf("capture", "unsubscribe", "detach", "dispose", "browser"), order)
    }
}
