package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon

internal actual fun faviconLookupHost(url: String?): String? = FaviconHost.of(url)

internal actual fun faviconLookupOrigin(url: String?): String? = OriginalFaviconSource.originFor(url)

/**
 * Desktop implementation of favicon cache loading.
 */
actual fun loadFaviconFromCache(cacheKey: String?): ai.rever.boss.plugin.api.TabIcon.Image? {
    if (cacheKey == null) return null
    return FaviconCache.loadFavicon(cacheKey)
}

/**
 * Desktop implementation of page favicon resolution.
 */
actual suspend fun loadHighQualityFavicon(
    url: String?,
    standardCacheKey: String?,
): ai.rever.boss.plugin.api.TabIcon.Image? = HighQualityFaviconService.getHighQualityFavicon(url, standardCacheKey)

actual suspend fun loadHighQualityCardFavicon(
    url: String?,
    standardCacheKey: String?,
): ai.rever.boss.plugin.api.TabIcon.Image? = resolveHighQualityCardFavicon(url, standardCacheKey)

internal actual suspend fun loadCachedHighQualityFavicon(
    url: String?,
    standardCacheKey: String?,
): TabIcon.Image? = resolveCachedHighQualityFavicon(url, standardCacheKey)

/** Non-suspending sources can only read artwork; stale files remain usable without refreshing. */
internal suspend fun resolveCachedHighQualityFavicon(
    url: String?,
    standardCacheKey: String?,
    pageIcon: (String?) -> TabIcon.Image? = { it?.let(FaviconCache::loadFavicon) },
    artwork: (String) -> CachedFavicon? = { pageUrl ->
        FaviconHost.of(pageUrl)?.let { loadFaviconArtwork(it, origin = pageUrl) }
    },
): TabIcon.Image? =
    HighQualityFaviconService.resolve(
        url,
        standardCacheKey,
        pageIcon = pageIcon,
        hostGuess = { pageUrl -> pageUrl?.let(artwork)?.icon },
        qualityUpgrade = { pageUrl, page -> sharperMatchingFavicon(page, pageUrl?.let(artwork)?.icon) },
    )
