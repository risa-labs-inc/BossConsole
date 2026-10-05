package ai.rever.boss.components.common

import ai.rever.boss.cache.faviconLookupHost
import ai.rever.boss.cache.faviconLookupOrigin
import ai.rever.boss.cache.loadCachedHighQualityFavicon
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import androidx.compose.runtime.*

/**
 * The standard-cache key for [tabInfo], or null when it has none.
 *
 * Separate from [rememberFaviconLoader] because a caller that resolves through
 * `loadHighQualityFavicon` wants the key, not a second decode of the same file - and a plain
 * `as? FluckTabInfo` is not the same answer: a dynamic plugin tab carries its key on a class this
 * module cannot see. Platform getters are cached by class; their values are read each composition
 * so snapshot-backed properties on a retained tab are observed too.
 */
@Composable
fun rememberFaviconCacheKey(tabInfo: TabInfo): String? =
    when (tabInfo) {
        is FluckTabInfo -> tabInfo.faviconCacheKey
        else -> dynamicFaviconCacheKey(tabInfo)
    }

@Composable
fun rememberFaviconLoader(tabInfo: TabInfo): TabIcon.Image? {
    val faviconCacheKey = rememberFaviconCacheKey(tabInfo)
    val pageUrl = faviconPageUrl(tabInfo)
    val host = faviconLookupHost(pageUrl)
    val origin = faviconLookupOrigin(pageUrl)

    var loadedFavicon by remember(origin, faviconCacheKey) {
        mutableStateOf<TabIcon.Image?>(null)
    }

    // The resolver performs IO off the UI thread and preserves cancellation. A sharper cached
    // icon is used only when its artwork matches the page's own favicon.
    LaunchedEffect(origin, faviconCacheKey) {
        if (host != null || faviconCacheKey != null) {
            loadedFavicon = loadCachedHighQualityFavicon(pageUrl, faviconCacheKey)
        }
    }

    return loadedFavicon
}

/** Only browser tabs have a page URL; a file or terminal must never trigger a host lookup. */
internal fun faviconPageUrl(tabInfo: TabInfo): String? =
    when {
        tabInfo is FluckTabInfo -> {
            tabInfo.currentUrl
        }

        tabInfo.typeId == FluckTabType.typeId -> {
            dynamicBrowserFaviconUrl(tabInfo)
        }

        else -> {
            null
        }
    }?.takeIf { it.isNotBlank() }

internal expect fun dynamicFaviconCacheKey(tabInfo: TabInfo): String?

internal expect fun dynamicBrowserFaviconUrl(tabInfo: TabInfo): String?
