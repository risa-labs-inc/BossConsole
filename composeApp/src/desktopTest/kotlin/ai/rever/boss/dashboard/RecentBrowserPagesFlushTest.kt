package ai.rever.boss.dashboard

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class RecentBrowserPagesFlushTest {
    @Test
    fun `flush persists a new visit before the five second debounce`() =
        runBlocking {
            val manager = RecentBrowserPagesManager
            manager.flushPendingSaves()
            val original = manager.settingsFile
            val dir = Files.createTempDirectory("recent-pages-flush").toFile()
            val file = dir.resolve("pages.json")
            val url = "https://flush-test.example/${System.nanoTime()}"
            try {
                manager.settingsFile = file
                manager.recordPageVisit(url, "Flush regression")
                // Recording is asynchronous. Drive the public flush until that queued visit
                // has scheduled its save; the deadline is shorter than the normal debounce.
                withTimeout(2_000) {
                    while (!file.exists() || !file.readText().contains(url)) {
                        manager.flushPendingSaves()
                        yield()
                    }
                }
                assertTrue(file.readText().contains(url))
            } finally {
                // Await the writer without holding a Windows file handle open.
                manager.removePage(url).join()
                manager.flushPendingSaves()
                manager.settingsFile = original
                dir.deleteRecursively()
            }
        }
}
