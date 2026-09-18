package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for issue #925's `BrowserZoomSettingsManager` findings: a lost-update race
 * between concurrent [BrowserZoomSettingsManager.setZoomForDomain] calls, unsynchronized writes
 * from [BrowserZoomSettingsManager.saveSettings]/[BrowserZoomSettingsManager.saveSettingsSync]
 * tearing the file, and a corrupt file being silently re-read (and re-failed) forever instead of
 * self-healing.
 *
 * `BrowserZoomSettingsManager` is a singleton that loads once at class-init time, so these tests
 * drive it through [BrowserZoomSettingsManager.reloadForTesting] rather than relying on init
 * timing - the same seam [ai.rever.boss.services.auth.UserDataStorage] exposes for the same
 * reason.
 */
class BrowserZoomSettingsManagerTest {
    @AfterTest
    fun cleanUp() {
        BrowserZoomSettingsManager.clearAllSettings()
        BrowserZoomSettingsManager.saveSettingsSync()
        BrowserZoomSettingsManager.settingsFile.parentFile
            ?.listFiles { f -> f.name.startsWith("${BrowserZoomSettingsManager.settingsFile.name}.corrupt-") }
            ?.forEach { it.delete() }
    }

    @Test
    fun `concurrent zoom changes on different domains do not lose updates`() {
        BrowserZoomSettingsManager.clearAllSettings()

        // 1.0 is deliberately excluded from the generated levels: setZoomForDomain treats it as
        // "reset to default" and does not store it, which is correct pre-existing behaviour, not
        // a lost update - starting the range at 1.5 keeps every one of these domains storable.
        val domainCount = 50
        // One thread per task so every one of them can be genuinely parked on `go` at once,
        // rather than a smaller pool draining tasks in waves and diluting the contention this
        // test exists to create.
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
        assertEquals(domainCount, stored.size, "at least one concurrent update was lost")
        repeat(domainCount) { i ->
            assertEquals(1.5 + i * 0.01, BrowserZoomSettingsManager.getZoomForDomain("site-$i.example"))
        }
    }

    @Test
    fun `saveSettings and saveSettingsSync do not tear the file when they race`() {
        BrowserZoomSettingsManager.clearAllSettings()
        BrowserZoomSettingsManager.setZoomForDomain("stable.example", 2.0)

        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (1..40).map {
                    pool.submit {
                        if (it % 2 == 0) {
                            BrowserZoomSettingsManager.saveSettingsSync()
                        } else {
                            runBlocking { BrowserZoomSettingsManager.saveSettings() }
                        }
                    }
                }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // A torn write would leave invalid JSON on disk; reloading must still see the value
        // both save paths were writing throughout, not an empty or half-written file.
        BrowserZoomSettingsManager.reloadForTesting()
        assertEquals(2.0, BrowserZoomSettingsManager.getZoomForDomain("stable.example"))
    }

    @Test
    fun `a corrupt settings file is moved aside and replaced with fresh defaults on reload`() {
        BrowserZoomSettingsManager.clearAllSettings()
        BrowserZoomSettingsManager.settingsFile.parentFile?.mkdirs()
        BrowserZoomSettingsManager.settingsFile.writeText("{ this is not valid json")

        BrowserZoomSettingsManager.reloadForTesting()

        assertEquals(emptyMap(), BrowserZoomSettingsManager.getAllDomainSettings())

        // The original path now holds a fresh, valid file - not the corrupt bytes, and not
        // missing entirely (which would mean the same corrupt content gets re-read and re-failed
        // on every future launch).
        assertTrue(BrowserZoomSettingsManager.settingsFile.exists(), "a fresh file must be written back")
        assertTrue(
            !BrowserZoomSettingsManager.settingsFile.readText().contains("this is not valid"),
            "the corrupt content must not still be at the original path",
        )

        // The corrupt bytes themselves survive, renamed aside for inspection.
        val corruptSiblings =
            BrowserZoomSettingsManager.settingsFile.parentFile
                ?.listFiles { f -> f.name.startsWith("${BrowserZoomSettingsManager.settingsFile.name}.corrupt-") }
                .orEmpty()
        assertEquals(1, corruptSiblings.size)
        assertEquals("{ this is not valid json", corruptSiblings.single().readText())
    }
}
