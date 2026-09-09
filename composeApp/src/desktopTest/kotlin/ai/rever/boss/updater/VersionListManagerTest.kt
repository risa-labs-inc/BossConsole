package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class VersionListManagerTest {
    @Test
    fun `realtime refresh during a fetch loads the newly published release`() =
        runBlocking {
            val firstFetchStarted = CompletableDeferred<Unit>()
            val finishFirstFetch = CompletableDeferred<Unit>()
            var calls = 0
            val manager =
                VersionListManager {
                    calls++
                    if (calls == 1) {
                        firstFetchStarted.complete(Unit)
                        finishFirstFetch.await()
                        listOf(release(1))
                    } else {
                        listOf(release(2), release(1))
                    }
                }
            val initial = launch { manager.fetchVersions() }
            firstFetchStarted.await()
            val realtime = launch(start = CoroutineStart.UNDISPATCHED) { manager.fetchVersions(forceRefresh = true) }
            finishFirstFetch.complete(Unit)
            initial.join()
            realtime.join()

            assertEquals(2, calls)
            assertEquals(
                Version(9, 5, 2),
                manager.versions.value
                    .first()
                    .version,
            )
        }

    @Test
    fun `realtime burst waiting on an older fetch shares one follow up`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val manager =
                VersionListManager {
                    calls++
                    if (calls == 1) {
                        started.complete(Unit)
                        finish.await()
                    }
                    listOf(release(calls))
                }
            val initial = launch { manager.fetchVersions() }
            started.await()
            val refreshes =
                List(10) {
                    launch(start = CoroutineStart.UNDISPATCHED) { manager.fetchVersions(forceRefresh = true) }
                }
            finish.complete(Unit)
            initial.join()
            refreshes.forEach { it.join() }
            assertEquals(2, calls)
            assertEquals(listOf(release(2)), manager.versions.value)
        }

    @Test
    fun `dashboard and settings share the result of an in flight fetch`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val manager =
                VersionListManager {
                    calls++
                    started.complete(Unit)
                    finish.await()
                    listOf(release(1))
                }
            val dashboard = launch { manager.fetchVersions() }
            started.await()
            val settings = launch(start = CoroutineStart.UNDISPATCHED) { manager.fetchVersions() }
            finish.complete(Unit)
            dashboard.join()
            settings.join()

            assertEquals(1, calls)
            assertEquals(listOf(release(1)), manager.versions.value)
        }

    @Test
    fun `closing a fetching surface does not poison shared error state and another can retry`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var calls = 0
            val manager =
                VersionListManager {
                    calls++
                    if (calls == 1) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    listOf(release(1))
                }
            val dashboard = launch { manager.fetchVersions() }
            started.await()
            dashboard.cancelAndJoin()

            assertFalse(manager.isLoading.value)
            assertNull(manager.error.value)
            manager.fetchVersions()
            assertEquals(2, calls)
            assertEquals(listOf(release(1)), manager.versions.value)
        }

    private fun release(patch: Int) =
        VersionInfo(
            version = Version(9, 5, patch),
            releaseDate = "2026-09-08",
            downloadSize = 0,
            releaseNotes = "Release $patch",
            downloadUrl = "",
            isDraft = false,
            isPrerelease = false,
        )
}
