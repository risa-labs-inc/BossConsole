package ai.rever.boss.plugin.sandbox.ui

import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.SandboxState
import ai.rever.boss.plugin.sandbox.health.PluginHealthMetrics
import ai.rever.boss.plugin.ui.LocalPopupLayeringRequired
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the WIRING, which the registry's own tests cannot see.
 *
 * `PluginUiMountRegistryTest` proves the registry counts and waits correctly. That is worth
 * nothing if the boundary never calls it - the registry would stay empty, every wait would return
 * instantly, and the whole fix would be inert while its unit tests stayed green.
 *
 * Two kinds of test live here, and the difference matters:
 *
 * - The `BoundaryUnderTest` tests use a **replica** of the registering effect. They pin the
 *   ordering contract cheaply, but they would all still pass if the registration were deleted from
 *   the real `PluginErrorBoundary` - they cannot see the wiring they are named for.
 * - The `PluginErrorBoundary` tests drive the **real composable** through a fake sandbox. Those are
 *   the ones that fail if the effect goes missing or moves out of the content branch.
 *
 * It also pins the ordering the fix rests on: **the count must drop AFTER the plugin content's own
 * onDispose has run.** If it dropped first, the unload would be told "disposed" while the very
 * lambdas it is waiting for were still to execute - the original bug, restored, with nothing else
 * in the suite noticing. Compose dispatches remember observers inner-first, so the boundary's
 * effect (outermost) runs last; this test fails if that ever inverts.
 */
@OptIn(ExperimentalTestApi::class)
class PluginUiMountWiringTest {
    @BeforeTest
    fun setUp() = PluginUiMountRegistry.reset()

    @AfterTest
    fun tearDown() = PluginUiMountRegistry.reset()

    @Test
    fun `a mounted boundary is counted, and stops being counted when it goes`() =
        runComposeUiTest {
            var mounted by mutableStateOf(true)
            setContent {
                Box(Modifier) {
                    if (mounted) {
                        BoundaryUnderTest { }
                    }
                }
            }

            waitForIdle()
            assertTrue(PluginUiMountRegistry.isMounted(PLUGIN_ID), "the boundary did not register")

            mounted = false
            waitForIdle()
            assertFalse(PluginUiMountRegistry.isMounted(PLUGIN_ID), "the boundary did not unregister")
        }

    @Test
    fun `two boundaries for one plugin are counted separately`() =
        runComposeUiTest {
            // A tab and a sidebar panel, or a tab in each of two windows. The first to go must not
            // report the plugin as gone - that is exactly when the loader would close under the
            // second.
            var second by mutableStateOf(true)
            setContent {
                Box(Modifier) {
                    BoundaryUnderTest { }
                    if (second) {
                        BoundaryUnderTest { }
                    }
                }
            }

            waitForIdle()
            assertEquals(2, PluginUiMountRegistry.mounted.value[PLUGIN_ID])

            second = false
            waitForIdle()
            assertTrue(PluginUiMountRegistry.isMounted(PLUGIN_ID), "one surface is still up")
            assertEquals(1, PluginUiMountRegistry.mounted.value[PLUGIN_ID])
        }

    @Test
    fun `the count drops only after the plugin's own onDispose has run`() =
        runComposeUiTest {
            // The ordering the whole fix depends on. The content's onDispose stands in for a
            // plugin's - the lambda that, in the real bug, could no longer resolve its own class.
            var mounted by mutableStateOf(true)
            var countWhenContentDisposed: Int? = null

            setContent {
                Box(Modifier) {
                    if (mounted) {
                        BoundaryUnderTest {
                            DisposableEffect(Unit) {
                                onDispose {
                                    countWhenContentDisposed = PluginUiMountRegistry.mounted.value[PLUGIN_ID]
                                }
                            }
                        }
                    }
                }
            }

            waitForIdle()
            mounted = false
            waitForIdle()

            assertEquals(
                1,
                countWhenContentDisposed,
                "the registry said 'disposed' while the plugin's own onDispose was still to run - " +
                    "an unload waiting on it would close the classloader too early",
            )
            assertFalse(PluginUiMountRegistry.isMounted(PLUGIN_ID), "and it must be zero afterwards")
        }

