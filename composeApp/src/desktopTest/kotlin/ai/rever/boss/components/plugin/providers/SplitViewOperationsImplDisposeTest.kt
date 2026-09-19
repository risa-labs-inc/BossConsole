package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SplitViewOperationsImpl] owns a coroutine scope for its plugin-facing operations. It must be
 * a [DisposableProvider] whose [dispose] cancels that scope, so a closed window does not leak it
 * or its in-flight Main-dispatched work.
 */
class SplitViewOperationsImplDisposeTest {
    private fun operations(scope: CoroutineScope) =
        SplitViewOperationsImpl(
            splitViewState = SplitViewState(TabRegistry(), "split-ops-dispose-test"),
            windowId = "split-ops-dispose-test",
            scope = scope,
        )

    @Test
    fun `dispose cancels the operations scope`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ops = operations(scope)
        assertTrue(scope.isActive, "scope should be active before dispose")

        ops.dispose()

        assertFalse(scope.isActive, "dispose must cancel the scope so it does not leak")
    }

    @Test
    fun `dispose is idempotent`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ops = operations(scope)

        ops.dispose()
        ops.dispose()

        assertFalse(scope.isActive)
    }
}
