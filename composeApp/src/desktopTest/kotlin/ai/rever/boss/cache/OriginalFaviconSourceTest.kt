package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OriginalFaviconSourceTest {
    @AfterTest
    fun cleanUp(): Unit =
        runTest {
            OriginalFaviconSource.clearAttempts()
            HqFaviconDiskCache.delete(HqFaviconDiskCache.keyFor("home-lookup.test"))
        }

    @Test
    fun `Gmail's original ICO supplies the matching 256px artwork instead of a generic Google logo`() {
        val page = page("gmail-page-16.png")
        val original = assertNotNull(OriginalFaviconSource.decode(bytes("gmail-original.ico")))
        assertEquals(256, original.bitmap.width)
        assertSame(original.icon, sharperMatchingFavicon(page, original.icon))
        val google = page("google-cached-large.png")
        assertSame(page, sharperMatchingFavicon(page, google))
    }

    @Test
    fun `BOSS SVG preserves its matching 256px embedded PNG artwork`() {
        val page = page("boss-page-16.png")
        val original = assertNotNull(OriginalFaviconSource.decode(bytes("boss-original.svg")))
        assertEquals(256, original.bitmap.width)
        assertEquals(256, original.bitmap.height)
        assertSame(original.icon, sharperMatchingFavicon(page, original.icon))
    }

    @Test
    fun `the site's ICO is fetched directly without downloading the homepage when it is already sharp`(): Unit =
        runTest {
            val requests = mutableListOf<String>()
            val result =
                OriginalFaviconSource.resolve("https://mail.google.com/", page("gmail-page-16.png")) { url ->
                    requests.add(url)
                    bytes("gmail-original.ico")
                }
            assertEquals(256, assertNotNull(result).bitmap.width)
            assertEquals(listOf("https://mail.google.com/favicon.ico"), requests)
        }

    @Test
    fun `a missing ICO can fall back to the original SVG declared by the site`(): Unit =
        runTest {
            val requests = mutableListOf<String>()
            val result =
                OriginalFaviconSource.resolve("https://cli.risaboss.com/", page("boss-page-16.png")) { url ->
                    requests.add(url)
                    when (url) {
                        "https://cli.risaboss.com/" -> {
                            """
                            <html><head>
                            <link rel='icon' type='image/svg+xml' href='/app-viewer/boss-logo.svg'>
                            </head></html>
                            """.trimIndent().toByteArray()
                        }

                        "https://cli.risaboss.com/app-viewer/boss-logo.svg" -> {
                            bytes("boss-original.svg")
                        }

                        else -> {
                            null
                        }
                    }
                }
            assertEquals(256, assertNotNull(result).bitmap.width)
            assertEquals(
                listOf(
                    "https://cli.risaboss.com/favicon.ico",
                    "https://cli.risaboss.com/",
                    "https://cli.risaboss.com/app-viewer/boss-logo.svg",
                ),
                requests,
            )
        }

    @Test
    fun `unrelated site artwork never replaces the recorded page icon`(): Unit =
        runTest {
            assertNull(
                OriginalFaviconSource.resolve("https://example.test/", page("gmail-page-16.png")) { url ->
                    if (url.endsWith("favicon.ico")) bytes("google-cached-large.png") else null
                },
            )
        }

    @Test
    fun `icon discovery strips credentials page paths queries and fragments`() {
        assertEquals(
            "https://example.test:8443/",
            OriginalFaviconSource.originFor("https://user:password@example.test:8443/private?token=secret#inbox"),
        )
        assertNull(OriginalFaviconSource.originFor("file:///Users/someone/icon.svg"))
        assertNull(OriginalFaviconSource.originFor("about:blank"))
        assertNull(OriginalFaviconSource.originFor(null))
    }

    @Test
    fun `only actual web icon links are considered and relative paths resolve correctly`() {
        val html =
            """
            <html><head>
            <link rel="stylesheet" href="style.css">
            <link rel="shortcut icon" href="icons/site.ico">
            <link rel="apple-touch-icon" href="/apple.png">
            <link rel="icon" href="https://cdn.example.test/logo.svg">
            <link rel="icon" href="file:///tmp/icon.png">
            <link rel="icon" href="https://user:pass@example.test/icon.png">
            <link rel="icon" href="data:image/svg+xml,anything">
            </head></html>
            """.trimIndent()
        assertEquals(
            listOf(
                "https://example.test/icons/site.ico",
                "https://example.test/apple.png",
                "https://cdn.example.test/logo.svg",
            ),
            OriginalFaviconSource.iconLinks("https://example.test/", html),
        )
    }

    @Test
    fun `corrupt oversized and non-image responses are ignored`() {
        assertNull(OriginalFaviconSource.decode("not an image".toByteArray()))
        assertNull(OriginalFaviconSource.decode(ByteArray(256 * 1024 + 1)))
        assertNull(OriginalFaviconSource.decode("<html>an error page</html>".toByteArray()))
        val oversized = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(2048, 1, BufferedImage.TYPE_INT_ARGB), "png", oversized)
        assertNull(OriginalFaviconSource.decode(oversized.toByteArray()))
    }

    @Test
    fun `a vector SVG renders at 128px with transparent surrounding pixels`() {
        val vector =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
            <circle cx="50" cy="50" r="30" fill="red"/></svg>
            """.trimIndent().toByteArray()
        val original = assertNotNull(OriginalFaviconSource.decode(vector))
        assertEquals(128, original.bitmap.width)
        assertEquals(128, original.bitmap.height)
        val pixels = original.bitmap.toPixelMap()
        assertEquals(0f, pixels[0, 0].alpha)
        assertTrue(pixels[64, 64].red > 0.9f && pixels[64, 64].alpha > 0.9f)
    }

    @Test
    fun `a suggested page without a cached icon can use its own original artwork`(): Unit =
        runTest {
            val original =
                OriginalFaviconSource.resolve("https://mail.google.com/", null) { bytes("gmail-original.ico") }
            assertEquals(256, assertNotNull(original).bitmap.width)
        }

    @Test
    fun `concurrent cards for one site wait for the same original instead of leaving some blurry`(): Unit =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val fetch: suspend (String) -> ByteArray? = {
                requests.incrementAndGet()
                entered.complete(Unit)
                release.await()
                bytes("gmail-original.ico")
            }
            val page = page("gmail-page-16.png")
            val first = async { OriginalFaviconSource.sharperIcon("https://home-lookup.test/first", page, fetch) }
            entered.await()
            val second = async { OriginalFaviconSource.sharperIcon("https://home-lookup.test/second", page, fetch) }
            release.complete(Unit)
            assertEquals(256f, assertNotNull(first.await()).painter.intrinsicSize.width)
            assertEquals(256f, assertNotNull(second.await()).painter.intrinsicSize.width)
            assertEquals(1, requests.get())
            assertNotNull(
                OriginalFaviconSource.sharperIcon("https://home-lookup.test/third", page) { error("fetched twice") },
            )
        }

    @Test
    fun `cancelling the source owner permits a later retry`(): Unit =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val page = page("gmail-page-16.png")
            val first =
                async {
                    OriginalFaviconSource.sharperIcon("https://home-lookup.test/first", page) {
                        entered.complete(Unit)
                        CompletableDeferred<ByteArray?>().await()
                    }
                }
            entered.await()
            first.cancelAndJoin()
            val result =
                OriginalFaviconSource.sharperIcon("https://home-lookup.test/second", page) {
                    bytes("gmail-original.ico")
                }
            assertEquals(256f, assertNotNull(result).painter.intrinsicSize.width)
        }

    private fun bytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/favicon-quality/$name").use { assertNotNull(it).readBytes() }

    private fun page(name: String): TabIcon.Image =
        bytes(name).inputStream().use { stream ->
            TabIcon.Image(BitmapPainter(assertNotNull(ImageIO.read(stream)).toComposeImageBitmap()))
        }
}
