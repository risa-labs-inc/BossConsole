package ai.rever.boss.updater

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.hours

/**
 * Manages fetching and caching of available BOSS versions from GitHub Releases.
 *
 * Caches version list for 1 hour to avoid GitHub API rate limiting:
 * - Unauthenticated: 60 requests/hour
 * - Authenticated: 5000 requests/hour
 *
 * Note: fetchVersions() is a suspend function called from composable scope,
 * so no dedicated coroutine scope is needed here.
 */
class VersionListManager(
    private val updateService: UpdateService,
) {
    private val logger = BossLogger.forComponent("VersionListManager")
    private val _versions = MutableStateFlow<List<VersionInfo>>(emptyList())
    val versions: StateFlow<List<VersionInfo>> = _versions

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var lastFetchTime: kotlin.time.Instant? = null
    private val cacheDuration = 1.hours

    /**
     * Fetches all available versions from GitHub Releases.
     * Uses cached data if available and not expired.
     */
    suspend fun fetchVersions(forceRefresh: Boolean = false) {
        if (_isLoading.value) return

        // Check cache validity
        val now =
            kotlin.time.Clock.System
                .now()
        val cacheValid =
            lastFetchTime?.let { lastFetch ->
                (now - lastFetch) < cacheDuration
            } ?: false

        if (!forceRefresh && cacheValid && _versions.value.isNotEmpty()) {
            return // Use cached data
        }

        _isLoading.value = true
        _error.value = null

        try {
            val allVersions =
                updateService
                    .fetchAllReleases()
                    .filter { !it.isDraft && !it.isPrerelease }
                    .sortedByDescending { it.version }

            _versions.value = allVersions
            lastFetchTime = now
        } catch (e: Exception) {
            _error.value = "Failed to fetch versions: ${e.message}"
            logger.warn(LogCategory.NETWORK, "Error fetching version list", error = e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Searches versions by version number.
     */
    fun searchVersions(query: String): List<VersionInfo> {
        if (query.isBlank()) return _versions.value

        return _versions.value.filter { version ->
            version.version.toString().contains(query, ignoreCase = true)
        }
    }

    /**
     * Clears the cache, forcing next fetch to get fresh data.
     */
    fun clearCache() {
        lastFetchTime = null
        _versions.value = emptyList()
    }

    /**
     * Clean up resources. Currently a no-op, kept for API consistency
     * and future cleanup needs.
     */
    fun cleanup() {
        // No cleanup needed - fetchVersions() uses caller's coroutine scope
    }
}