    @Test
    fun `the real boundary registers while it is rendering plugin content`() =
        runComposeUiTest {
            var shown by mutableStateOf(true)
            setContent {
                if (shown) {
                    PluginErrorBoundary(
                        pluginId = PLUGIN_ID,
                        sandbox = FakeSandbox(SandboxState.RUNNING),
                        onRestart = {},
                    ) { Box(Modifier) }
                }
            }
            waitForIdle()
            assertTrue(
                PluginUiMountRegistry.isMounted(PLUGIN_ID),
                "the real PluginErrorBoundary must register - if this fails while the " +
                    "BoundaryUnderTest tests pass, the effect was deleted or moved and every " +
                    "unload wait is now silently instant",
            )

            shown = false
            waitForIdle()
            assertFalse(PluginUiMountRegistry.isMounted(PLUGIN_ID), "and unregister when it goes")
        }

    @Test
    fun `the real boundary marks plugin popups for cross-scene layering`() =
        runComposeUiTest {
            var layeringRequired: Boolean? = null
            setContent {
                PluginErrorBoundary(
                    pluginId = PLUGIN_ID,
                    sandbox = FakeSandbox(SandboxState.RUNNING),
                    onRestart = {},
                ) {
                    layeringRequired = LocalPopupLayeringRequired.current
                    Box(Modifier)
                }
            }
            waitForIdle()

            assertEquals(true, layeringRequired)
        }

    @Test
    fun `safe plugin content marks popups for cross-scene layering`() =
        runComposeUiTest {
            var layeringRequired: Boolean? = null
            setContent {
                SafePluginContent(
                    pluginId = PLUGIN_ID,
                    sandbox = FakeSandbox(SandboxState.RUNNING),
                    fallback = {},
                ) {
                    layeringRequired = LocalPopupLayeringRequired.current
                    Box(Modifier)
                }
            }
            waitForIdle()

            assertEquals(true, layeringRequired)
        }

    @Test
    fun `a boundary rendering the fallback is not counted`() =
        runComposeUiTest {
            setContent {
                PluginErrorBoundary(
                    pluginId = PLUGIN_ID,
                    // DISABLED synthesises an error, so the boundary takes the fallback branch
                    // without needing a real crash.
                    sandbox = FakeSandbox(SandboxState.DISABLED),
                    onRestart = {},
                ) { Box(Modifier) }
            }
            waitForIdle()
            assertFalse(
                PluginUiMountRegistry.isMounted(PLUGIN_ID),
                "a fallback composes no plugin code, so it has no plugin onDispose lambdas for an " +
                    "unload to wait on - counting it made crash recovery pay the full timeout for " +
                    "nothing",
            )
        }
}

/** Enough of a [PluginSandbox] to mount the real boundary; the boundary only reads `state`. */
private class FakeSandbox(
    state: SandboxState,
) : PluginSandbox {
    override val pluginId = PLUGIN_ID
    override val state: StateFlow<SandboxState> = MutableStateFlow(state)
    override val healthMetrics: StateFlow<PluginHealthMetrics> = MutableStateFlow(PluginHealthMetrics())
    override val sandboxScope = CoroutineScope(SupervisorJob())

    override fun recordHeartbeat() = Unit

    override fun recordSuccess() = Unit

    override fun recordError(error: Throwable) = Unit

    override suspend fun start() = Result.success(Unit)

    override suspend fun stop() = Result.success(Unit)

    override suspend fun restart() = Result.success(Unit)

    override fun markUnhealthy() = Unit

    override fun resetHealth() = Unit

    override fun resetRestartAttempts() = Unit
}

/**
 * The registering half of `PluginErrorBoundary`, mounted on its own.
 *
 * A replica, deliberately: these three tests are about Compose's dispatch ORDER, and a replica
 * isolates that from everything else the real boundary drags in. It proves nothing about whether
 * the real boundary registers at all - the `PluginErrorBoundary` tests above cover that, and the
 * two are only meaningful together.
 *
 * Kept in the same position as the real one - outermost, around the content, inside the branch
 * that renders plugin code.
 */
@Composable
private fun BoundaryUnderTest(content: @Composable () -> Unit) {
    DisposableEffect(PLUGIN_ID) {
        PluginUiMountRegistry.onMounted(PLUGIN_ID)
        onDispose { PluginUiMountRegistry.onDisposed(PLUGIN_ID) }
    }
    content()
}

private const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.terminaltab"
