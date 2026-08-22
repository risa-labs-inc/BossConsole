package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a layout being applied is worth booting Chromium for, and - the part that carries the
 * change - that the boot is asked for BEFORE the wait for plugin tab types, not after.
 *
 * The wait is bounded dead time at startup while the browser plugin registers its factory. Asking
 * during it is what turns a cold engine boot from something the user watches inside their first tab
 * into something that already happened. Move those lines below the wait and the benefit evaporates
 * with every other test still green, which is why the ordering is pinned here and not just the
 * predicate.
 *
 * The predicate has to stay narrow in the other direction too: a terminal-only or editor-only
 * layout must not start an engine nobody asked for, which is the whole reason the unforced startup
 * gate exists.
 */
class BrowserEngineWarmupTriggerTest {
    /** Minimal stand-in; the applier only builds TabInfo, it never renders the component. */
    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    @Test
    fun `a layout with a browser tab warms the engine`() {
        assertTrue(needsBrowserEngine(setOf(FluckTabType.typeId)))
        assertTrue(needsBrowserEngine(setOf(TerminalTabType.typeId, FluckTabType.typeId)))
    }

    @Test
    fun `a layout without one does not`() {
        assertFalse(needsBrowserEngine(emptySet()))
        assertFalse(needsBrowserEngine(setOf(TerminalTabType.typeId)))
        assertFalse(needsBrowserEngine(setOf(TerminalTabType.typeId, CodeEditorTabType.typeId)))
    }

    @Test
    fun `the warm-up is requested before the applier waits for the browser tab type`() {
        val registry = TabRegistry()
        val splitViewState = SplitViewState(registry, windowId = "warmup-test-window")
        val warmed = AtomicBoolean(false)

        runBlocking {
            // The browser tab type is deliberately NOT registered yet, so applyWorkspace parks in
            // awaitTabTypes - exactly the startup situation. Anything the warm-up hook does after
            // that wait cannot be observed until the type is registered, so seeing `warmed` flip
            // while the applier is still parked IS the ordering assertion.
            val applying =
                launch(Dispatchers.Default) {
                    applyWorkspace(
                        workspace = browserWorkspace(),
                        splitViewState = splitViewState,
                        warmEngine = { warmed.set(true) },
                    )
                }

            withTimeout(10_000) {
                while (!warmed.get()) yield()
            }
            assertTrue(warmed.get(), "the engine boot must be asked for while the applier is waiting")
            assertTrue(applying.isActive, "the applier must still be parked on the tab-type wait")

            // Let it finish so the applier does not sit on the bounded wait for the whole timeout.
            registry.registerTabType(FluckTabType) { config, ctx -> StubTabComponent(ctx, config, FluckTabType) }
            applying.join()
        }
    }

    @Test
    fun `a terminal-only layout never asks for the engine`() {
        val registry =
            TabRegistry().apply {
                registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
            }
        val splitViewState = SplitViewState(registry, windowId = "warmup-test-window-terminal")
        val warmed = AtomicBoolean(false)

        runBlocking {
            applyWorkspace(
                workspace =
                    LayoutWorkspace(
                        id = "warmup-test-terminal",
                        name = "Terminal only",
                        description = "One terminal",
                        layout =
                            SinglePanel(
                                PanelConfig(id = "main", tabs = listOf(TabConfig(type = "terminal", title = "Term"))),
                            ),
                    ),
                splitViewState = splitViewState,
                warmEngine = { warmed.set(true) },
            )
        }

        assertFalse(warmed.get(), "a terminal-only session must not pay a Chromium spawn")
    }

    private fun browserWorkspace() =
        LayoutWorkspace(
            id = "warmup-test-browser",
            name = "Browser only",
            description = "One browser tab",
            layout =
                SinglePanel(
                    PanelConfig(
                        id = "main",
                        tabs = listOf(TabConfig(type = "browser", title = "Loading...", url = "https://example.com")),
                    ),
                ),
        )
}
