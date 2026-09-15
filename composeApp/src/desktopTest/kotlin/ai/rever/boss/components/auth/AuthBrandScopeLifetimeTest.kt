package ai.rever.boss.components.auth

import ai.rever.boss.components.auth.forms.authBrandViewScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthBrandScopeLifetimeTest {
    @Test
    fun `view failure preserves siblings and the page loaded callback`() =
        runBlocking {
            withTimeout(10_000) {
                val parent = Job()
                val composition = CoroutineScope(parent + Dispatchers.Main)
                val view = authBrandViewScope(composition)
                try {
                    val sibling = view.launch { awaitCancellation() }
                    view.launch { error("Expected view failure") }.join()
                    assertTrue(parent.isActive)
                    assertTrue(view.isActive)
                    assertTrue(sibling.isActive)
                    val loadedOnEdt = CompletableDeferred<Boolean>()
                    composition.launch { loadedOnEdt.complete(EventQueue.isDispatchThread()) }
                    assertTrue(loadedOnEdt.await())
                } finally {
                    view.cancel()
                    parent.cancelAndJoin()
                }
            }
        }

    @Test
    fun `composition cancellation stops running view work`() =
        runBlocking {
            withTimeout(10_000) {
                val parent = Job()
                val view = authBrandViewScope(CoroutineScope(parent + Dispatchers.Main))
                try {
                    val started = CompletableDeferred<Unit>()
                    val observer =
                        view.launch {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                    started.await()
                    parent.cancelAndJoin()
                    assertFalse(view.isActive)
                    assertTrue(observer.isCancelled)
                } finally {
                    view.cancel()
                    parent.cancelAndJoin()
                }
            }
        }
}
