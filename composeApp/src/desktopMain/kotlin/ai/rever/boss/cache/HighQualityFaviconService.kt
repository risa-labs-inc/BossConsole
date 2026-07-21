package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.pathutils.BossDirectories
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Service for fetching high-quality favicons for the dashboard.
 * Uses Google's favicon service to get larger icons (up to 128px).
 * Falls back to standard favicon cache if high-quality version unavailable.
 *
 * Performance optimizations:
 * - Async HTTP with Ktor client (non-blocking)
 * - Reduced timeouts (2.5s) for faster failure detection
 * - Concurrency limit (3 simultaneous fetches) to prevent network flooding
 * - LRU cache eviction to prevent unbounded growth
 * - Cache-first approach to minimize network requests
 */
object HighQualityFaviconService {
    private const val HQ_CACHE_DIR_NAME = "favicon-hq-cache"
    private const val ICON_SIZE = 128 // Request 128px icons from Google
    private const val REQUEST_TIMEOUT_MS = 2500L
    private const val MAX_CONCURRENT_FETCHES = 3
    private const val MAX_CACHE_SIZE = 200 // Maximum number of cached favicons
    private const val CACHE_EVICTION_COUNT = 50 // Number of items to evict when limit reached

    // Semaphore to limit concurrent network requests
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    // Mutex for thread-safe cache operations
    private val cacheMutex = Mutex()

    // Ktor HTTP client with connection pooling
    private val httpClient by lazy {
        HttpClient(CIO) {
            engine {
                requestTimeout = REQUEST_TIMEOUT_MS
                endpoint {
                    connectTimeout = REQUEST_TIMEOUT_MS
                    socketTimeout = REQUEST_TIMEOUT_MS
                }
            }
        }
    }

    private val cacheDir: File by lazy {
        val dir = BossDirectories.resolve("cache/$HQ_CACHE_DIR_NAME")
        dir.mkdirs()
        dir
    }

    /**
     * Get a high-quality favicon for a URL.
     * First checks HQ cache, then fetches from Google if needed.
     * Falls back to standard favicon cache if Google service fails.
     *
     * Uses semaphore to limit concurrent network requests to MAX_CONCURRENT_FETCHES.
     *
     * @param url The page URL to get favicon for
     * @param standardCacheKey The cache key from the standard favicon cache (fallback)
     * @return ai.rever.boss.plugin.api.TabIcon.Image if found, null otherwise
     */
    suspend fun getHighQualityFavicon(url: String, standardCacheKey: String?): ai.rever.boss.plugin.api.TabIcon.Image? {
        return withContext(Dispatchers.IO) {
            try {
                val domain = extractDomain(url) ?: return@withContext loadStandardFavicon(standardCacheKey)
                val cacheKey = generateCacheKey(domain)

                // Check HQ cache first (no semaphore needed for local cache)
                val cached = loadFromCache(cacheKey)
                if (cached != null) {
                    return@withContext cached
                }

                // Try to fetch from Google's favicon service (with concurrency limit)
                val fetched = fetchSemaphore.withPermit {
                    fetchFromGoogle(domain, cacheKey)
                }
                if (fetched != null) {
                    return@withContext fetched
                }

                // Fall back to standard favicon
                loadStandardFavicon(standardCacheKey)
            } catch (e: Exception) {
                loadStandardFavicon(standardCacheKey)
            }
        }
    }

    /**
     * Extract domain from URL.
     */
    private fun extractDomain(url: String): String? {
        return try {
            val withoutProtocol = url.removePrefix("https://").removePrefix("http://")
            withoutProtocol.substringBefore('/').substringBefore('?').removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generate cache key for domain.
     */
    private fun generateCacheKey(domain: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(domain.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Load favicon from HQ cache.
     * Updates file access time for LRU tracking.
     */
    private fun loadFromCache(cacheKey: String): ai.rever.boss.plugin.api.TabIcon.Image? {
        val cacheFile = File(cacheDir, "$cacheKey.png")
        if (!cacheFile.exists()) return null

        return try {
            // Touch file to update access time for LRU
            cacheFile.setLastModified(System.currentTimeMillis())

            val bufferedImage = ImageIO.read(cacheFile) ?: return null
            val imageBitmap = bufferedImage.toComposeImageBitmap()
            ai.rever.boss.plugin.api.TabIcon.Image(BitmapPainter(imageBitmap))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetch high-quality favicon from Google's service using async Ktor client.
     * URL format: https://www.google.com/s2/favicons?domain=example.com&sz=128
     */
    private suspend fun fetchFromGoogle(domain: String, cacheKey: String): ai.rever.boss.plugin.api.TabIcon.Image? {
        val googleUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=$ICON_SIZE"

        return try {
            val response = httpClient.get(googleUrl) {
                headers {
                    append(HttpHeaders.UserAgent, "Mozilla/5.0")
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val bytes = response.readRawBytes()
                val bufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

                if (bufferedImage != null && bufferedImage.width >= 32) {
                    // Evict old entries if cache is full
                    evictIfNeeded()

                    // Save to cache
                    val cacheFile = File(cacheDir, "$cacheKey.png")
                    ImageIO.write(bufferedImage, "PNG", cacheFile)

                    val imageBitmap = bufferedImage.toComposeImageBitmap()
                    ai.rever.boss.plugin.api.TabIcon.Image(BitmapPainter(imageBitmap))
                } else {
                    // Image too small, skip caching
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Evict oldest entries if cache exceeds MAX_CACHE_SIZE.
     * Uses file modification time for LRU ordering.
     */
    private suspend fun evictIfNeeded() {
        cacheMutex.withLock {
            val files = cacheDir.listFiles() ?: return

            if (files.size >= MAX_CACHE_SIZE) {
                // Sort by last modified (oldest first) and delete oldest entries
                val toDelete = files
                    .sortedBy { it.lastModified() }
                    .take(CACHE_EVICTION_COUNT)

                toDelete.forEach { file ->
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        // Ignore deletion errors
                    }
                }
            }
        }
    }

    /**
     * Load from standard favicon cache as fallback.
     */
    private fun loadStandardFavicon(cacheKey: String?): ai.rever.boss.plugin.api.TabIcon.Image? {
        if (cacheKey == null) return null
        return FaviconCache.loadFavicon(cacheKey)
    }

    /**
     * Clear the HQ favicon cache.
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // Ignore cache clearing errors
        }
    }

    /**
     * Get cache stats.
     */
    fun getCacheStats(): Pair<Int, Long> {
        val files = cacheDir.listFiles() ?: return Pair(0, 0L)
        return Pair(files.size, files.sumOf { it.length() })
    }

    /**
     * Cleanup resources when no longer needed.
     */
    fun close() {
        httpClient.close()
    }
}
