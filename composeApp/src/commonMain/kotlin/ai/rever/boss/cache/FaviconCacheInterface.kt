package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon

/**
 * Platform-specific favicon cache interface.
 * Desktop implementation uses file-based cache, other platforms return null.
 */
expect fun loadFaviconFromCache(cacheKey: String?): ai.rever.boss.plugin.api.TabIcon.Image?

/**
 * The icon for a page: its own cached favicon if there is one, else Google's guess about its host.
 *
 * That order is the point - see `HighQualityFaviconService` for why a guess about a host must not
 * overwrite a per-page icon captured from the tab itself. Do NOT read the standard cache yourself
 * first; this already does, on an IO dispatcher.
 *
 * @param url the page URL, or null for a tab that is not a page (a terminal, a file), which has no
 *   host to guess from
 * @param standardCacheKey the key into the standard favicon cache, i.e. the page's own icon
 */
expect suspend fun loadHighQualityFavicon(
    url: String?,
    standardCacheKey: String?,
): ai.rever.boss.plugin.api.TabIcon.Image?
