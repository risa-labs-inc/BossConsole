package ai.rever.boss.dashboard

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogListener
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [RecentBrowserPagesManager.loadAsync] end to end against a hermetic temp file, the same
 * role [RecentFilesLoadTest] plays for its sibling: [RecentBrowserPagesMergeTest] keeps the merge
 * rule pure, and this one pins what the load does around it.
 *
 * There is no `resetForTesting` here - the recent-page tests drive the public surface and swap
 * [RecentBrowserPagesManager.settingsFile], as [RecentBrowserPagesFlushTest] does - so assertions
 * are containment-based: the shared singleton can hold entries an earlier test in this JVM
 * recorded, and only the entries a test itself contributes are ordered.
 */
class RecentBrowserPagesLoadTest {
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    /** Unique per run, so decoded pages can never collide with an earlier test's leftovers. */
    private val seed = System.nanoTime()

    /** Freshly stamped, so the cap-30 merge cannot crowd them out of any ambient state. */
    private val persistedPages =
        listOf(
            RecentBrowserPage(
                url = "https://load-$seed.example/a",
                title = "Load test a",
                lastVisited = System.currentTimeMillis() - 100,
            ),
            RecentBrowserPage(
                url = "https://load-$seed.example/b",
                title = "Load test b",
                lastVisited = System.currentTimeMillis() - 200,
            ),
        )

    @Test
    fun `an ordinary launch delivers the decoded pages to the flow`() =
        runBlocking {
            val manager = RecentBrowserPagesManager
            manager.flushPendingSaves()
            val original = manager.settingsFile
            val dir = Files.createTempDirectory("recent-pages-load").toFile()
            val file = dir.resolve("pages.json")
            try {
                manager.settingsFile = file
                file.writeText(
                    json.encodeToString(RecentBrowserPagesData(pages = persistedPages)),
                )

                manager.loadAsync()

                val urls = manager.recentPages.value.map { it.url }
                assertTrue(
                    urls.containsAll(persistedPages.map { it.url }),
                    "the decoded pages must reach the flow",
                )
            } finally {
                for (page in persistedPages) manager.removePage(page.url).join()
                manager.flushPendingSaves()
                manager.settingsFile = original
                dir.deleteRecursively()
            }
        }

    // #1629: kotlinx appends the file it failed on to the exception, and this file is visited URLs
    // with their query strings.
    @Test
    fun `a corrupt recent-pages file is logged without its URLs`() =
        runBlocking {
            val manager = RecentBrowserPagesManager
            manager.flushPendingSaves()
            val original = manager.settingsFile
            val dir = Files.createTempDirectory("recent-pages-load-log").toFile()
            val file = dir.resolve("pages.json")
            val secret = "token=recent-$seed"
            val entries = mutableListOf<LogEntry>()
            val listener = LogListener { entry -> synchronized(entries) { entries += entry } }
            try {
                manager.settingsFile = file
                // Torn mid-object, the shape an interrupted write leaves.
                file.writeText("""{"pages":[{"url":"https://leak-$seed.example/?$secret","title":"t"""")
                BossLogger.addListener(listener)

                manager.loadAsync(historyFile = dir.resolve("no-history.json"))

                val logged = synchronized(entries) { entries.toList() }
                val failure = logged.single { it.message == "Error loading recent pages" }
                assertNull(failure.error, "the decoder's exception carries the file, so it must not be attached")
                assertEquals("JsonDecodingException", failure.data?.get("decodeFailure"))
                for (entry in logged) {
                    assertFalse(secret in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
                }
            } finally {
                BossLogger.removeListener(listener)
                manager.flushPendingSaves()
                manager.settingsFile = original
                dir.deleteRecursively()
            }
        }

    @Test
    fun `a load failure leaves pages recorded while it ran in place`() =
        runBlocking {
            val manager = RecentBrowserPagesManager
            manager.flushPendingSaves()
            val original = manager.settingsFile
            val dir = Files.createTempDirectory("recent-pages-load-failure").toFile()
            val file = dir.resolve("pages.json")
            val url = "https://load-failure-$seed.example/page"
            try {
                manager.settingsFile = file
                file.writeText("{ not the json the decoder expects")
                // The visit that lands while the doomed load is in flight. Recording is
                // asynchronous, so drive the public flow until it has been applied.
                manager.recordPageVisit(url, "Load failure regression")
                withTimeout(2_000) {
                    while (manager.recentPages.value.none { it.url == url }) yield()
                }

                manager.loadAsync()

                assertTrue(
                    manager.recentPages.value.any { it.url == url },
                    "the failed load must not discard the visit that raced it",
                )
            } finally {
                // The flush test's discipline: await the removal before restoring the path, so
                // no save scheduled during the test lands on the real file afterwards.
                manager.removePage(url).join()
                manager.flushPendingSaves()
                manager.settingsFile = original
                dir.deleteRecursively()
            }
        }
}
