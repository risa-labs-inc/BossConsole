package ai.rever.boss.components.common

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
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
@Composable
fun rememberFaviconLoader(tabInfo: TabInfo): ai.rever.boss.plugin.api.TabIcon.Image? {
    // Extract faviconCacheKey - first check built-in FluckTabInfo, then try reflection for dynamic plugins
    val faviconCacheKey = when (tabInfo) {
        is FluckTabInfo -> tabInfo.faviconCacheKey
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

    // State to hold the loaded favicon
    var loadedFavicon by remember(faviconCacheKey) {
        mutableStateOf<ai.rever.boss.plugin.api.TabIcon.Image?>(null)
    }

    // Load favicon asynchronously on IO thread
    LaunchedEffect(faviconCacheKey) {
        if (faviconCacheKey != null) {
            loadedFavicon = withContext(Dispatchers.IO) {
                try {
                    ai.rever.boss.cache.loadFaviconFromCache(faviconCacheKey)
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
