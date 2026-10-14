package ai.rever.boss.dashboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentPagesDismissalDuringLoadTest {
    @Test fun `clear wins over a paused recent pages read and preserves a later visit`() = verifyLoad(false, true)

    @Test fun `remove retains unrelated history during paused read`() = verifyLoad(false, false)

    @Test fun `clear wins over a paused browser history bootstrap`() = verifyLoad(true, true)

    @Test fun `remove wins over a paused browser history bootstrap`() = verifyLoad(true, false)

    private fun verifyLoad(
        bootstrap: Boolean,
        clear: Boolean,
    ) = runBlocking {
        val manager = RecentBrowserPagesManager
        manager.initialLoad.join()
        manager.flushPendingSaves()
        val original = manager.settingsFile
        val originalPages = manager.recentPages.value
        val originalDismissals = manager.dismissedSuggestions.value
        val dir = Files.createTempDirectory("pages-load-dismissal").toFile()
        val file = dir.resolve("pages.json")
        val history = dir.resolve("history.json")
        val removed = "https://removed.example/"
        val retained = "https://retained.example/"
        val later = "https://later.example/"
        val entries =
            """[{"url":"$removed","title":"Removed","lastVisited":1},""" +
                """{"url":"$retained","title":"Retained","lastVisited":2}]"""
        try {
            manager.settingsFile = file
            manager.clearAll().join()
            if (bootstrap) {
                file.delete()
                history.writeText(entries)
            } else {
                file.writeText("""{"pages":$entries}""")
            }
            val readStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loading =
                launch {
                    manager.loadAsync(read = { source ->
                        val captured = source.readText()
                        readStarted.complete(Unit)
                        release.await()
                        captured
                    }, historyFile = history)
                }
            try {
                withTimeout(2_000) { readStarted.await() }
                if (clear) manager.clearAll().join() else manager.removePage(removed).join()
                manager.recordPageVisit(later, "After dismissal")
                withTimeout(2_000) {
                    while (manager.recentPages.value.none { it.url == later }) yield()
                }
            } finally {
                release.complete(Unit)
                loading.join()
            }
            verifyOutcome(clear, file, removed, retained, later)
        } finally {
            manager.clearAll().join()
            manager.flushPendingSaves()
            restoreState("_recentPages", originalPages)
            restoreState("_dismissedSuggestions", originalDismissals)
            manager.settingsFile = original
            dir.deleteRecursively()
        }
    }

    private suspend fun verifyOutcome(
        clear: Boolean,
        file: java.io.File,
        removed: String,
        retained: String,
        later: String,
    ) {
        val manager = RecentBrowserPagesManager
        assertFalse(manager.recentPages.value.any { it.url == removed })
        assertTrue(manager.recentPages.value.any { it.url == later })
        assertEquals(!clear, manager.recentPages.value.any { it.url == retained })
        manager.flushPendingSaves()
        assertFalse(file.readText().contains(removed))
        assertTrue(file.readText().contains(later))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> restoreState(
        name: String,
        value: T,
    ) {
        val field = RecentBrowserPagesManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field.get(RecentBrowserPagesManager) as MutableStateFlow<T>).value = value
    }
}
