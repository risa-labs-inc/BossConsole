package ai.rever.boss.components.common

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val faviconLogger = BossLogger.forComponent("FaviconLoader")

/**
 * Hook that loads favicon from cache for a given tab
 * Returns loaded favicon or null if unavailable/error
 *
 * Handles:
 * - Async loading on IO thread (non-blocking)
 * - Error handling with logging
 * - Efficient caching with remember
 */

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
fun rememberFaviconLoader(tabInfo: TabInfo): ai.rever.boss.plugin.api.TabIcon.Image? {
    val faviconCacheKey = rememberFaviconCacheKey(tabInfo)

    // State to hold the loaded favicon
    var loadedFavicon by remember(faviconCacheKey) {
        mutableStateOf<ai.rever.boss.plugin.api.TabIcon.Image?>(null)
    }

    // Load favicon asynchronously on IO thread
    LaunchedEffect(faviconCacheKey) {
        if (faviconCacheKey != null) {
            loadedFavicon =
                withContext(Dispatchers.IO) {
                    try {
                        ai.rever.boss.cache
                            .loadFaviconFromCache(faviconCacheKey)
                    } catch (e: Exception) {
                        faviconLogger.debug(
                            LogCategory.BROWSER,
                            "Error loading favicon",
                            mapOf("key" to faviconCacheKey, "error" to e.toString()),
                        )
                        null
                    }
                }
        } else {
            loadedFavicon = null
        }
    }

    return loadedFavicon
}
