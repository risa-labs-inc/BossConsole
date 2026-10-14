package ai.rever.boss.components.sidebar

import ai.rever.boss.plugin.ui.TerminalTitleBarAction
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTitleBarBridgeTest {
    @Test
    fun `provider owns controls without a terminal composition and cleans up with window`() =
        runTest {
            val provider = Any()
            val actionsOwner = Any()
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(EmptyApplier(), recomposer)
            val action = TerminalTitleBarAction("call", "Call", "phone", Icons.Default.Call, false) {}
            try {
                TerminalTitleBarBridge.hostWindow("provider-window", true)
                TerminalTitleBarBridge.registerProvider(provider) { windowId ->
                    SideEffect {
                        TerminalTitleBarBridge.publish(windowId, actionsOwner, true, listOf(action))
                        TerminalTitleBarBridge.publishCallBar(windowId, actionsOwner) {}
                    }
                    DisposableEffect(Unit) {
                        onDispose {
                            TerminalTitleBarBridge.remove(actionsOwner)
                            TerminalTitleBarBridge.removeCallBar(actionsOwner)
                        }
                    }
                }
                composition.setContent { TerminalTitleBarBridge.Content("provider-window") }
                assertEquals(listOf(action), TerminalTitleBarBridge.actions("provider-window"))
                assertTrue(TerminalTitleBarBridge.actions("other-window").isEmpty())
                assertTrue(TerminalTitleBarBridge.hasCallBar("provider-window"))
                assertFalse(TerminalTitleBarBridge.hasCallBar("other-window"))
                TerminalTitleBarBridge.unregisterProvider(provider)
                composition.setContent { TerminalTitleBarBridge.Content("provider-window") }
                assertTrue(TerminalTitleBarBridge.actions("provider-window").isEmpty())
                assertFalse(TerminalTitleBarBridge.hasCallBar("provider-window"))
            } finally {
                composition.dispose()
                recomposer.cancel()
                TerminalTitleBarBridge.unregisterProvider(provider)
                TerminalTitleBarBridge.remove(actionsOwner)
                TerminalTitleBarBridge.hostWindow("provider-window", false)
            }
        }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun remove(
            index: Int,
            count: Int,
        ) = Unit

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) = Unit

        override fun onClear() = Unit
    }

    @Test
    fun `actions are isolated by window and removed with their owner`() {
        val first = Any()
        val second = Any()
        var clicks = 0
        val action = TerminalTitleBarAction("call", "Call", "phone", Icons.Default.Call, true) { clicks++ }
        try {
            assertFalse(TerminalTitleBarBridge.isHosted("test-first"))
            TerminalTitleBarBridge.hostWindow("test-first", true)
            assertTrue(TerminalTitleBarBridge.isHosted("test-first"))
            assertFalse(TerminalTitleBarBridge.isHosted("test-second"))
            TerminalTitleBarBridge.publish("test-first", first, true, listOf(action))
            TerminalTitleBarBridge.publish("test-second", second, true, listOf(action))
            TerminalTitleBarBridge.actions("test-first").single().onClick()
            assertEquals(1, clicks)
            TerminalTitleBarBridge.publish("test-first", first, false, listOf(action))
            assertTrue(TerminalTitleBarBridge.actions("test-first").isEmpty())
            assertEquals(1, TerminalTitleBarBridge.actions("test-second").size)
            TerminalTitleBarBridge.remove(second)
            assertTrue(TerminalTitleBarBridge.actions("test-second").isEmpty())
        } finally {
            TerminalTitleBarBridge.remove(first)
            TerminalTitleBarBridge.remove(second)
            TerminalTitleBarBridge.hostWindow("test-first", false)
        }
    }
}
