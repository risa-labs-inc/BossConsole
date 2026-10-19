package ai.rever.boss.window

import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.sharing.AwtAppInputSink
import ai.rever.boss.sharing.onEdt
import androidx.compose.ui.awt.ComposeWindow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedWindowControlsTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
    fun `live registration stays scoped and retires without removing its replacement`() =
        onEdt {
            val window =
                ComposeWindow().apply {
                    focusableWindowState = false
                    setBounds(100, 100, 360, 180)
                    setContent { }
                    isVisible = true
                }
            var delivered = 0
            var allowed = true
            val sink = AwtAppInputSink(window, privateSurfaceAllowed = { allowed }, requireForeground = false)
            val first = OwnedWindowControls.register("synthetic-window", window, mapOf("close" to { delivered += 100 }))
            val replacement =
                OwnedWindowControls.register(
                    "synthetic-window",
                    window,
                    mapOf("close" to { delivered++ }),
                )
            try {
                first.close()
                assertEquals(listOf("close"), OwnedWindowControls.capabilities("synthetic-window"))
                assertTrue(sink.apply(AppInputEvent.Window("close")))
                assertEquals(1, delivered)
                allowed = false
                assertFalse(sink.apply(AppInputEvent.Window("close")))
                allowed = true
                assertFalse(sink.apply(AppInputEvent.Window("close"), 0))
                assertFalse(sink.apply(AppInputEvent.Window("start-capture")))
                replacement.close()
                assertTrue(OwnedWindowControls.capabilities("synthetic-window").isEmpty())
                assertFalse(sink.apply(AppInputEvent.Window("close")))
                assertEquals(1, delivered)
            } finally {
                replacement.close()
                first.close()
                sink.releaseAll()
                window.dispose()
            }
        }

    @Test
    fun `commands require current native identity authority and lifetime`() =
        onEdt {
            var handle: Long? = 12L
            var closes = 0
            val entry = OwnedWindowControlEntry(12L, { handle }, mapOf("close" to { closes++ }))
            assertEquals(listOf("close"), entry.capabilities())
            assertFalse(entry.perform("close", Long.MAX_VALUE) { false })
            assertFalse(entry.perform("close", 0) { true })
            assertFalse(entry.perform("start-capture", Long.MAX_VALUE) { true })
            assertTrue(entry.perform("close", Long.MAX_VALUE) { true })
            assertEquals(1, closes)
            handle = 13L
            assertFalse(entry.perform("close", Long.MAX_VALUE) { true })
            assertTrue(entry.capabilities().isEmpty())
            handle = 12L
            assertFalse(
                entry.perform("close", Long.MAX_VALUE) {
                    handle = null
                    true
                },
            )
            handle = 12L
            entry.retire()
            assertFalse(entry.perform("close", Long.MAX_VALUE) { true })
            assertEquals(1, closes)
        }

    @Test
    fun `unknown callbacks and unregistered windows never advertise capabilities`() =
        onEdt {
            var invoked = false
            val entry = OwnedWindowControlEntry(12L, { 12L }, mapOf("start-capture" to { invoked = true }))
            assertTrue(entry.capabilities().isEmpty())
            assertFalse(entry.perform("start-capture", Long.MAX_VALUE) { true })
            assertFalse(invoked)
            assertTrue(OwnedWindowControls.capabilities("unregistered-window").isEmpty())
            assertTrue(OwnedWindowControlEntry(0L, { 0L }, mapOf("close" to {})).capabilities().isEmpty())
        }
}
