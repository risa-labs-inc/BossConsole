package ai.rever.boss.components.common

import ai.rever.boss.cache.loadHighQualityFavicon
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.*

private val faviconLogger = BossLogger.forComponent("FaviconLoader")

/**
 * The standard-cache key for [tabInfo], or null when it has none.
 *
 * Separate from [rememberFaviconLoader] because a caller that resolves through
 * `loadHighQualityFavicon` wants the key, not a second decode of the same file - and a plain
 * `as? FluckTabInfo` is not the same answer: a dynamic plugin tab carries its key on a class this
 * module cannot see, which is what the reflection branch is for.
 */
@Composable
fun rememberFaviconCacheKey(tabInfo: TabInfo): String? =
    // Actually remembered, which the inline version this was extracted from was not: the else
    // branch is kotlin-reflect over every member of the tab's class, and this runs once per tab in
    // the tab bar and once per row in the capture picker, where hover and selection recompose.
    remember(tabInfo) {
        when (tabInfo) {
            is FluckTabInfo -> {
                tabInfo.faviconCacheKey
            }

            else -> {
                // Try reflection for dynamic plugin tabs that have faviconCacheKey property
                try {
                    val property = tabInfo::class.members.find { it.name == "faviconCacheKey" }
                    property?.call(tabInfo) as? String
                } catch (e: Exception) {
                    faviconLogger.debug(
                        LogCategory.BROWSER,
                        "faviconCacheKey reflection probe failed - tab has no favicon",
                        mapOf("error" to e.toString()),
                    )
                    null
                }
            }
        }
    }

@Composable
fun rememberFaviconLoader(tabInfo: TabInfo): TabIcon.Image? {
    val faviconCacheKey = rememberFaviconCacheKey(tabInfo)
    val pageUrl = remember(tabInfo) { faviconPageUrl(tabInfo) }

    var loadedFavicon by remember(pageUrl, faviconCacheKey) {
        mutableStateOf<TabIcon.Image?>(null)
    }

    // The resolver performs IO off the UI thread and preserves cancellation. A sharper cached
    // icon is used only when its artwork matches the page's own favicon.
    LaunchedEffect(pageUrl, faviconCacheKey) {
        if (pageUrl != null || faviconCacheKey != null) {
            loadedFavicon = loadHighQualityFavicon(pageUrl, faviconCacheKey)
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

        tabInfo.typeId.typeId == "fluck" -> {
            // The browser plugin's concrete tab type belongs to another classloader.
            try {
                val properties = tabInfo::class.members
                properties.firstOrNull { it.name == "currentUrl" }?.call(tabInfo) as? String
                    ?: properties.firstOrNull { it.name == "initialUrl" }?.call(tabInfo) as? String
            } catch (e: Exception) {
                faviconLogger.debug(
                    LogCategory.BROWSER,
                    "Browser favicon URL reflection probe failed",
                    mapOf("error" to e.toString()),
                )
                null
            }
        }

        else -> {
            null
        }
    }
