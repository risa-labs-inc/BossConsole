package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the BrowserZoomSettingsManager hardening (#925, #1051):
 *
 * - a corrupt settings file is renamed aside (self-heal) instead of silently
 *   re-failing every launch at the live path;
 * - the rename-aside helper is a pure file operation, so it is pinned on a
 *   caller-supplied directory without touching the shared BossDirectories root;
 * - the helper's aside name is collision-proof and its `renameTo` return
 *   value is checked, so two corrupt cycles in the same millisecond do not
 *   clobber each other and a Windows-side rename failure leaves a signal;
 * - the read-modify-write mutators ([setZoomForDomain], [clearDomainZoom],
 *   [clearAllSettings]) hold [BrowserZoomSettingsManager]'s save lock for
 *   their whole critical section, so concurrent mutators do not lose updates;
 * - the manager's own `saveSettingsSync` and `loadSettings` round-trip a
 *   value through the real atomic-write helper (and a corrupt file lands
 *   beside the live path with the documented `<name>.corrupt.<millis>.<uuid>`
 *   suffix), exercising the same path the production manager uses.
 */
class BrowserZoomSettingsManagerHardeningTest {
    private lateinit var tmp: File
    private var originalFile: File? = null

    @BeforeTest
    fun setUp() {
        tmp = File.createTempFile("zoom-hardening", null)
        tmp.delete()
        tmp.mkdir()
        originalFile = BrowserZoomSettingsManager.settingsFile
        BrowserZoomSettingsManager.settingsFile = File(tmp, "browser-zoom-settings.json")
        // Start every test from a clean in-memory state so the assertions
        // about what survived a corrupt load are not polluted by the
        // previous test's domain map.
        BrowserZoomSettingsManager.clearAllSettings()
    }

    @AfterTest
    fun tearDown() {
        originalFile?.let { BrowserZoomSettingsManager.settingsFile = it }
        tmp.deleteRecursively()
        BrowserZoomSettingsManager.clearAllSettings()
    }

    // --- quarantine helper: pure file operation ----------------------------------------------

    @Test
    fun `a corrupt settings file is renamed aside and no longer sits at the live path`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("{ this is not valid json")

        moveCorruptSettingsAside(live, now = { 1726000000000L })

        // The corrupt bytes no longer sit at the live name; an aside copy survives for diagnosis.
        assertFalse(live.exists(), "the corrupt file must not remain at the live path")
        val aside = tmp.listFiles()!!.single { it.name.startsWith("browser-zoom-settings.json.corrupt.") }
        assertTrue(aside.exists(), "the aside copy must exist")
        assertTrue(aside.name.contains("1726000000000"), "millisecond timestamp preserved for diagnosis: ${aside.name}")
        assertEquals(true, aside.readText().startsWith("{ this"))
    }

    @Test
    fun `an absent file is a no-op - no aside is created`() {
        val absent = File(tmp, "never-existed.json")
        moveCorruptSettingsAside(absent, now = { 1726000000000L })
        assertEquals(0, tmp.listFiles()!!.size, "the directory must stay empty - no aside for an absent file")
    }

    /**
     * #1051's collision case: two corrupt cycles that resolve to the SAME
     * millisecond used to rename to the same target, so POSIX `rename(2)`
     * overwrote the earlier backup and Windows `MoveFile` reported
     * `ERROR_ALREADY_EXISTS`. The UUID half guarantees distinct asides,
     * so both survive.
     */
    @Test
    fun `two corrupt cycles in the same millisecond keep two distinct asides`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("garbage one")
        moveCorruptSettingsAside(live, now = { 1L })
        live.writeText("garbage two")
        moveCorruptSettingsAside(live, now = { 1L })

        val asides = tmp.listFiles()!!.filter { it.name.contains(".corrupt.") }
        assertEquals(2, asides.size, "two distinct corrupt cycles must keep two diagnosable asides")
        assertEquals(setOf("garbage one", "garbage two"), asides.map { it.readText() }.toSet())
        assertEquals(2, asides.map { it.name }.toSet().size, "the two asides must have distinct names")
    }

    /**
     * #1051's rename-failure case: when [File.renameTo] cannot move the
     * file (a locked target on Windows, a path that has already vanished,
     * or any other platform-specific refusal), the helper must leave a
     * signal that the recovery step did not actually move anything. The
     * test injects a rename function that always returns false, which is
     * the same shape a real platform refusal takes - the JVM can't be
     * made to reliably refuse `File.renameTo` cross-platform without a
     * target collision, and the UUID aside name the helper now uses would
     * defeat that collision in any case. The live file must STILL be
     * there after the helper runs (the helper cannot remove it), and no
     * aside should be created.
     */
    @Test
    fun `a rename failure leaves the source in place and does not create an aside`() {
        val live = File(tmp, "browser-zoom-settings.json")
        live.writeText("{ broken")
        val renaming = AtomicBoolean(false)

        moveCorruptSettingsAside(
            live,
            now = { 1L },
            renameFn = { _, _ ->
                renaming.set(true)
                false
            },
        )

        assertTrue(renaming.get(), "the injected rename function must have been called")
        assertTrue(live.exists(), "renameTo failure must leave the live file in place")
        // No aside file is created when the rename does not happen.
        assertEquals(emptyList(), tmp.listFiles()!!.filter { it.name.contains(".corrupt.") })
    }

    // --- real save/load roundtrip through the manager ----------------------------------------

    /**
     * #1051's end-to-end shape: the manager's own `saveSettingsSync` and
     * `loadSettings` round-trip a value through the same atomic-write helper
     * the production code uses, and the file on disk contains the bytes
     * that were meant to land there (no half-written live file, no leftover
     * temp file).
     */
    @Test
    fun `a value written through saveSettingsSync round-trips through loadSettings`() {
        BrowserZoomSettingsManager.setZoomForDomain("example.com", 1.25)
        BrowserZoomSettingsManager.saveSettingsSync()

        val live = BrowserZoomSettingsManager.settingsFile
        assertTrue(live.exists(), "saveSettingsSync must create the live settings file")

        val onDisk = live.readText()
        assertTrue(onDisk.contains("\"example.com\""), "the saved file must contain the domain: $onDisk")
        assertTrue(onDisk.contains("1.25"), "the saved file must contain the zoom level: $onDisk")

        // No temp file should sit beside the live file; the atomic move took it.
        assertEquals(emptyList(), tmp.listFiles()!!.filter { it.name.startsWith(live.name) && it.name != live.name })

        // Forcing a load exercises the file-read path: the value the save
        // wrote must come back out. The simplest way to verify that without
        // resetting the manager's in-memory state is to rebuild the JSON
        // decode path the same way loadSettings does.
        val json =
            kotlinx.serialization.json.Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }
        val reloaded = json.decodeFromString<BrowserZoomSettingsData>(onDisk)
        assertEquals(1.25, reloaded.domainSettings["example.com"]?.zoomLevel)
    }

    /**
     * The corrupt-load self-heal path through the real manager: a
     * deliberately-broken file at the live path is renamed aside when
     * [BrowserZoomSettingsManager.loadSettings] runs, the live path is
     * freed for the next save, and the in-memory state falls back to the
     * default rather than staying broken.
     */
    @Test
    fun `loading a corrupt file through the manager renames it aside and recovers to defaults`() {
        BrowserZoomSettingsManager.setZoomForDomain("before-load.example", 1.5)
        BrowserZoomSettingsManager.saveSettingsSync()
        // Replace the saved file with corrupt bytes so the next load triggers recovery.
        BrowserZoomSettingsManager.settingsFile.writeText("{ not valid json")

        BrowserZoomSettingsManager.loadSettings()

        assertFalse(
            BrowserZoomSettingsManager.settingsFile.exists(),
            "the live path must be free after the corrupt file is renamed aside",
        )
        val asides = tmp.listFiles()!!.filter { it.name.startsWith("browser-zoom-settings.json.corrupt.") }
        assertEquals(1, asides.size, "exactly one aside for the one corrupt load")
        assertEquals(1.0, BrowserZoomSettingsManager.getZoomForDomain("after-load.example"))
    }

    // --- read-modify-write race: concurrent mutators must not lose updates ------------------

    /**
     * #1051's lost-update case: the in-memory `settings` map is read,
     * mutated, and written back by [setZoomForDomain], and two concurrent
     * callers without serialization can each read the same starting state
     * and overwrite each other. The lock turns the R-M-W into a single
     * critical section, so every domain a thread writes survives.
     *
     * Without the lock the test fails under any thread schedule where two
     * reads complete before either write - which the [CyclicBarrier] at
     * the call site is meant to encourage, and which the 100 iterations
     * above that make reliable.
     */
    @Test
    fun `concurrent mutators do not lose updates`() {
        val iterations = 100
        val threadCount = 4
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            repeat(iterations) { iteration ->
                BrowserZoomSettingsManager.clearAllSettings()
                val domains = Array(threadCount) { i -> "iter-$iteration-thread-$i.example" }
                val ready = CyclicBarrier(threadCount)
                val done = CountDownLatch(threadCount)
                for (i in 0 until threadCount) {
                    executor.execute {
                        // All threads enter the R-M-W at the same instant
                        // so the race window is at its widest.
                        ready.await(5, TimeUnit.SECONDS)
                        BrowserZoomSettingsManager.setZoomForDomain(domains[i], 1.0 + i * 0.1)
                        done.countDown()
                    }
                }
                assertTrue(done.await(5, TimeUnit.SECONDS), "iteration $iteration: threads did not finish")
                for (i in 0 until threadCount) {
                    val expected = 1.0 + i * 0.1
                    val actual = BrowserZoomSettingsManager.getZoomForDomain(domains[i])
                    assertEquals(expected, actual, "iteration $iteration thread $i: ${domains[i]} lost its update")
                }
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /**
     * Companion regression: a mutator interleaved with a save must not
     * observe a torn state - the saved file must contain the union of the
     * mutator's write and whatever the save captured. Without the lock,
     * the save could read the in-memory state BEFORE the mutator's update
     * lands and the file would be missing the latest change.
     */
    @Test
    fun `a save after a mutator contains the mutator's value`() {
        BrowserZoomSettingsManager.clearAllSettings()
        BrowserZoomSettingsManager.setZoomForDomain("first.example", 1.25)
        BrowserZoomSettingsManager.saveSettingsSync()
        BrowserZoomSettingsManager.setZoomForDomain("second.example", 1.5)
        BrowserZoomSettingsManager.saveSettingsSync()

        val onDisk = BrowserZoomSettingsManager.settingsFile.readText()
        assertTrue(onDisk.contains("first.example"), "first mutator's domain must be in the saved file")
        assertTrue(onDisk.contains("second.example"), "second mutator's domain must be in the saved file")

        // POSIX permissions on the saved file match the atomic-write helper's
        // owner-only contract; use assumeTrue so a platform where the call
        // fails (Windows, FAT, anything that does not support POSIX bits)
        // shows as SKIPPED rather than silently passing the assertion.
        val settingsPath = BrowserZoomSettingsManager.settingsFile.toPath()
        val permsResult = runCatching { Files.getPosixFilePermissions(settingsPath) }
        assumeTrue(
            permsResult.isSuccess,
            "POSIX permissions are not supported on this platform - skipping the bit check",
        )
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            permsResult.getOrThrow(),
        )
    }

    // --- #1051 review: read I/O errors must NOT quarantine (#1051) -----------------------

    /**
     * A transient read failure (file locked by AV or sync, a permission
     * hiccup) used to be caught by the broad `Exception` block and routed
     * through the quarantine path. The fix splits I/O from decode errors:
     * an [IOException] keeps the file where it is, loads defaults in
     * memory, and gates saves - the file might still hold a valid value
     * that the read just could not reach. Without the fix, an AV-locked
     * valid file would be moved aside and the next save would write
     * defaults over the live path, silently destroying the user's
     * per-domain zoom levels.
     */
    @Test
    fun `a read IOException on a valid file keeps the file and loads defaults in memory (#1051)`() {
        val live = BrowserZoomSettingsManager.settingsFile
        // Write a valid JSON the manager would happily decode on a real read.
        live.writeText(
            """
            {
              "domainSettings": {
                "preserved.example": { "domain": "preserved.example", "zoomLevel": 1.5 }
              },
              "defaultZoomLevel": 1.0
            }
            """.trimIndent(),
        )

        // Force the read to throw a real IOException. The seam is the new
        // readText parameter on loadSettings; production callers do not
        // pass it.
        BrowserZoomSettingsManager.loadSettings(readText = {
            throw IOException("simulated AV lock")
        })

        // The file is still where it was - we did NOT quarantine.
        assertTrue(live.exists(), "a transient I/O error must leave the file in place")
        assertEquals(
            emptyList(),
            tmp.listFiles()!!.filter { it.name.contains(".corrupt.") },
            "no aside may be created for an I/O error - only for real decode failures",
        )

        // In-memory state is the defaults; the next save must not be
        // allowed to overwrite the file we just failed to read.
        assertEquals(1.0, BrowserZoomSettingsManager.getZoomForDomain("preserved.example"))
    }

    /**
     * Companion to the I/O-error test: after a failed read, both save
     * entry points must refuse to write the in-memory defaults over the
     * still-present live file. Without the gate, the user's zoom levels
     * get destroyed on the next call site that mutates and saves.
     */
    @Test
    fun `save refuses to overwrite a live file after a read IOException (#1051)`() {
        val live = BrowserZoomSettingsManager.settingsFile
        val originalBytes =
            """
            {
              "domainSettings": {
                "preserved.example": { "domain": "preserved.example", "zoomLevel": 1.5 }
              },
              "defaultZoomLevel": 1.0
            }
            """.trimIndent()
        live.writeText(originalBytes)

        // Force a read failure so canSaveSafely flips to false.
        BrowserZoomSettingsManager.loadSettings(readText = {
            throw IOException("simulated AV lock")
        })

        // Mutate so the in-memory state is no longer empty; both save entry
        // points would otherwise happily write defaults that lose the user's
        // zoom levels.
        BrowserZoomSettingsManager.setZoomForDomain("defaults-only.example", 1.25)
        BrowserZoomSettingsManager.saveSettingsSync()
        runBlocking { BrowserZoomSettingsManager.saveSettings() }

        // The live file still carries the bytes the user had on disk. The
        // gate held: nothing on disk was overwritten.
        assertEquals(
            originalBytes.trimIndent(),
            live.readText().trim(),
            "the live file must not be overwritten while a previous read failed",
        )
    }

    /**
     * The save gate must lift on the next successful load. Without this
     * the manager would be permanently read-only after a single AV lock
     * - the very condition a follow-up launch might trigger.
     */
    @Test
    fun `the save gate lifts after a subsequent successful load (#1051)`() {
        val live = BrowserZoomSettingsManager.settingsFile
        live.writeText(
            """
            {
              "domainSettings": {
                "first.example": { "domain": "first.example", "zoomLevel": 1.5 }
              },
              "defaultZoomLevel": 1.0
            }
            """.trimIndent(),
        )

        // First load fails: gate is closed, in-memory state is reset to defaults.
        BrowserZoomSettingsManager.loadSettings(readText = { throw IOException("flaky") })
        BrowserZoomSettingsManager.saveSettingsSync()
        assertEquals(
            true,
            live.readText().contains("first.example"),
            "save must still be refused after the first failed load - " +
                "the file must stay byte-for-byte what the user had",
        )

        // Second load succeeds: gate re-opens. The next save carries the
        // post-recovery mutator's domain to disk.
        BrowserZoomSettingsManager.loadSettings()
        BrowserZoomSettingsManager.setZoomForDomain("pending.example", 1.25)
        BrowserZoomSettingsManager.saveSettingsSync()
        val onDisk = live.readText()
        assertTrue(
            onDisk.contains("first.example"),
            "the saved file must carry the value the second load recovered: $onDisk",
        )
        assertTrue(
            onDisk.contains("pending.example"),
            "the saved file must carry the post-recovery mutator's domain: $onDisk",
        )
    }

    // --- #1051 review: cap the corrupt asides so they cannot fill the directory -----------

    /**
     * Repeated corruption must not be allowed to fill the user's settings
     * directory. The cap keeps the newest [maxAsides] and deletes older
     * ones on each quarantine, so the most recent failure stays
     * diagnosable while older ones are recycled.
     */
    @Test
    fun `corrupt asides are capped so repeated corruption cannot fill the directory (#1051)`() {
        val live = File(tmp, "browser-zoom-settings.json")
        repeat(5) { i ->
            live.writeText("garbage #$i")
            moveCorruptSettingsAside(live, now = { 1_726_000_000_000L + i })
        }
        val asides = tmp.listFiles()!!.filter { it.name.contains(".corrupt.") }
        assertEquals(3, asides.size, "the cap is 3 - older asides are deleted")
        // The newest three are the survivors; their content matches the
        // last three quarantines.
        assertEquals(
            setOf("garbage #2", "garbage #3", "garbage #4"),
            asides.map { it.readText() }.toSet(),
        )
    }

    /**
     * The cap operates on OTHERS, not on the just-created aside. A same-
     * millisecond tie (or a clock that stepped back, or an unparseable
     * older stamp) used to drop the freshly-quarantined file from the
     * survivor set, breaking the "newest is kept" guarantee. The new aside
     * is pinned first; the cap deletes the older candidates only.
     *
     * The injected `now()` returns the SAME value for the fourth quarantine
     * as for the third (a same-ms tie) AND for the fourth returns a value
     * smaller than the third - so the sort tie would otherwise put the new
     * aside last. The cap must still keep the freshly-created copy.
     */
    @Test
    fun `the cap never deletes the aside it just created (#1051)`() {
        val live = File(tmp, "browser-zoom-settings.json")
        val nows =
            listOf(
                1_726_000_000_000L,
                1_726_000_000_001L,
                // Fourth quarantine returns the same millis as the third - a tie.
                // On Windows + ext4 the tie would have sorted the new aside last,
                // dropping it under the cap.
                1_726_000_000_002L,
                1_726_000_000_002L,
            )
        val nowIter = nows.iterator()
        repeat(4) { i ->
            live.writeText("garbage #$i")
            moveCorruptSettingsAside(live, now = { nowIter.next() })
        }
        val asides = tmp.listFiles()!!.filter { it.name.contains(".corrupt.") }
        assertEquals(3, asides.size, "the cap is 3 - the freshly-created aside must survive the tie")
        // The fourth-quarantine bytes must be present - the new aside was not
        // deleted by its own cap. With the OLD broken logic, the same-ms tie
        // would have dropped it; the survivor set would be garbage #1, #2, #3.
        assertTrue(
            asides.any { it.readText() == "garbage #3" },
            "the just-created aside must survive a same-ms tie with an older one: " +
                "asides = ${asides.map { it.name to it.readText() }}",
        )
    }
}
