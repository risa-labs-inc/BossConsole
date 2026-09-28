package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogListener
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Corrupt-file recovery and write ordering for browser-zoom-settings.json (BossConsole#925).
 * #937 made the writes atomic and owner-only; a file that is already corrupt - a torn write
 * from before it, or a hand-edit - was still re-read and re-failed on every launch, and the next
 * save overwrote it with no copy kept.
 * It is now moved aside and replaced with a fresh default. Separately, concurrent zoom changes
 * could each read the same starting map and drop one another's update; they now share a lock.
 *
 * Hermetic through [BrowserZoomSettingsManager.resetForTesting], so nothing here touches the operator's ~/.boss.
 */
class BrowserZoomSettingsRecoveryTest {
    private lateinit var tempDir: File
    private lateinit var settingsFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("zoom-corrupt-test-").toFile()
        settingsFile = File(tempDir, "browser-zoom-settings.json")
    }

    @AfterTest
    fun tearDown() {
        BrowserZoomSettingsManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `a corrupt zoom settings file is moved aside and replaced with a fresh default`() {
        assertSelfHeals("{ not valid json at all")
    }

    // The classic artifact of a crash mid-write under the old truncate-on-open writeText.
    @Test
    fun `an empty zoom settings file is moved aside and replaced with a fresh default`() {
        assertSelfHeals("")
    }

    // Valid JSON of the wrong shape, as a hand-edit can leave behind.
    @Test
    fun `a zoom settings file of the wrong shape is set aside and replaced with a fresh default`() {
        assertSelfHeals("[]")
    }

    // Only a decode failure may rename a file away. A read error (here: the path is a directory, so
    // readText throws an IOException) says nothing about the bytes, so nothing is moved.
    @Test
    fun `a read failure that is not a decode failure leaves the file in place`() {
        assertTrue(settingsFile.mkdirs())

        BrowserZoomSettingsManager.resetForTesting(settingsFile)

        assertTrue(settingsFile.isDirectory, "the original path must be untouched")
        val corrupt = tempDir.listFiles { f -> f.name.startsWith("${settingsFile.name}.corrupt-") }.orEmpty()
        assertEquals(0, corrupt.size, "no aside may be created for a read error")
    }

    // #1695: the corrupt-file log line used to carry the decoder's exception, whose message quotes
    // the file - and this file is keyed by the domains a user zoomed.
    @Test
    fun `the corrupt-file log line names no domain from the file`() {
        val domain = "private-intranet.example"
        val entries = mutableListOf<LogEntry>()
        val listener = LogListener { entry -> synchronized(entries) { entries += entry } }
        settingsFile.writeText(
            """{"domainSettings":{"$domain":{"domain":"$domain","zoomLevel":"big"}}}""",
        )

        BossLogger.addListener(listener)
        try {
            BrowserZoomSettingsManager.resetForTesting(settingsFile)
        } finally {
            BossLogger.removeListener(listener)
        }

        val logged = synchronized(entries) { entries.toList() }
        val corrupt = logged.single { it.message.startsWith("Zoom settings file is corrupt") }
        assertNull(corrupt.error, "the decoder's exception quotes the file, so it must not be attached")
        assertEquals("$.domainSettings[*].zoomLevel", corrupt.data?.get("path"))
        for (entry in logged) {
            assertFalse(domain in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
        }
    }

    private fun assertSelfHeals(fixture: String) {
        settingsFile.writeText(fixture)

        BrowserZoomSettingsManager.resetForTesting(settingsFile)

        assertEquals(emptyMap(), BrowserZoomSettingsManager.getAllDomainSettings())
        assertTrue(settingsFile.isFile, "a fresh file must be written back")
        assertTrue(settingsFile.readText() != fixture, "the corrupt content must not still be at the original path")
        val corrupt = tempDir.listFiles { f -> f.name.startsWith("${settingsFile.name}.corrupt-") }.orEmpty()
        assertEquals(1, corrupt.size, "the corrupt bytes must be kept, renamed aside")
        assertEquals(fixture, corrupt.single().readText())
    }

    @Test
    fun `concurrent zoom changes on different domains do not lose updates`() {
        BrowserZoomSettingsManager.resetForTesting(settingsFile)
        // 1.0 is excluded: setZoomForDomain treats it as "reset to default" and stores nothing.
        val domainCount = 50
        // One thread per task, so every one can be parked on `go` at once and the contention is real.
        val pool = Executors.newFixedThreadPool(domainCount)
        val ready = CountDownLatch(domainCount)
        val go = CountDownLatch(1)
        try {
            repeat(domainCount) { i ->
                pool.submit {
                    ready.countDown()
                    go.await()
                    BrowserZoomSettingsManager.setZoomForDomain("site-$i.example", 1.5 + i * 0.01)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "not every thread reached the starting line")
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish in time")
        } finally {
            pool.shutdownNow()
        }

        val stored = BrowserZoomSettingsManager.getAllDomainSettings()
        assertEquals(domainCount, stored.size, "a concurrent update was lost")
    }

    // What this pins is the atomic whole-file save from either entry point while the map changes
    // under it; the lock itself is pinned by the lost-update test above.
    @Test
    fun `saves from both entry points racing zoom changes always leave a valid file`() {
        BrowserZoomSettingsManager.resetForTesting(settingsFile)
        BrowserZoomSettingsManager.setZoomForDomain("stable.example", 2.0)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (1..60).map {
                    pool.submit {
                        when (it % 3) {
                            0 -> BrowserZoomSettingsManager.saveSettingsSync()
                            1 -> runBlocking { BrowserZoomSettingsManager.saveSettings() }
                            else -> BrowserZoomSettingsManager.setZoomForDomain("site-$it.example", 1.5)
                        }
                    }
                }
            for (future in futures) future.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val inMemory = BrowserZoomSettingsManager.getAllDomainSettings().keys

        // No final save: whatever the last racing writer left must decode on its own.
        BrowserZoomSettingsManager.resetForTesting(settingsFile)
        val onDisk = BrowserZoomSettingsManager.getAllDomainSettings().keys
        assertTrue("stable.example" in onDisk, "the file on disk did not decode: $onDisk")
        assertTrue(inMemory.containsAll(onDisk), "the file holds domains that were never set: ${onDisk - inMemory}")
    }
}
