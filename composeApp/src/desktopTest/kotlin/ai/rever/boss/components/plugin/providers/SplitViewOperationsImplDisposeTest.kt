package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SplitViewOperationsImpl] owns a coroutine scope for its plugin-facing operations. It must be
 * a [DisposableProvider] whose [dispose] cancels that scope, so a closed window does not leak it
 * or its in-flight Main-dispatched work, and the scope must be a [SupervisorJob] so one failing
 * operation does not silently kill every later one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `a failed operation does not cancel the scope so later operations still run`() {
        // Use the DEFAULT (production) scope, not an injected one - the injected scope would carry
        // the test's own SupervisorJob and mask the production one. setMain stands in for the
        // scope's Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val ops =
                SplitViewOperationsImpl(
                    splitViewState = SplitViewState(TabRegistry(), "split-ops-supervisor-test"),
                    windowId = "split-ops-supervisor-test",
                )

            // The child fails, but a handler on THIS launch captures the exception so it never
            // reaches kotlinx-coroutines-test's global handler and fails an unrelated later test in
            // the same JVM. A supervised scope stays active after a child fails; a plain Job would be
            // cancelled, so the isActive assertion is what keeps this test pinning the SupervisorJob.
            var caught: Throwable? = null
            ops.scope.launch(CoroutineExceptionHandler { _, e -> caught = e }) { error("boom") }
            assertEquals("boom", caught?.message, "the failing child's exception must be observed")
            assertTrue(ops.scope.isActive, "a failed operation must not cancel a supervised scope")

            var laterRan = false
            ops.scope.launch { laterRan = true }
            assertTrue(laterRan, "a later operation on the same scope must still run after one failed")

            ops.dispose()
            assertFalse(ops.scope.isActive, "dispose still cancels the scope")
        } finally {
            Dispatchers.resetMain()
        }
    }
}
