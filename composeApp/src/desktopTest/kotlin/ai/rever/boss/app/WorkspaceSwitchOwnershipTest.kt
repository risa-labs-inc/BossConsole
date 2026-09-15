package ai.rever.boss.app

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceApplyHooks
import ai.rever.boss.components.workspaces.WorkspaceSwitchAction
import ai.rever.boss.components.workspaces.applyPreparedWorkspace
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.isUnsaved
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceSwitchOwnershipTest {
    private class Stub(
        context: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by context {
        @Composable override fun Content() = Unit
    }

    private fun state(window: String): SplitViewState {
        val registry =
            TabRegistry().apply {
                registerTabType(CodeEditorTabType) { config, context -> Stub(context, config, CodeEditorTabType) }
                registerTabType(FluckTabType) { config, context -> Stub(context, config, FluckTabType) }
            }
        return SplitViewState(registry, windowId = window)
    }

    private fun space(
        id: String,
        browser: Boolean = false,
    ) = LayoutWorkspace(
        id = id,
        name = "Space $id",
        description = "Disposable test Space",
        projectPath = "/tmp",
        layout =
            SinglePanel(
                PanelConfig(
                    id = "main",
                    tabs =
                        listOf(
                            if (browser) {
                                TabConfig(type = "browser", title = "Browser", url = "about:blank")
                            } else {
                                TabConfig(type = "editor", title = "Editor", filePath = "/tmp/$id.txt")
                            },
                        ),
                ),
            ),
    )

    private fun live(state: SplitViewState) = extractCurrentWorkspace(state, "/tmp", defaultWorkingDirectory = "/tmp")

    @AfterTest fun clearRegistry() = TabUpdateRegistry.clear()

    @Test fun oldSlowPreparationCannotRelabelOrReplaceTheNewerWindowTree() =
        runBlocking {
            val state = state("switch-generation")
            val a = space("a")
            val b = space("b", browser = true)
            val c = space("c")
            applyWorkspace(a, state)
            val before = live(state).layout
            val owner = WorkspaceSwitchGeneration()
            val first = owner.next()
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            var oldCommitCalls = 0
            val old =
                launch {
                    applyPreparedWorkspace(
                        b,
                        state,
                        hooks =
                            WorkspaceApplyHooks(warmEngine = {
                                started.complete(Unit)
                                check(release.await(10, TimeUnit.SECONDS))
                            }, beforeApply = {
                                if (owner.isCurrent(first)) {
                                    oldCommitCalls++
                                    true
                                } else {
                                    false
                                }
                            }),
                    )
                }
            try {
                withTimeout(10_000) { started.await() }
                assertEquals("a", state.currentWorkspaceId)
                assertEquals(before, live(state).layout)
                val newest = owner.next()
                applyPreparedWorkspace(c, state, hooks = WorkspaceApplyHooks(beforeApply = { owner.isCurrent(newest) }))
                val newestLayout = live(state).layout
                release.countDown()
                old.join()
                assertEquals(0, oldCommitCalls)
                assertEquals("c", state.currentWorkspaceId)
                assertEquals(newestLayout, live(state).layout)
            } finally {
                release.countDown()
                old.cancel()
            }
        }

    @Test fun rejectedCommitKeepsUnsavedLayoutAndPreservedStateUntouched() =
        runBlocking {
            val state = state("switch-cancel")
            val a = space("a")
            applyWorkspace(a, state)
            state.preserveCurrentState("a", a.name)
            val before = live(state).layout
            applyPreparedWorkspace(space("b"), state, hooks = WorkspaceApplyHooks(beforeApply = { false }))
            assertEquals("a", state.currentWorkspaceId)
            assertEquals(before, live(state).layout)
            assertTrue(state.hasPreservedWorkspace("a"))
        }

    @Test fun rejectedPreservedRestoreCannotChangeVisibleIdentityOrTree() =
        runBlocking {
            val state = state("preserved-cancel")
            val a = space("a")
            val b = space("b")
            applyWorkspace(b, state)
            state.preserveCurrentState("b", b.name)
            applyWorkspace(a, state)
            val before = live(state).layout
            assertTrue(state.hasPreservedWorkspace("b"))
            applyPreparedWorkspace(b, state, hooks = WorkspaceApplyHooks(beforeApply = { false }))
            assertEquals("a", state.currentWorkspaceId)
            assertEquals(before, live(state).layout)
        }

    @Test fun preservationCreatedAtCommitKeepsUnsavedTreeOnSameSpaceReselection() =
        runBlocking {
            val state = state("same-space")
            val a = space("a")
            applyWorkspace(a, state)
            state.clearAllPanels()
            val edited = live(state).layout
            assertFalse(state.hasPreservedWorkspace(a.id))
            applyPreparedWorkspace(
                a,
                state,
                hooks =
                    WorkspaceApplyHooks(beforeApply = {
                        state.preserveCurrentState(a.id, a.name)
                        true
                    }),
            )
            assertEquals(a.id, state.currentWorkspaceId)
            assertEquals(edited, live(state).layout)
        }

    @Test fun dirtyWindowCannotBorrowAnotherWindowsIdentityOrCloseConsent() =
        runBlocking {
            val a = space("a")
            val b = space("b")
            val first = state("identity-first")
            val second = state("identity-second")
            applyWorkspace(a, first)
            applyWorkspace(b, second)
            assertEquals(a, windowSpaceIdentity(first.currentWorkspaceId, b, listOf(a, b)))
            assertEquals(b, windowSpaceIdentity(second.currentWorkspaceId, b, listOf(a, b)))
            assertEquals("last-session", windowSpaceIdentity("last-session", b, emptyList())?.id)
            assertEquals("Last Session", windowSpaceIdentity("last-session", b, emptyList())?.name)
            assertFalse(shouldPromptForWorkspaceSwitch(WorkspaceSwitchAction.CLOSE, isUnsaved(live(first), a)))
            first.clearAllPanels()
            assertTrue(shouldPromptForWorkspaceSwitch(WorkspaceSwitchAction.CLOSE, isUnsaved(live(first), a)))
            assertFalse(shouldPromptForWorkspaceSwitch(WorkspaceSwitchAction.CLOSE, isUnsaved(live(second), b)))
            assertTrue(
                shouldPromptForWorkspaceSwitch(WorkspaceSwitchAction.CLOSE, spaceIsUnsaved("last-session", emptySet())),
            )
            assertFalse(shouldPromptForWorkspaceSwitch(WorkspaceSwitchAction.KEEP, true))
            assertTrue(shouldPromptForWorkspaceSwitch(WorkspaceSwitchAction.ASK, false))
        }
}
