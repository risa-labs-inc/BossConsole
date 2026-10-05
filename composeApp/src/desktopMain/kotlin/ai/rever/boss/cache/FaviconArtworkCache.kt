package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import java.io.File

/** Prefer site-owned artwork; the old untagged Google cache remains a compatible fallback. */
internal fun loadFaviconArtwork(
    host: String,
    dir: File = HqFaviconDiskCache.defaultDir,
): CachedFavicon? {
    val original = HqFaviconDiskCache.loadOriginal(host, dir)
    return original ?: HqFaviconDiskCache.load(HqFaviconDiskCache.keyFor(host), dir)
}

internal class FaviconArtworkRefreshSources(
    val original: suspend (String, TabIcon.Image) -> TabIcon.Image? = { url, page ->
        OriginalFaviconSource.sharperIcon(url, page)
    },
    val google: suspend (String, Long) -> TabIcon.Image? = { url, now ->
        HighQualityFaviconService.hostIcon(url, nowMs = now, refreshSmallIcon = true)
    },
)

/** Expired site assets are refreshed from the site, never through Google's guess namespace. */
internal suspend fun refreshFaviconArtwork(
    url: String,
    page: TabIcon.Image,
    cached: CachedFavicon,
    nowMs: Long,
    sources: FaviconArtworkRefreshSources = FaviconArtworkRefreshSources(),
): TabIcon.Image? =
    if (cached.source == FaviconArtworkSource.SITE) {
        sources.original(url, page) ?: cached.icon
    } else {
        sources.google(url, nowMs)
    }
