package ai.rever.boss.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for what [HqFaviconDiskCache] does to files, which is the part of this PR that *removes or
 * retains user data* and was the only part with nothing pinning it.
 *
 * Uses the `internal` directory seam, the same one [FaviconCacheTest] uses - the default resolves
 * through `BossDirectories` to the developer's real `~/.boss/cache`.
 */
class HqFaviconDiskCacheTest {
    private val dir: File =
        File.createTempFile("favicon-hq-cache-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun entryFor(host: String) = File(dir, "${HqFaviconDiskCache.keyFor(host)}.png")

    private fun writeEntry(
        host: String,
        ageMs: Long = 0,
    ): File =
        entryFor(host).also { file ->
            ImageIO.write(sixteenPxIcon(), "PNG", file)
            file.setLastModified(System.currentTimeMillis() - ageMs)
        }

    @Test
    fun `a stored icon comes back with the time it was fetched`() =
        runTest {
            HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor(HOST), sixteenPxIcon(), dir)

            val entry = assertNotNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(HOST), dir))
            assertEquals(entryFor(HOST).lastModified(), entry.fetchedAtMs)
        }

    /**
     * **The no-touch guarantee.** The mtime was bumped on every read to order an LRU, which made
     * the age the TTL is measured from unknowable and pinned the most-shown entries - a wrong icon
     * on a favourite tile among them - beyond eviction's reach. A regression to bumping would
     * otherwise be invisible until someone's icons stopped expiring.
     */
    @Test
    fun `a read does not touch the entry`() {
        val file = writeEntry(HOST, ageMs = TEN_DAYS)
        val before = file.lastModified()

        assertNotNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(HOST), dir))

        assertEquals(before, file.lastModified(), "reading bumped the mtime the TTL is measured from")
    }

    /**
     * **An expired entry is not destroyed before a replacement exists.** Deleting on read meant:
     * delete the only copy, fetch fails (offline, Google blocked, rate-limited), letter - and a
     * letter on every later launch too. Freshness is the caller's decision; this hands back the
     * bytes and the date.
     */
    @Test
    fun `an expired entry survives the read that finds it stale`() {
        val file = writeEntry(HOST, ageMs = FaviconFreshness.MAX_CACHE_AGE_MS + TEN_DAYS)

        val entry = assertNotNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(HOST), dir))

        assertTrue(FaviconFreshness.isEntryExpired(entry.fetchedAtMs, System.currentTimeMillis()))
        assertTrue(file.exists(), "the only copy was deleted before anything could replace it")
    }

    @Test
    fun `a missing or unreadable entry is a cache miss, not a throw`() {
        assertNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor("never-fetched.test"), dir))

        entryFor(HOST).writeText("not a png")
        assertNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(HOST), dir))
    }

    /**
     * A write replaces the entry through a temp file and a move, so a concurrent reader never
     * meets a half-written PNG - and nothing is left behind if it does not.
     */
    @Test
    fun `a write replaces the entry and leaves no temp file`() =
        runTest {
            writeEntry(HOST, ageMs = FaviconFreshness.MAX_CACHE_AGE_MS + TEN_DAYS)
            val key = HqFaviconDiskCache.keyFor(HOST)

            HqFaviconDiskCache.save(key, sixteenPxIcon(), dir)

            val entry = assertNotNull(HqFaviconDiskCache.load(key, dir))
            assertTrue(!FaviconFreshness.isEntryExpired(entry.fetchedAtMs, System.currentTimeMillis()))
            assertEquals(emptyList(), dir.listFiles()!!.filter { it.name.endsWith(".part") }.map { it.name })
            assertEquals(1, HqFaviconDiskCache.stats(dir).first)
        }

    /**
     * Eviction is oldest-*fetched* first now that reads leave the mtime alone. What it must not do
     * is take the entry currently being written.
     */
    @Test
    fun `a full cache evicts its oldest entries and keeps the new one`() =
        runTest {
            // One under the cap, aged so the oldest are unambiguous, then one more write to tip it.
            repeat(MAX_ENTRIES) { index -> writeEntry("host-$index.test", ageMs = (MAX_ENTRIES - index) * 1000L) }
            val newest = "just-fetched.test"

            HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor(newest), sixteenPxIcon(), dir)

            assertEquals(MAX_ENTRIES - EVICTION_COUNT + 1, HqFaviconDiskCache.stats(dir).first)
            assertNotNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(newest), dir))
            assertNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor("host-0.test"), dir), "oldest survived")
            assertNotNull(HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor("host-${MAX_ENTRIES - 1}.test"), dir))
        }

    /**
     * A write that cannot happen leaves nothing behind - no entry, and no `.part` file holding a
     * name. The entry it would have replaced is untouched, which is the half that matters: a torn
     * write must not cost the icon that was already there.
     */
    @Test
    fun `a write that fails leaves the cache as it found it`() =
        runTest {
            val existing = writeEntry(HOST)
            val before = existing.readBytes()
            val gone = File(dir, "not-a-directory")

            HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor(HOST), sixteenPxIcon(), gone)

            assertEquals(emptyList(), dir.listFiles()!!.filter { it.name.endsWith(".part") }.map { it.name })
            assertEquals(before.toList(), existing.readBytes().toList())
        }

    /**
     * The whole reason the `.part` file, `atomicMoveFrom` and the shared mutex exist: the shelf,
     * the dashboard and the picker can all resolve the same host at the same moment. Every writer
     * gets its own temp file and the last move wins, so no reader meets a torn PNG and no temp
     * file is left holding a name.
     *
     * On [Dispatchers.Default] rather than `runTest`'s scheduler, because virtual time would
     * serialise exactly the overlap this is about.
     */
    @Test
    fun `concurrent writes for one key leave one whole entry and no temp files`() =
        runTest {
            val key = HqFaviconDiskCache.keyFor(HOST)

            withContext(Dispatchers.Default) {
                List(WRITERS) { async { HqFaviconDiskCache.save(key, sixteenPxIcon(), dir) } }.awaitAll()
            }

            assertEquals(1, HqFaviconDiskCache.stats(dir).first)
            assertEquals(emptyList(), dir.listFiles()!!.filter { it.name.endsWith(".part") }.map { it.name })
            assertNotNull(HqFaviconDiskCache.load(key, dir), "the surviving entry does not decode")
        }

    /**
     * `.part` files are excluded from the cache's own listing, which is load-bearing twice: a write
     * in flight is neither counted towards the cap nor taken by the eviction that runs before it.
     */
    @Test
    fun `a write in flight is neither counted nor evicted`() =
        runTest {
            val inFlight = File(dir, "hq-favicon_123.part").apply { writeBytes(ByteArray(64)) }
            repeat(MAX_ENTRIES) { index -> writeEntry("host-$index.test", ageMs = (MAX_ENTRIES - index) * 1000L) }

            assertEquals(MAX_ENTRIES, HqFaviconDiskCache.stats(dir).first, "a .part file was counted as an entry")

            HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor("just-fetched.test"), sixteenPxIcon(), dir)

            assertTrue(inFlight.exists(), "eviction took a write that was still in flight")
        }

    @Test
    fun `clear empties the cache`() =
        runTest {
            writeEntry(HOST)
            HqFaviconDiskCache.clear(dir)
            assertEquals(0, HqFaviconDiskCache.stats(dir).first)
        }

    private companion object {
        const val HOST = "example.test"
        const val TEN_DAYS = 10L * 24 * 60 * 60 * 1000

        /**
         * Mirrors the cache's own cap and batch size. The eviction test asserts the count
         * `MAX_ENTRIES - EVICTION_COUNT + 1`, so changing either without changing these fails it.
         */
        const val MAX_ENTRIES = 200
        const val EVICTION_COUNT = 50

        /** Enough to overlap on any machine; the mutex makes the count irrelevant beyond that. */
        const val WRITERS = 8
    }
}
