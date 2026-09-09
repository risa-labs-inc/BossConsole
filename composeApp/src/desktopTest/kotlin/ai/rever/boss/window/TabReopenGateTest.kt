package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Cmd+Shift+T gate in [AWTKeyboardInterceptor.dispatchAction].
 *
 * The File menu item has always been gated on [ClosedTabHistory.hasEntries]; the interceptor was
 * not, so on an empty stack the chord was consumed and nothing happened. Cmd+Shift+T is a plain
 * Cmd+Shift+letter, so the surface that had focus may well have wanted it - the same argument
 * [TabStepGateTest] and [TabSelectGateTest] make for their chords.
 */
class TabReopenGateTest {
    private data class FakeTab(
        override val id: String,
        override val title: String = id,
    ) : TabInfo {
        override val typeId = TabTypeId("test", "test")
        override val icon: ImageVector = Icons.Default.Add
        override val tabIcon: TabIcon? = null
    }

    private val windowId = "reopen-gate-window"

    @BeforeTest
    @AfterTest
    fun reset() {
        ClosedTabHistory.clear(windowId)
    }

    @Test
    fun `an empty stack leaves the chord to the focused component`() {
        assertFalse(
            AWTKeyboardInterceptor.dispatchAction(KeymapActions.TAB_REOPEN_CLOSED, windowId),
            "consuming Cmd+Shift+T with nothing to reopen takes it from whatever had focus",
        )
    }

    @Test
    fun `a non-empty stack claims the chord`() {
        ClosedTabHistory.record(windowId, FakeTab("closed-1"))

        assertTrue(AWTKeyboardInterceptor.dispatchAction(KeymapActions.TAB_REOPEN_CLOSED, windowId))
    }

    @Test
    fun `the gate and the menu item's enabled flag read the same state`() {
        assertFalse(ClosedTabHistory.hasEntries(windowId))
        assertFalse(AWTKeyboardInterceptor.dispatchAction(KeymapActions.TAB_REOPEN_CLOSED, windowId))

        ClosedTabHistory.record(windowId, FakeTab("closed-1"))
        assertTrue(ClosedTabHistory.hasEntries(windowId))
        assertTrue(AWTKeyboardInterceptor.dispatchAction(KeymapActions.TAB_REOPEN_CLOSED, windowId))
    }
}
