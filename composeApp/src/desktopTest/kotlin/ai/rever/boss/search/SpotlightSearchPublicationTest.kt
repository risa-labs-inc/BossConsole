package ai.rever.boss.search

import ai.rever.boss.components.dialogs.SpotlightDialogState
import ai.rever.boss.components.dialogs.runSpotlightSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotlightSearchPublicationTest {
    private val result = SearchResult.FileResult("needle", "/needle", "needle", 1, emptyList())

    @Test
    fun `clear invalidates an in flight result and clears busy state`() =
        runTest {
            val state = SpotlightDialogState()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old =
                launch {
                    runSpotlightSearch(state, "needle") {
                        started.complete(Unit)
                        release.await()
                        listOf(result)
                    }
                }
            started.await()
            assertTrue(state.isSearching)
            runSpotlightSearch(state, " ") { error("blank query must not search") }
            assertFalse(state.isSearching)
            release.complete(Unit)
            old.join()
            assertEquals(emptyList(), state.results)
            assertFalse(state.isSearching)
        }

    @Test
    fun `obsolete completion cannot clear replacement busy state or publish results`() =
        runTest {
            val state = SpotlightDialogState()
            val oldStarted = CompletableDeferred<Unit>()
            val oldRelease = CompletableDeferred<Unit>()
            val newStarted = CompletableDeferred<Unit>()
            val newRelease = CompletableDeferred<Unit>()
            val old =
                launch {
                    runSpotlightSearch(state, "old") {
                        oldStarted.complete(Unit)
                        oldRelease.await()
                        listOf(result)
                    }
                }
            oldStarted.await()
            val replacement =
                launch {
                    runSpotlightSearch(state, "new") {
                        newStarted.complete(Unit)
                        newRelease.await()
                        emptyList()
                    }
                }
            newStarted.await()
            oldRelease.complete(Unit)
            old.join()
            assertTrue(state.isSearching)
            assertEquals(emptyList(), state.results)
            newRelease.complete(Unit)
            replacement.join()
            assertFalse(state.isSearching)
        }

    @Test
    fun `cancelled effect cannot publish even when its source swallows cancellation`() =
        runTest {
            val state = SpotlightDialogState()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val search =
                launch {
                    runSpotlightSearch(state, "needle") {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                        }
                        listOf(result)
                    }
                }
            started.await()
            search.cancel()
            release.complete(Unit)
            search.join()
            assertEquals(emptyList(), state.results)
            assertFalse(state.isSearching)
        }
}
