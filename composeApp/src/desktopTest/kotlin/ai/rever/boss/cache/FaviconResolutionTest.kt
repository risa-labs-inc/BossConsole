package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
 * Tests for the three pure decisions behind [HighQualityFaviconService]: which host gets asked
 * about, whether Google's answer is an answer at all, and when a cached answer stops being
 * trusted.
 *
 * The fetch itself is not covered here - it needs the network, and what it does with a response is
 * exactly these decisions plus "can ImageIO decode it". The disk half is in
 * [HqFaviconDiskCacheTest].
 */
class FaviconResolutionTest {
    @AfterTest
    fun cleanUp() {
        // The miss memory is process-wide; a leaked entry would silently suppress another test's
        // fetch.
        FaviconMissMemory.forget()
    }

    // ------------------------------------------------------------- order

    /**
     * The whole point of the change, and the one thing that must not quietly flip back: a page
     * whose own favicon is known keeps it, and Google is not asked at all.
     *
     * Reversing the two calls inside [HighQualityFaviconService.resolve] fails both assertions -
     * the first because the guess would win, the second because the request would be spent. That
     * request is what told a third party which site a favourite points at.
     */
    @Test
    fun `the page's own icon wins and Google is not even asked`() =
        runTest {
            var askedAbout: String? = null
            val cached = iconStub()

            val resolved =
                HighQualityFaviconService.resolve(
                    url = "https://mail.google.com/mail/u/0",
                    standardCacheKey = CACHE_KEY,
                    pageIcon = { key -> cached.takeIf { key == CACHE_KEY } },
                    hostGuess = { url ->
                        askedAbout = url
                        iconStub()
                    },
                )

            assertSame(cached, resolved)
            assertNull(askedAbout, "a page with its own icon still told Google which site it is")
        }

    /** And the guess still fills the gap, which is the only reason it is consulted at all. */
    @Test
    fun `the host guess fills in when the page has no icon of its own`() =
        runTest {
            val guessed = iconStub()

            assertSame(
                guessed,
                HighQualityFaviconService.resolve(URL, null, pageIcon = { null }, hostGuess = { guessed }),
            )
            assertNull(HighQualityFaviconService.resolve(URL, null, pageIcon = { null }, hostGuess = { null }))
        }

    /**
     * A source that throws costs only itself. One corrupt entry in the standard cache must not
     * also suppress the host guess, and nothing may reach the caller: the tile shows its letter.
     */
    @Test
    fun `a source that throws costs itself, not the other source and not the tile`() =
        runTest {
            val guessed = iconStub()

            assertSame(
                guessed,
                HighQualityFaviconService.resolve(
                    URL,
                    CACHE_KEY,
                    pageIcon = { error("cache is corrupt") },
                    hostGuess = { guessed },
                ),
            )
            assertNull(
                HighQualityFaviconService.resolve(
                    URL,
                    CACHE_KEY,
                    pageIcon = { error("cache is corrupt") },
                    hostGuess = { error("google is on fire") },
                ),
            )
        }

    // ---------------------------------------------------------------- host

    @Test
    fun `host is extracted from an ordinary url`() {
        assertEquals("github.com", FaviconHost.of("https://github.com/rever/boss"))
        assertEquals("github.com", FaviconHost.of("http://www.github.com"))
        assertEquals("mail.google.com", FaviconHost.of("https://mail.google.com/mail/u/0/#inbox"))
        assertEquals("example.com", FaviconHost.of("HTTPS://Example.COM./x"))
    }

    /** Google keys on host alone and 404s on `localhost:3000`, so a dev server got no icon. */
    @Test
    fun `port is not part of the host`() {
        assertEquals("localhost", FaviconHost.of("http://localhost:3000/app"))
        assertEquals("dev.risalabs.ai", FaviconHost.of("https://dev.risalabs.ai:8443"))
    }

    /**
     * A privacy fix, not a tidy-up: the old extraction put `user:pw@example.com` in the `domain=`
     * parameter of a request to `www.google.com`, and then MD5'd it into a cache filename.
     */
    @Test
    fun `credentials are not part of the host`() {
        assertEquals("example.com", FaviconHost.of("https://user:pw@example.com/x"))
    }

