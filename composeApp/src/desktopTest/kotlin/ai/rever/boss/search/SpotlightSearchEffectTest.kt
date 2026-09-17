package ai.rever.boss.search

import ai.rever.boss.components.dialogs.SpotlightDialogState
import ai.rever.boss.components.dialogs.SpotlightSearchEffect
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

class SpotlightSearchEffectTest {
    @Test
    fun `index completion refreshes unchanged query and replacement session starts its own search`() =
        runBlocking {
            SearchSources.clearForTests()
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
                val first = SpotlightDialogState().apply { query = "needle" }
                val session = mutableStateOf(first)
                val files = mutableStateOf<List<IndexedFile>>(emptyList())
                try {
                    composition.setContent { SpotlightSearchEffect(session.value, "window", files.value) }
                    // Let the first search finish against the cold, empty snapshot.
                    delay(150)
                    assertEquals(emptyList(), first.results.filterIsInstance<SearchResult.FileResult>())
                    files.value = listOf(IndexedFile("needle.kt", "/a/needle.kt", "needle.kt"))
                    Snapshot.sendApplyNotifications()
                    withTimeout(5_000) {
                        while (first.results.filterIsInstance<SearchResult.FileResult>().isEmpty()) delay(10)
                    }
                    assertEquals(
                        "/a/needle.kt",
                        first.results
                            .filterIsInstance<SearchResult.FileResult>()
                            .single()
                            .path,
                    )

                    // The query and file snapshot are identical; session identity must still restart the effect.
                    val second = SpotlightDialogState().apply { query = "needle" }
                    session.value = second
                    Snapshot.sendApplyNotifications()
                    withTimeout(5_000) {
                        while (second.results.filterIsInstance<SearchResult.FileResult>().isEmpty()) delay(10)
                    }
                    assertEquals(first.results, second.results)
                    assertEquals("needle", first.query)

                    assertRefreshedQuery(files, second)
                } finally {
                    composition.dispose()
                    recomposer.cancel()
                    runner.cancelAndJoin()
                    frames.cancelAndJoin()
                    SearchSources.clearForTests()
                }
            }
        }

    private suspend fun assertRefreshedQuery(
        files: androidx.compose.runtime.MutableState<List<IndexedFile>>,
        second: SpotlightDialogState,
    ) {
        // A completed refresh renames a matching file without editing the query.
        files.value = listOf(IndexedFile("needle-renamed.kt", "/a/needle-renamed.kt", "needle-renamed.kt"))
        Snapshot.sendApplyNotifications()
        withTimeout(5_000) {
            while (second.results
                    .filterIsInstance<SearchResult.FileResult>()
                    .singleOrNull()
                    ?.path !=
                "/a/needle-renamed.kt"
            ) {
                delay(10)
            }
        }
        files.value = emptyList()
        Snapshot.sendApplyNotifications()
        withTimeout(5_000) {
            while (second.results.filterIsInstance<SearchResult.FileResult>().isNotEmpty()) delay(10)
        }
        assertEquals("needle", second.query)
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
