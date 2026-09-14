@file:Suppress("PackageNaming")

package ai.rever.boss.components.window_panel

import ai.rever.boss.components.buttons.ReportContextMenuVisibility
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
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

class ContextMenuVisibilityLifecycleTest {
    @Test
    fun `open close reopen and removal each release exactly one report`() =
        runBlocking {
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
                val composition = Composition(NoNodes(), recomposer)
                val open = mutableStateOf(false)
                val present = mutableStateOf(true)
                val reports = mutableListOf<Boolean>()
                try {
                    composition.setContent {
                        if (present.value) ReportContextMenuVisibility(open.value) { reports += it }
                    }
                    assertEquals(emptyList(), reports)
                    open.value = true
                    Snapshot.sendApplyNotifications()
                    withTimeout(5_000) { while (reports.size < 1) delay(10) }
                    assertEquals(listOf(true), reports)
                    open.value = false
                    Snapshot.sendApplyNotifications()
                    withTimeout(5_000) { while (reports.size < 2) delay(10) }
                    assertEquals(listOf(true, false), reports)
                    open.value = true
                    Snapshot.sendApplyNotifications()
                    withTimeout(5_000) { while (reports.size < 3) delay(10) }
                    present.value = false
                    Snapshot.sendApplyNotifications()
                    withTimeout(5_000) { while (reports.size < 4) delay(10) }
                    assertEquals(listOf(true, false, true, false), reports)
                } finally {
                    composition.dispose()
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
