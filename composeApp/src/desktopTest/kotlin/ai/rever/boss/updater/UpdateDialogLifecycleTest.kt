package ai.rever.boss.updater

import ai.rever.boss.components.overlays.HeavyweightModalRegistry
import ai.rever.boss.components.overlays.TrackHeavyweightModal
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateDialogLifecycleTest {
    @Test
    fun `real effects yield return and dispose without self oscillation or leaking registrations`() =
        runBlocking {
            assertEquals(0, HeavyweightModalRegistry.openCount)
            var promptMounts = 0
            var promptDisposals = 0
            withCompositions { owner, otherWindow, settle ->
                val otherOpen = mutableStateOf(false)
                val ownsPrompt = mutableStateOf(true)
                val requested = mutableStateOf(true)

                owner.setContent {
                    UpdateDialogGate(requested.value, ownsPrompt.value, updateAvailable = true) {
                        TrackHeavyweightModal()
                        DisposableEffect(Unit) {
                            promptMounts++
                            onDispose { promptDisposals++ }
                        }
                    }
                }
                otherWindow.setContent {
                    if (otherOpen.value) TrackHeavyweightModal()
                }
                settle()
                assertEquals(1, HeavyweightModalRegistry.openCount)
                assertEquals(1, promptMounts)
                assertEquals(0, promptDisposals)

                otherOpen.value = true
                settle()
                assertEquals(1, HeavyweightModalRegistry.openCount)
                assertEquals(1, promptMounts)
                assertEquals(1, promptDisposals)
                assertEquals(true, requested.value, "yielding must not dismiss the requested update")

                otherOpen.value = false
                settle()
                assertEquals(1, HeavyweightModalRegistry.openCount)
                assertEquals(2, promptMounts)
                assertEquals(1, promptDisposals)

                ownsPrompt.value = false
                settle()
                assertEquals(0, HeavyweightModalRegistry.openCount)
                assertEquals(2, promptDisposals)

                // Other-modal-first: ownership returns while another window still has a modal.
                otherOpen.value = true
                settle()
                ownsPrompt.value = true
                settle()
                assertEquals(2, promptMounts)
                assertEquals(1, HeavyweightModalRegistry.openCount)
                otherOpen.value = false
                settle()
                assertEquals(3, promptMounts)

                requested.value = false
                settle()
                assertEquals(0, HeavyweightModalRegistry.openCount)
                assertEquals(3, promptDisposals)
                requested.value = true
                settle()
                assertEquals(4, promptMounts)
            }
            assertEquals(0, HeavyweightModalRegistry.openCount)
            assertEquals(promptMounts, promptDisposals)
        }

    private suspend fun withCompositions(block: suspend (Composition, Composition, suspend () -> Unit) -> Unit) {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            val frames =
                launch {
                    while (isActive) {
                        clock.sendFrame(System.nanoTime())
                        delay(1)
                    }
                }
            val owner = Composition(NoNodes(), recomposer)
            val otherWindow = Composition(NoNodes(), recomposer)

            suspend fun settle() {
                withTimeout(5_000) {
                    // Several frames also catch a feedback loop that merely passed through
                    // the expected state before opening and closing again.
                    repeat(5) {
                        Snapshot.sendApplyNotifications()
                        delay(10)
                        recomposer.awaitIdle()
                    }
                }
            }
            try {
                block(owner, otherWindow, ::settle)
            } finally {
                owner.dispose()
                otherWindow.dispose()
                recomposer.cancel()
                runner.cancelAndJoin()
                frames.cancelAndJoin()
            }
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
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
}
