package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That a change to a pane's TAB LIST is actually observed.
 *
 * **A unit test on `isUnsaved` cannot catch this defect and did not.** Both halves were correct in
 * isolation: the extractor reads the tabs, and the comparison notices a difference. What was
 * missing was the SUBSCRIPTION between them - `tabsState` is a Decompose `Value`, so
 * `tabsState.value` in the extractor registers no Compose snapshot read, and the watcher's
 * `snapshotFlow` never re-ran for a tab-only change. That is why this test collects the real flow
 * against a real [SplitViewState] and mutates it, rather than asserting anything about the rule
 * downstream of it.
 *
 * The signal is what is tested rather than the whole watcher: the watcher lives inside a
 * `@Composable`'s `LaunchedEffect` and cannot be driven from here, but everything it does after
 * this flow emits is a pure function ([extractCurrentWorkspace], [isUnsaved]) with tests of its
 * own. The seam that broke is the one under test.
 */
class TabListChangesTest {
    private class StubComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = CodeEditorTabType

        @Composable
        override fun Content() {
        }
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(CodeEditorTabType) { config, ctx -> StubComponent(ctx, config) }
        }

    @AfterTest
    fun tearDown() = TabUpdateRegistry.clear()

    private var nextWindow = 0

    private fun newState() = SplitViewState(tabRegistry, windowId = "tab-changes-${nextWindow++}")

    private fun editor(name: String) =
        EditorTabInfo(
            id = "editor-$name",
            typeId = CodeEditorTabType.typeId,
            title = name,
            filePath = "/tmp/$name",
        )

    /**
     * Collect [SplitViewState.tabListChanges] and count what arrives, so a test can say "one more
     * signal reached the watcher after I did that".
     *
     * The initial signal is consumed before the test acts: Decompose calls a new observer
     * immediately with the current value, so collection always starts with one.
     */
    private inner class Signals(
        val state: SplitViewState,
        scope: CoroutineScope,
    ) {
        private val count = AtomicInteger(0)

        init {
            state.tabListChanges().onEach { count.incrementAndGet() }.launchIn(scope)
        }

        /** Wait for the count to move past [from], or give up. Returns the count reached. */
        suspend fun awaitBeyond(from: Int): Int =
            withTimeoutOrNull(SIGNAL_TIMEOUT_MS) {
                while (count.get() <= from) {
                    // The tree half is a snapshotFlow, which only re-emits once Compose has
                    // published the write. Nothing here runs a frame clock, so the test sends the
                    // notification itself, exactly as a composition would.
                    Snapshot.sendApplyNotifications()
                    yield()
                }
                count.get()
            } ?: count.get()

        suspend fun settled(): Int {
            awaitBeyond(0)
            return count.get()
        }
    }

    private fun withSignals(block: suspend (Signals) -> Unit) =
        runBlocking(Dispatchers.Default) {
            val state = newState()
            val signals = Signals(state, this)
            signals.settled()
            block(signals)
            // The collectors live on this scope; cancelling them is what closes the
            // subscriptions, so runBlocking does not wait on a flow that never completes.
            coroutineContext.cancelChildren()
        }

    // ==================== the reported case ====================

    @Test
    fun `adding a tab is observed`() {
        // The user's report. Before the subscription existed, nothing downstream of the watcher
        // ran at all for this: the Space stayed marked saved and the tab never reached the Last
        // Session record.
        withSignals { signals ->
            val before = signals.settled()

            signals.state
                .getPanel("main")!!
                .tabsComponent
                .addTab(editor("a"))

            assertTrue(
                signals.awaitBeyond(before) > before,
                "adding a tab must reach the watcher; it is a Decompose Value, not Compose state",
            )
        }
    }

    // ==================== the rest of the same blind spot ====================

    @Test
    fun `closing a tab is observed`() {
        withSignals { signals ->
            val panel = signals.state.getPanel("main")!!.tabsComponent
            panel.addTab(editor("a"))
            val before = signals.awaitBeyond(0)

            panel.removeTab(0)

            assertTrue(signals.awaitBeyond(before) > before)
        }
    }

    @Test
    fun `reordering within a pane is observed`() {
        withSignals { signals ->
            val panel = signals.state.getPanel("main")!!.tabsComponent
            panel.addTab(editor("a"))
            panel.addTab(editor("b"))
            val before = signals.awaitBeyond(0)

            panel.moveTab(0, 1)

            assertTrue(signals.awaitBeyond(before) > before)
        }
    }

    @Test
    fun `a tab moved between panes is observed`() {
        withSignals { signals ->
            val left = signals.state.getPanel("main")!!.tabsComponent
            left.addTab(editor("a"))
            left.addTab(editor("b"))
            val right = signals.state.splitPanel("main", SplitOrientation.VERTICAL)
            val before = signals.awaitBeyond(0)

            // The transfer path this branch added: the live component moves, it is not rebuilt.
            val moved =
                left.detachTab(
                    left.tabsState.value.tabs
                        .first()
                        .id,
                )
            assertTrue(moved != null, "the tab has to be detachable for this to test the move")
            signals.state
                .getPanel(right)!!
                .tabsComponent
                .adoptTab(moved)

            assertTrue(signals.awaitBeyond(before) > before)
        }
    }

    @Test
    fun `a pane created by a split is subscribed to as well`() {
        // The resubscribe. A subscription set up once at collection would never hear from a pane
        // that did not exist yet, so every tab added to a new split would be invisible.
        withSignals { signals ->
            val right = signals.state.splitPanel("main", SplitOrientation.VERTICAL)
            val before = signals.awaitBeyond(0)

            signals.state
                .getPanel(right)!!
                .tabsComponent
                .addTab(editor("new pane"))

            assertTrue(
                signals.awaitBeyond(before) > before,
                "a pane created after collection started must be observed too",
            )
        }
    }

    // ==================== and that the signal is worth acting on ====================

    /**
     * The other end of the wire: what the watcher does when a signal arrives. Kept in this file
     * because the defect was that these two never met - the extract was right and the signal was
     * missing, and each was tested on its own.
     */
    @Test
    fun `a re-extract after a tab add reads as unsaved against the copy saved before it`() {
        withSignals { signals ->
            val panel = signals.state.getPanel("main")!!.tabsComponent
            panel.addTab(editor("a"))
            signals.awaitBeyond(0)

            val live = extractCurrentWorkspace(signals.state, projectPath = "/tmp/p")
            val saved = live.copy(id = "workspace-1", name = "My Space", description = "d")
            assertEquals(false, isUnsaved(live, saved), "clean before the change")

            val before = signals.settled()
            panel.addTab(editor("b"))
            assertTrue(signals.awaitBeyond(before) > before, "the signal is what makes the rest run")

            val after = extractCurrentWorkspace(signals.state, projectPath = "/tmp/p")
            assertTrue(isUnsaved(after, saved), "and the re-extract is what the mark is computed from")
        }
    }

    private companion object {
        /** Long enough for a subscription to deliver, short enough that a failure is not a hang. */
        const val SIGNAL_TIMEOUT_MS = 2_000L
    }
}
