package ai.rever.boss.cache

import kotlinx.coroutines.test.runTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [HighQualityFaviconService.hostIcon], where the fetch outcome, the entry on disk and
 * the miss memory meet.
 *
 * The distinction that runs through all of it: **Google not answering and Google answering "no
 * icon" mean opposite things about the cached copy.** No answer leaves it the best thing anyone
 * has; a definite no means the site removed its icon and the copy is now a picture of something
 * that is gone. Conflating them either throws away a usable icon whenever the network hiccups, or
 * shows a removed one until 200 entries force an eviction.
 *
 * The clock, the cache directory and the fetch are all injected, so none of this touches the
 * network or the developer's real `~/.boss/cache`.
 */
class HostIconResolutionTest {
    private val dir: File =
        File.createTempFile("favicon-hosticon-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        FaviconMissMemory.forget()
    }

    private fun entryFor(host: String) = File(dir, "${HqFaviconDiskCache.keyFor(host)}.png")

    private fun writeEntry(ageMs: Long): File =
        entryFor(HOST).also {
            ImageIO.write(sixteenPxIcon(), "PNG", it)
            it.setLastModified(NOW - ageMs)
        }

    @Test
    fun `a fresh entry is served without a request`() =
        runTest {
            writeEntry(ageMs = DAY)
            var fetched = false

            val icon =
                HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ ->
                    fetched = true
                    FaviconFetch.NoAnswer
                }

            assertNotNull(icon)
            assertFalse(fetched, "a fresh entry still cost a request")
        }

    /** Offline should cost sharpness, not the icon - and must not be irreversible. */
    @Test
    fun `no answer leaves an expired entry serving`() =
        runTest {
            val file = writeEntry(ageMs = FaviconFreshness.MAX_CACHE_AGE_MS + DAY)

            val icon = HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ -> FaviconFetch.NoAnswer }

            assertNotNull(icon, "being offline took the icon away")
            assertTrue(file.exists())
            assertFalse(FaviconMissMemory.remembers(HOST, NOW), "a transient failure was remembered as a miss")
        }

    /**
     * The site dropped its favicon. Keeping the entry would show a removed icon until eviction,
     * because expiry alone never deletes - it only means "prefer a refetch".
     */
    @Test
    fun `a definite no-icon drops the entry rather than serving it stale`() =
        runTest {
            val file = writeEntry(ageMs = FaviconFreshness.MAX_CACHE_AGE_MS + DAY)

            val icon =
                HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ ->
                    FaviconMissMemory.record(HOST, NOW)
                    FaviconFetch.NoIcon
                }

            assertNull(icon, "an icon the site has removed is still being served")
            assertFalse(file.exists(), "the entry for a host with no favicon survived")
        }

    /** And having learned it, the next resolution does not re-ask, nor resurrect anything. */
    @Test
    fun `a remembered miss skips the request`() =
        runTest {
            FaviconMissMemory.record(HOST, NOW)
            var fetched = false

            val icon =
                HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ ->
                    fetched = true
                    FaviconFetch.NoAnswer
                }

            assertNull(icon)
            assertFalse(fetched, "a host known to have no favicon was asked about again")
        }

    /**
     * The state a FAILED delete leaves behind: the miss is remembered but the entry survives - a
     * Windows lock, a permission problem. Returning null there would show a letter for six hours
     * with a usable icon sitting on disk, and would repeat every six hours after that.
     */
    @Test
    fun `an entry that outlived its delete still serves while the miss is remembered`() =
        runTest {
            writeEntry(ageMs = FaviconFreshness.MAX_CACHE_AGE_MS + DAY)
            FaviconMissMemory.record(HOST, NOW)

            val icon = HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ -> FaviconFetch.NoAnswer }

            assertNotNull(icon, "an icon that survived its own deletion was not used")
        }

    /** The window is six hours, not forever: a host that adds a favicon gets asked again. */
    @Test
    fun `the request resumes once the miss window passes`() =
        runTest {
            FaviconMissMemory.record(HOST, NOW)
            val fetched = iconStub()

            val icon =
                HighQualityFaviconService.hostIcon(
                    URL,
                    NOW + FaviconMissMemory.MISS_MEMORY_MS + 1,
                    dir,
                ) { _, _ -> FaviconFetch.Icon(fetched) }

            assertSame(fetched, icon)
        }

    @Test
    fun `a fetched icon wins over an expired entry`() =
        runTest {
            writeEntry(ageMs = FaviconFreshness.MAX_CACHE_AGE_MS + DAY)
            val fetched = iconStub()

            val icon = HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ -> FaviconFetch.Icon(fetched) }

            assertSame(fetched, icon)
        }

    // -------------------------------------------- what a reply means

    /**
     * The placeholder is the one reply that means Google looked and found nothing, so it is the
     * only one that may be remembered - and remembering is what stops the next composition of the
     * tile spending another 2.5s-timeout request.
     */
    @Test
    fun `the placeholder is a definite miss and is remembered`() =
        runTest {
            val outcome = HighQualityFaviconService.acceptResponse(placeholderBytes(), HOST, KEY, dir)

            assertEquals(FaviconFetch.NoIcon, outcome)
            assertTrue(FaviconMissMemory.remembers(HOST))
            assertFalse(entryFor(HOST).exists(), "the placeholder was cached")
        }

    /**
     * HTTP 200 with something that is not an image is what a rate-limit interstitial or a
     * proxy-truncated body looks like. Transient, so it must NOT suppress the retry for six hours
     * - which recording it as a miss would do.
     */
    @Test
    fun `an undecodable reply is not a miss and is not remembered`() =
        runTest {
            val notAnImage = "<html>rate limited</html>".toByteArray()

            val outcome = HighQualityFaviconService.acceptResponse(notAnImage, HOST, KEY, dir)

            assertEquals(FaviconFetch.NoAnswer, outcome)
            assertFalse(FaviconMissMemory.remembers(HOST), "a transient reply was remembered as a definite miss")
        }

    @Test
    fun `a real icon is cached and returned`() =
        runTest {
            val outcome = HighQualityFaviconService.acceptResponse(pngBytes(sixteenPxIcon()), HOST, KEY, dir)

            assertTrue(outcome is FaviconFetch.Icon)
            assertNotNull(HqFaviconDiskCache.load(KEY, dir))
            assertFalse(FaviconMissMemory.remembers(HOST))
        }

    /** A terminal, a file, a `boss://` panel: no host, so no request and no icon. */
    @Test
    fun `a url with no host is never fetched for`() =
        runTest {
            var fetched = false
            val fetch: suspend (String, String) -> FaviconFetch = { _, _ ->
                fetched = true
                FaviconFetch.NoAnswer
            }

            assertNull(HighQualityFaviconService.hostIcon(null, NOW, dir, fetch))
            assertNull(HighQualityFaviconService.hostIcon("file:///Users/someone/notes.md", NOW, dir, fetch))
            assertFalse(fetched)
        }

    private fun placeholderBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/google-no-icon-placeholder.png")) {
            "fixture missing from the test resources"
        }.use { it.readBytes() }

    private companion object {
        const val HOST = "example.test"
        val KEY: String = HqFaviconDiskCache.keyFor(HOST)
        const val URL = "https://example.test/page"
        const val NOW = 1_700_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}

