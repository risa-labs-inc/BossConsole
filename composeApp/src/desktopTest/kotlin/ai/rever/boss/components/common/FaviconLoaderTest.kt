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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
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

    private fun artwork(size: Int): BufferedImage =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                color = Color.BLUE
                fillRect(size / 4, size / 4, size / 2, size / 2)
                dispose()
            }
        }
}