    /**
     * A backslash ends the authority too. WHATWG says so for special schemes, so Chromium - and
     * therefore JxBrowser - opens `https://example.com\@evil.com/` on **example.com**. Splitting
     * on `/` alone would take the userinfo strip literally and hand Google `evil.com`: the wrong
     * icon on the tile, a request naming a site nobody visited, and that name cached for a
     * fortnight. Anyone who can put a URL in a bookmark file can reach it.
     */
    @Test
    fun `a backslash cannot smuggle a different host past the credential strip`() {
        assertEquals("example.com", FaviconHost.of("https://example.com\\@evil.com/"))
        assertEquals("example.com", FaviconHost.of("https://example.com\\evil.com/x"))
    }

    /**
     * An authority may carry an `&`. Interpolated into the query it appended attacker-shaped
     * parameters to a request BOSS makes to Google; `parameter()` encodes it, and the host is
     * extracted unchanged so the key it hashes to stays honest.
     */
    @Test
    fun `an ampersand in the authority stays part of the host`() {
        assertEquals("evil.com&x=1", FaviconHost.of("https://evil.com&x=1/"))
    }

    /** An IPv6 literal's colons are inside the brackets and are not a port. */
    @Test
    fun `ipv6 literal survives port stripping`() {
        assertEquals("[::1]", FaviconHost.of("http://[::1]:8080/"))
        // Unterminated, i.e. malformed. Nothing to strip and nothing to guess; pass it through
        // rather than truncate at a colon that is not a port separator.
        assertEquals("[::1", FaviconHost.of("http://[::1/"))
    }

    /**
     * A non-http URL has no host Google can answer for. This used to yield `file:` and spend a
     * round trip being told so.
     */
    @Test
    fun `non-http urls have no host to ask about`() {
        assertNull(FaviconHost.of("file:///Users/someone/notes.md"))
        assertNull(FaviconHost.of("boss://plugin/editor"))
        assertNull(FaviconHost.of("chrome://settings"))
        assertNull(FaviconHost.of("/just/a/path"))
        assertNull(FaviconHost.of(""))
        assertNull(FaviconHost.of(null))
    }

    /** A terminal tab's title or command must not be read as a domain and spend a request. */
    @Test
    fun `opaque schemes and non-urls have no host to ask about`() {
        assertNull(FaviconHost.of("mailto:someone@example.com"))
        assertNull(FaviconHost.of("javascript:alert(1)"))
        assertNull(FaviconHost.of("about:blank"))
        assertNull(FaviconHost.of("data:image/png;base64,AAAA"))
        assertNull(FaviconHost.of("npm run dev"))
    }

    /**
     * `NetscapeBookmarkParser` passes an export's HREF through verbatim and nothing on the import
     * path normalises a scheme onto it, so a scheme-less bookmark does reach the service. The old
     * extraction resolved these by accident of stripping a prefix that was not there, and the
     * failure mode of dropping them is a silent letter rather than an error.
     */
    @Test
    fun `scheme-relative and scheme-less urls still name a host`() {
        assertEquals("example.com", FaviconHost.of("//example.com/x"))
        assertEquals("example.com", FaviconHost.of("example.com/x"))
        assertEquals("example.com", FaviconHost.of("www.example.com"))
        assertEquals("localhost", FaviconHost.of("localhost:3000/app"))
        // A dotted quad is a host too, and the `://` branch already accepted one.
        assertEquals("192.168.1.10", FaviconHost.of("192.168.1.10:3000/app"))
    }

    @Test
    fun `query and fragment are not part of the host`() {
        assertEquals("google.com", FaviconHost.of("https://www.google.com?q=risa"))
        assertEquals("example.com", FaviconHost.of("https://example.com#top"))
    }

    // --------------------------------------------------------- placeholder

    /**
     * Google answers "no favicon here" with HTTP 200 and a 16x16 globe, identical for every
     * unknown host. Caching that is what made unrelated hosts share one anonymous icon.
     *
     * The fixture is the real 726-byte payload, captured from the service; a fingerprint test
     * against a re-encoded copy would prove nothing about the bytes actually arriving.
     */
    @Test
    fun `google's no-icon placeholder is recognised`() {
        assertEquals(726, placeholderBytes().size, "fixture is no longer the payload this pins")
        assertTrue(GoogleNoIconPlaceholder.matches(placeholderBytes()))
    }

