package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon

/**
 * Platform-specific favicon cache interface.
 * Desktop implementation uses file-based cache, other platforms return null.
 */
expect fun loadFaviconFromCache(cacheKey: String?): ai.rever.boss.plugin.api.TabIcon.Image?

/**
 * Load high-quality favicon for dashboard display.
 * Uses Google's favicon service for larger icons (128px).
 * Falls back to standard cache if unavailable.
 *
 * @param url The page URL to get favicon for
 * @param standardCacheKey The cache key from standard favicon cache (fallback)
 */
expect suspend fun loadHighQualityFavicon(url: String, standardCacheKey: String?): ai.rever.boss.plugin.api.TabIcon.Image?
