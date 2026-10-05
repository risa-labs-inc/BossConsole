package ai.rever.boss.components.common

import ai.rever.boss.cache.FaviconCache
import ai.rever.boss.cache.HqFaviconDiskCache
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.pathutils.BossDirectories
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FaviconLoaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tab rows use matching sharp icons and refresh when the current URL changes`(): Unit =
        runBlocking {
            val firstUrl = "https://favicon-first.test/page"
            val secondUrl = "https://favicon-second.test/page"
            val cacheKey = assertNotNull(FaviconCache.saveFavicon(firstUrl, artwork(16).toComposeImageBitmap()))
            val firstKey = HqFaviconDiskCache.keyFor("favicon-first.test")
            val secondKey = HqFaviconDiskCache.keyFor("favicon-second.test")
            HqFaviconDiskCache.save(firstKey, artwork(64))
            HqFaviconDiskCache.save(secondKey, artwork(128))
            val tab =
                mutableStateOf<TabInfo>(
                    DynamicBrowserTab(firstUrl, secondUrl, cacheKey),
                )
            val rendered = AtomicReference<TabIcon.Image?>()
            try {
                compose.setContent {
                    val icon = rememberFaviconLoader(tab.value)
                    SideEffect { rendered.set(icon) }
                }
                // The initial URL deliberately points at the other host. Using it instead of
                // currentUrl would show 128px; bypassing the HQ resolver would show 16px.
                compose.waitUntil(5_000) {
                    rendered
                        .get()
                        ?.painter
                        ?.intrinsicSize
                        ?.width == 64f
                }
                compose.runOnIdle {
                    // Reuse the page cache key to prove URL changes also invalidate resolution.
                    tab.value = DynamicBrowserTab(secondUrl, firstUrl, cacheKey)
                }
                compose.waitUntil(5_000) {
                    rendered
                        .get()
                        ?.painter
                        ?.intrinsicSize
                        ?.width == 128f
                }
            } finally {
                File(BossDirectories.resolve("cache/favicon-cache"), "$cacheKey.png").delete()
                HqFaviconDiskCache.delete(firstKey)
                HqFaviconDiskCache.delete(secondKey)
            }
        }

    @Test
    fun `bundled and dynamic browsers resolve their current page rather than the initial URL`() {
        val current = "https://current.test/page"
        val initial = "https://initial.test/page"
        val bundled =
            FluckTabInfo(
                id = "bundled",
                typeId = TabTypeId("fluck"),
                _title = "Page",
                url = initial,
                _currentUrl = current,
            )
        assertEquals(current, faviconPageUrl(bundled))
        assertEquals(current, faviconPageUrl(DynamicBrowserTab(current, initial, null)))
        assertEquals(initial, faviconPageUrl(InitialUrlBrowserTab(initial)))
    }

    @Test
    fun `nonbrowser tabs never supply a URL for host lookup`() {
        val terminal = DynamicBrowserTab("https://private.test", "https://initial.test", null, TabTypeId("terminal"))
        assertNull(faviconPageUrl(terminal))
    }

    @Test
    fun `retained plugin tabs observe both URL and page artwork changes`() {
        val firstUrl = "https://retained-first.test/page"
        val secondUrl = "https://retained-second.test/page"
        val (firstKey, secondKey) =
            runBlocking {
                val first = assertNotNull(FaviconCache.saveFavicon(firstUrl, artwork(16).toComposeImageBitmap()))
                val red = artwork(32, Color.RED)
                val second = assertNotNull(FaviconCache.saveFavicon(secondUrl, red.toComposeImageBitmap()))
                HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor("retained-first.test"), artwork(64))
                HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor("retained-second.test"), artwork(128))
                first to second
            }
        val tab = RetainedBrowserTab(firstUrl, firstKey)
        val rendered = AtomicReference<TabIcon.Image?>()
        try {
            compose.setContent {
                val icon = rememberFaviconLoader(tab)
                SideEffect { rendered.set(icon) }
            }
            compose.waitUntil(5_000) {
                rendered
                    .get()
                    ?.painter
                    ?.intrinsicSize
                    ?.width == 64f
            }
            compose.runOnIdle { tab.currentUrl = secondUrl }
            compose.waitUntil(5_000) {
                rendered
                    .get()
                    ?.painter
                    ?.intrinsicSize
                    ?.width == 128f
            }
            compose.runOnIdle { tab.faviconCacheKey = secondKey }
            compose.waitUntil(5_000) {
                rendered
                    .get()
                    ?.painter
                    ?.intrinsicSize
                    ?.width == 32f
            }
        } finally {
            for (key in listOf(firstKey, secondKey)) {
                File(BossDirectories.resolve("cache/favicon-cache"), "$key.png").delete()
            }
            runBlocking {
                for (host in listOf("retained-first.test", "retained-second.test")) {
                    HqFaviconDiskCache.delete(HqFaviconDiskCache.keyFor(host))
                }
            }
        }
    }

    @Test
    fun `missing failing and unsupported current getters never resurrect the initial page`() {
        assertNull(faviconPageUrl(GetterlessBrowserTab()))
        for (value in listOf(null, "", " ", 42)) assertNull(faviconPageUrl(UnsupportedBrowserTab(value)))
        assertNull(faviconPageUrl(FailingBrowserTab(IllegalStateException("getter failed"))))
        assertNull(faviconPageUrl(FailingBrowserTab(NoClassDefFoundError("optional dependency"))))
        assertFailsWith<CancellationException> { faviconPageUrl(FailingBrowserTab(CancellationException())) }
        assertEquals(
            "https://snapshot.test",
            faviconPageUrl(UnsupportedBrowserTab(mutableStateOf("https://snapshot.test"))),
        )
    }

    @Test
    fun `host identity ignores path query and credential changes`() {
        assertEquals(
            ai.rever.boss.cache
                .faviconLookupHost("https://example.com/first?one=1"),
            ai.rever.boss.cache
                .faviconLookupHost("https://user:password@www.example.com:443/second?two=2#section"),
        )
        assertNull(
            ai.rever.boss.cache
                .faviconLookupHost("about:blank"),
        )
    }

    open class GetterlessBrowserTab : TabInfo {
        override val id = "getterless"
        override val typeId = TabTypeId("fluck")
        override val title = "Page"
        override val icon = Icons.Outlined.Language
    }

    class RetainedBrowserTab(
        url: String,
        cacheKey: String,
    ) : GetterlessBrowserTab() {
        var currentUrl by mutableStateOf(url)
        var faviconCacheKey by mutableStateOf<String?>(cacheKey)
    }

    class UnsupportedBrowserTab(
        val currentUrl: Any?,
    ) : GetterlessBrowserTab() {
        val initialUrl = "https://initial.test"
    }

    class FailingBrowserTab(
        private val failure: Throwable,
    ) : GetterlessBrowserTab() {
        val currentUrl: String get() = throw failure
        val initialUrl = "https://initial.test"
    }

    class DynamicBrowserTab(
        val currentUrl: String,
        val initialUrl: String,
        val faviconCacheKey: String?,
        override val typeId: TabTypeId = TabTypeId("fluck"),
    ) : TabInfo {
        override val id = "dynamic"
        override val title = "Page"
        override val icon = Icons.Outlined.Language
    }

    class InitialUrlBrowserTab(
        val initialUrl: String,
    ) : TabInfo {
        override val id = "legacy"
        override val typeId = TabTypeId("fluck")
        override val title = "Page"
        override val icon = Icons.Outlined.Language
    }

    private fun artwork(
        size: Int,
        colour: Color = Color.BLUE,
    ): BufferedImage =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                color = colour
                fillRect(size / 4, size / 4, size / 2, size / 2)
                dispose()
            }
        }
}