    /**
     * And it is recognised *because of its bytes*, not because it is unusable. The placeholder
     * decodes perfectly well, so "did ImageIO manage it" cannot stand in for this check - which is
     * the whole reason the fingerprint exists.
     */
    @Test
    fun `the placeholder is a perfectly decodable image`() {
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(placeholderBytes())))
        assertEquals(16, decoded.width)
        assertEquals(16, decoded.height)
    }

    /**
     * The case the removed 32px floor makes newly important: a genuine 16px favicon must survive
     * both checks. `ByteArray(726) { 0 }` alone would only show SHA-256 is not trivially
     * colliding, because it is not an image at all.
     */
    @Test
    fun `a real 16px icon is not mistaken for the placeholder`() {
        val icon = pngBytes(sixteenPxIcon())
        assertFalse(GoogleNoIconPlaceholder.matches(icon))
        assertEquals(16, assertNotNull(ImageIO.read(ByteArrayInputStream(icon))).width)
    }

    @Test
    fun `non-images are not mistaken for the placeholder`() {
        assertFalse(GoogleNoIconPlaceholder.matches(ByteArray(726) { 0 }))
        assertFalse(GoogleNoIconPlaceholder.matches(ByteArray(0)))
    }

    // ----------------------------------------------------------- freshness

    /**
     * The fortnight boundary. Nothing else pins it, and a wrong guess used to be permanent - a
     * silent regression here shows up as icons that never correct themselves.
     */
    @Test
    fun `an entry expires after a fortnight and not before`() {
        val fetchedAt = 1_700_000_000_000L
        val fortnight = FaviconFreshness.MAX_CACHE_AGE_MS

        assertFalse(FaviconFreshness.isEntryExpired(fetchedAt, fetchedAt))
        assertFalse(FaviconFreshness.isEntryExpired(fetchedAt, fetchedAt + fortnight))
        assertTrue(FaviconFreshness.isEntryExpired(fetchedAt, fetchedAt + fortnight + 1))
        assertEquals(14L * 24 * 60 * 60 * 1000, fortnight)

        // A file dated in the future - restored by `rsync -a` from a skewed machine, or a clock
        // that stepped back. A negative age read as fresh would never be refetched at all, which
        // is the permanence this TTL exists to remove.
        assertTrue(FaviconFreshness.isEntryExpired(fetchedAt, fetchedAt - 1))
        assertTrue(FaviconFreshness.isEntryExpired(fetchedAt, fetchedAt - fortnight))
    }

    // --------------------------------------------------------- miss memory

    /**
     * Declining the placeholder left this service with no negative cache at all, so a tile
     * re-entering composition spent another 2.5s-timeout request to learn the same thing.
     */
    @Test
    fun `a recorded miss is remembered, then forgotten`() {
        val now = 1_700_000_000_000L
        val window = FaviconMissMemory.MISS_MEMORY_MS

        assertFalse(FaviconMissMemory.remembers("nothing.test", now))

        FaviconMissMemory.record("noicon.test", now)
        assertTrue(FaviconMissMemory.remembers("noicon.test", now))
        assertTrue(FaviconMissMemory.remembers("noicon.test", now + window))
        assertFalse(FaviconMissMemory.remembers("noicon.test", now + window + 1))
    }

    /** Much shorter than the entry TTL: a host that adds a favicon must not wait a fortnight. */
    @Test
    fun `misses are forgotten long before entries expire`() {
        assertTrue(FaviconMissMemory.MISS_MEMORY_MS < FaviconFreshness.MAX_CACHE_AGE_MS / 10)
    }

    @Test
    fun `forget clears every remembered miss`() {
        FaviconMissMemory.record("noicon.test")
        FaviconMissMemory.forget()
        assertFalse(FaviconMissMemory.remembers("noicon.test"))
    }

    private companion object {
        const val URL = "https://example.test/page"
        const val CACHE_KEY = "cache-key"
    }

    private fun placeholderBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/google-no-icon-placeholder.png")) {
            "fixture missing from the test resources"
        }.use { it.readBytes() }
}

/** A 16x16 icon with actual content, i.e. what a genuine small favicon looks like. */
internal fun sixteenPxIcon(): BufferedImage =
    BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                setRGB(x, y, if ((x + y) % 2 == 0) 0xFF1E88E5.toInt() else 0xFFFFFFFF.toInt())
            }
        }
    }

/** A decoded icon, for the tests that only care about identity. */
internal fun iconStub(): TabIcon.Image = TabIcon.Image(BitmapPainter(sixteenPxIcon().toComposeImageBitmap()))

/** [image] as the PNG bytes a fetch would hand the service. */
internal fun pngBytes(image: BufferedImage): ByteArray =
    ByteArrayOutputStream()
        .also { ImageIO.write(image, "PNG", it) }
        .toByteArray()
