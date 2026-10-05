package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FaviconLookupPolicyTest {
    private val dir =
        File.createTempFile("favicon-policy-", "").apply {
            delete()
            mkdirs()
        }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        FaviconMissMemory.forget()
        FaviconRetryMemory.clear()
    }

    @Test
    fun `offline retries pause without losing sharp artwork or changing its fetch date`(): Unit =
        runTest {
            val file = staleEntry(HOST)
            val fetchedAt = file.lastModified()
            var requests = 0
            val fetch: suspend (String, String) -> FaviconFetch = { _, _ ->
                requests++
                FaviconFetch.NoAnswer
            }
            for (now in listOf(NOW, NOW + FaviconRetryMemory.RETRY_DELAY_MS - 1)) {
                val resolved = assertNotNull(HighQualityFaviconService.hostIcon(URL, now, dir, fetch = fetch))
                assertEquals(128f, resolved.painter.intrinsicSize.width)
            }
            assertEquals(1, requests)
            assertEquals(fetchedAt, file.lastModified())
            assertFalse(FaviconMissMemory.remembers(HOST, NOW))
            HighQualityFaviconService.hostIcon(URL, NOW + FaviconRetryMemory.RETRY_DELAY_MS, dir, fetch = fetch)
            assertEquals(2, requests)
        }

    @Test
    fun `twenty restored tabs share one failed host attempt`(): Unit =
        runTest {
            staleEntry(HOST)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var requests = 0
            val results =
                List(20) {
                    async {
                        HighQualityFaviconService.hostIcon(URL, NOW, dir) { _, _ ->
                            requests++
                            started.complete(Unit)
                            finish.await()
                            FaviconFetch.NoAnswer
                        }
                    }
                }
            started.await()
            runCurrent()
            assertEquals(1, requests)
            finish.complete(Unit)
            results.awaitAll().forEach { assertEquals(128f, assertNotNull(it).painter.intrinsicSize.width) }
            assertEquals(1, requests)
        }

    @Test
    fun `offline production quality upgrade keeps the sharp matching copy`(): Unit =
        runTest {
            staleEntry(HOST)
            val small = TabIcon.Image(BitmapPainter(artwork(16).toComposeImageBitmap()))
            val upgraded =
                upgradeCachedFavicon(
                    URL,
                    small,
                    NOW,
                    loadCandidate = { HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(it), dir) },
                    refresh = { HighQualityFaviconService.hostIcon(it, NOW, dir) { _, _ -> FaviconFetch.NoAnswer } },
                )
            assertEquals(128f, upgraded.painter.intrinsicSize.width)
        }

    @Test
    fun `local names and IP literals never reach the third party service`(): Unit =
        runTest {
            for (host in listOf(
                "localhost",
                "server",
                "127.0.0.1",
                "192.168.1.2",
                "10.1.1.1",
                "169.254.1.1",
                "172.16.1.1",
                "8.8.8.8",
                "[::1]",
                "[fd00::1]",
                "build.local",
                "app.internal",
                "app.lan",
                "app.home",
                "app.corp",
                "app.intranet",
                "app.test",
                "app.invalid",
                "app.localhost",
                "app.localdomain",
                "app.example",
                "app.onion",
                "app.i2p",
            )) {
                assertNull(
                    HighQualityFaviconService.hostIcon("https://$host:3000/path", NOW, dir) { _, _ ->
                        error("non-public host was disclosed: $host")
                    },
                )
            }
        }

    @Test
    fun `local stale cached artwork remains usable without disclosing its host`(): Unit =
        runTest {
            staleEntry("app.internal")
            val icon =
                HighQualityFaviconService.hostIcon("https://app.internal/path", NOW, dir) { _, _ ->
                    error("local cache caused a lookup")
                }
            assertEquals(128f, assertNotNull(icon).painter.intrinsicSize.width)
        }

    @Test
    fun `public lookup sends only the sanitized hostname`(): Unit =
        runTest {
            var requested: String? = null
            HighQualityFaviconService.hostIcon(
                "https://user:password@www.example.com:443/path?secret=value#fragment",
                NOW,
                dir,
            ) { host, _ ->
                requested = host
                FaviconFetch.NoAnswer
            }
            assertEquals(HOST, requested)
            assertTrue(isPublicFaviconHost("xn--bcher-kva.de"))
            assertFalse(isPublicFaviconHost("example.com&secret=value"))
        }

    @Test
    fun `transient retry memory is bounded and does not stick after a clock rollback`() {
        repeat(FaviconRetryMemory.MAX_REMEMBERED + 10) { FaviconRetryMemory.record("host-$it.com", NOW + it) }
        assertFalse(FaviconRetryMemory.coolingDown("host-0.com", NOW))
        val latest = "host-${FaviconRetryMemory.MAX_REMEMBERED + 9}.com"
        assertTrue(FaviconRetryMemory.coolingDown(latest, NOW + 1_000))
        assertFalse(FaviconRetryMemory.coolingDown(latest, NOW - 1))
        assertFalse(FaviconRetryMemory.coolingDown(latest, NOW + 1_000 + FaviconRetryMemory.RETRY_DELAY_MS))
    }

    private fun staleEntry(host: String): File =
        File(dir, "${HqFaviconDiskCache.keyFor(host)}.png").also {
            ImageIO.write(artwork(128), "PNG", it)
            assertTrue(it.setLastModified(NOW - FaviconFreshness.MAX_CACHE_AGE_MS - 1))
        }

    private fun artwork(size: Int) =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                color = Color.BLUE
                fillRect(size / 4, size / 4, size / 2, size / 2)
                dispose()
            }
        }

    private companion object {
        const val HOST = "example.com"
        const val URL = "https://example.com/page"
        const val NOW = 1_700_000_000_000L
    }
}