/** The miss memory's bound, which nothing else exercises. */
class FaviconMissMemoryBoundTest {
    @AfterTest
    fun cleanUp() {
        FaviconMissMemory.forget()
    }

    /** Expired entries are the cheap subset to drop, so a steady trickle of misses never fills it. */
    @Test
    fun `expired misses are pruned before the bound bites`() {
        val now = 1_700_000_000_000L
        repeat(FaviconMissMemory.MAX_REMEMBERED) { i ->
            FaviconMissMemory.record("old-$i.test", now - FaviconMissMemory.MISS_MEMORY_MS - 1)
        }

        FaviconMissMemory.record("fresh.test", now)

        assertTrue(FaviconMissMemory.remembers("fresh.test", now))
        assertFalse(FaviconMissMemory.remembers("old-0.test", now))
    }

    /**
     * Nothing had expired, so there is no cheap subset: the map goes, which costs one extra
     * request per host to rebuild and never more.
     */
    @Test
    fun `a bound reached with nothing expired drops the lot`() {
        val now = 1_700_000_000_000L
        repeat(FaviconMissMemory.MAX_REMEMBERED) { i -> FaviconMissMemory.record("host-$i.test", now) }

        FaviconMissMemory.record("one-more.test", now)

        assertTrue(FaviconMissMemory.remembers("one-more.test", now))
        assertFalse(FaviconMissMemory.remembers("host-0.test", now))
        assertEquals(false, FaviconMissMemory.remembers("host-${FaviconMissMemory.MAX_REMEMBERED - 1}.test", now))
    }
}
