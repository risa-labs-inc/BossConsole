// Deliberately not in the registry's own package: `window_panel` has an underscore, which
// detekt's PackageNaming rule rejects for anything new. `internal` is module-wide, so
// RegisterSplitViewState is reachable from here regardless.
package ai.rever.boss.components.windowpanel

import ai.rever.boss.components.window_panel.RegisterSplitViewState
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.TabRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * b08: `SplitViewStateRegistry` entries used to be added by a `LaunchedEffect` with no
 * cleanup, while the unregister lived in a distant `DisposableEffect` keyed on seven other
 * values - a key change there dropped a live window, and a throw earlier in that onDispose
 * leaked a dead one for every `getAllStates` caller (Top of Mind, the MCP tools). The
 * register/unregister pair is now one effect keyed on `(windowId, splitViewState)`, so the
 * entry's lifetime is the window's composition lifetime.
 */
@OptIn(ExperimentalTestApi::class)
class SplitViewStateRegistryLifecycleTest {
    // The registry is process-wide and shared with other tests in this JVM - whatever this
    // suite registered must not outlive it.
    @AfterTest
    fun cleanup() {
        SplitViewStateRegistry
            .getAllStates()
            .keys
            .filter { it.startsWith(WINDOW_PREFIX) }
            .forEach(SplitViewStateRegistry::unregister)
    }

    @Test
    fun `closing windows returns the registry to baseline with no dead windows`() =
        runComposeUiTest {
            val windowIds = (1..3).map { "$WINDOW_PREFIX$it" }
            val baseline = SplitViewStateRegistry.getAllStates().keys
            var open by mutableStateOf(true)

            setContent {
                if (open) {
                    for (id in windowIds) {
                        // One remembered state per window id, like a real window holding its own.
                        val state = remember(id) { SplitViewState(TabRegistry(), id) }
                        RegisterSplitViewState(id, state)
                    }
                }
            }
            waitForIdle()
            assertEquals(baseline + windowIds.toSet(), SplitViewStateRegistry.getAllStates().keys)

            open = false
            waitForIdle()

            assertEquals(baseline, SplitViewStateRegistry.getAllStates().keys)
            assertTrue(
                windowIds.none(SplitViewStateRegistry::isRegistered),
                "closed windows must not stay enumerable - that is the b08 leak",
            )
        }

    @Test
    fun `replacing a window's state re-registers it rather than stranding the old entry`() =
        runComposeUiTest {
            val windowId = "${WINDOW_PREFIX}swap"
            var generation by mutableStateOf(0)

            setContent {
                val state = remember(generation) { SplitViewState(TabRegistry(), windowId) }
                RegisterSplitViewState(windowId, state)
            }
            waitForIdle()
            val first = SplitViewStateRegistry.getState(windowId)

            generation = 1
            waitForIdle()

            // The old state is gone with its effect and the live one answers lookups: the pair
            // cannot leave the stale entry behind the way a distant unregister could.
            val second = SplitViewStateRegistry.getState(windowId)
            assertNotNull(second)
            assertTrue(second !== first)
            assertSame(second, SplitViewStateRegistry.getAllStates()[windowId])
        }

    private companion object {
        const val WINDOW_PREFIX = "b08-registry-test-"
    }
}
