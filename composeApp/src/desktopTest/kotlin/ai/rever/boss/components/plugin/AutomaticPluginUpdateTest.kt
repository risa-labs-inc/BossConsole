package ai.rever.boss.components.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutomaticPluginUpdateTest {
    private fun plan(
        views: Int? = 0,
        native: Boolean = false,
        dependents: Boolean = false,
        windows: Boolean = false,
        disabled: Boolean = false,
    ) = automaticPluginUpdatePlan(views, native, dependents, windows, disabled)

    @Test
    fun `unknown or open views prevent automatic activation`() {
        assertEquals(AutomaticPluginUpdatePlan.WAIT, plan(views = null))
        assertEquals(AutomaticPluginUpdatePlan.WAIT, plan(views = 1))
        assertEquals(AutomaticPluginUpdatePlan.WAIT, plan(views = 2, native = true))
        assertEquals(AutomaticPluginUpdatePlan.RELOAD, plan())
    }

    @Test
    fun `native dependencies multiwindow and disabled plugins stage without live replacement`() {
        assertEquals(AutomaticPluginUpdatePlan.STAGE, plan(native = true))
        assertEquals(AutomaticPluginUpdatePlan.STAGE, plan(dependents = true))
        assertEquals(AutomaticPluginUpdatePlan.STAGE, plan(windows = true))
        assertEquals(AutomaticPluginUpdatePlan.STAGE, plan(disabled = true))
    }

    @Test
    fun `successful activation never restores the old version`(): Unit =
        runBlocking {
            var restored = false
            val result =
                applyAutomaticPluginUpdateWithRollback(
                    install = { Result.success(Unit) },
                    restore = {
                        restored = true
                        Result.success(Unit)
                    },
                )
            assertTrue(result.isSuccess)
            assertFalse(restored)
        }

    @Test
    fun `failed activation restores the old version and preserves the failure`(): Unit =
        runBlocking {
            var restored = false
            val failure = IllegalStateException("New plugin cannot load")
            val result =
                applyAutomaticPluginUpdateWithRollback(
                    install = { Result.failure(failure) },
                    restore = {
                        restored = true
                        Result.success(Unit)
                    },
                )
            assertTrue(restored)
            assertSame(failure, result.exceptionOrNull())
        }

    @Test
    fun `thrown activation failure also restores the old version`(): Unit =
        runBlocking {
            var restored = false
            val failure = IllegalStateException("Registration failed")
            val result =
                applyAutomaticPluginUpdateWithRollback(
                    install = { throw failure },
                    restore = {
                        restored = true
                        Result.success(Unit)
                    },
                )
            assertTrue(restored)
            assertSame(failure, result.exceptionOrNull())
        }

    @Test
    fun `restore failure retains both errors`(): Unit =
        runBlocking {
            val failure = IllegalStateException("New version failed")
            val restoreFailure = IllegalStateException("Old version failed")
            val result =
                applyAutomaticPluginUpdateWithRollback(
                    install = { Result.failure(failure) },
                    restore = { Result.failure(restoreFailure) },
                )
            assertSame(failure, result.exceptionOrNull())
            assertTrue(failure.suppressed.contains(restoreFailure))
        }

    @Test
    fun `cancellation is propagated`(): Unit =
        runBlocking {
            var restored = false
            var cancelled = false
            try {
                applyAutomaticPluginUpdateWithRollback(
                    install = { throw CancellationException("Cancelled") },
                    restore = {
                        restored = true
                        Result.success(Unit)
                    },
                )
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertFalse(restored)
        }
}
