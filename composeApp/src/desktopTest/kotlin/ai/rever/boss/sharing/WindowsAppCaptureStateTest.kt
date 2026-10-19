package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Identity/transition contract only; this suite does not establish Windows native runtime support. */
class WindowsAppCaptureStateTest {
    private val handle = 0x1234L
    private val processId = 42L
    private val minimized = WindowsCaptureWindowState(true, processId, handle, true, true)

    @Test
    fun `native minimized state is usable before AWT receives ICONIFIED`() {
        val reads = mutableListOf<Long>()
        assertTrue(
            exactOwnedWindowsWindowMinimized(handle, processId) {
                reads += it
                minimized
            },
        )
        assertEquals(listOf(handle, handle), reads)
    }

    @Test
    fun `invalid source never queries native windows`() {
        assertFalse(exactOwnedWindowsWindowMinimized(0, processId) { error("Unexpected native query") })
        assertFalse(exactOwnedWindowsWindowMinimized(handle, 0) { error("Unexpected native query") })
        assertFalse(exactOwnedWindowsWindowMinimized(handle, 0x100000000L) { error("Unexpected native query") })
    }

    @Test
    fun `closed hidden foreign child or restored window is never a paused root`() {
        val rejected =
            listOf(
                minimized.copy(live = false),
                minimized.copy(visible = false),
                minimized.copy(processId = processId + 1),
                minimized.copy(rootHandle = handle + 1),
                minimized.copy(minimized = false),
            )
        rejected.forEach { state ->
            assertFalse(exactOwnedWindowsWindowMinimized(handle, processId) { state })
            var reads = 0
            assertFalse(
                exactOwnedWindowsWindowMinimized(handle, processId) {
                    if (reads++ == 0) minimized else state
                },
                "Identity and minimize state must still hold after the initial observation: $state",
            )
        }
    }
}
