package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * File-based cache for browser tab favicons.
 * Stores favicons as PNG files in the application's cache directory.
 */
object FaviconCache {
    private val logger = BossLogger.forComponent("FaviconCache")
    private const val MAX_FAVICON_SIZE_BYTES = 100 * 1024 // 100KB limit
    private const val CACHE_DIR_NAME = "favicon-cache"

    private val cacheDir: File by lazy {
        val appCacheDir = BossDirectories.resolve("cache/$CACHE_DIR_NAME")
        appCacheDir.mkdirs()
        appCacheDir
    }

    /**
     * Generates a cache key from a URL by creating an MD5 hash.
     * This ensures consistent, filesystem-safe filenames.
     */
    fun generateCacheKey(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(url.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Saves a favicon to the cache, replacing any earlier icon for the same URL.
     *
     * @param url The URL associated with this favicon (used to generate cache key)
     * @param imageBitmap The favicon ImageBitmap to cache
     * @return The cache key, or null if the favicon exceeds the size limit and nothing usable is
     *   already cached for [url]
     */
    fun saveFavicon(
        url: String,
        imageBitmap: ImageBitmap,
    ): String? {
        val cacheKey = generateCacheKey(url)
        val cacheFile = File(cacheDir, "$cacheKey.png")
        var tempFile: File? = null
        try {
            val bufferedImage = imageBitmap.toAwtImage()

            // Written to a temp file first so the size limit is checked against the encoded PNG,
            // and so a failure part-way through encoding cannot leave a torn icon in the cache.
            tempFile = File.createTempFile("favicon_", ".png", cacheDir)
            ImageIO.write(bufferedImage, "PNG", tempFile)

            if (tempFile.length() > MAX_FAVICON_SIZE_BYTES) {
                logger.debug(
                    LogCategory.BROWSER,
                    "Favicon too large, skipping cache",
                    mapOf(
                        "size" to tempFile.length(),
                        "maxSize" to MAX_FAVICON_SIZE_BYTES,
                    ),
                )
                return existingKeyOrNull(cacheKey, cacheFile)
            }

            // NOT File.renameTo: that does not overwrite an existing file on Windows, so every
            // favicon after the first for a given URL failed there - and the cache outlives the
            // process, so "the first" was usually some previous run. See File.atomicMoveFrom.
            cacheFile.atomicMoveFrom(tempFile)
            return cacheKey
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error saving favicon", error = e)
            return existingKeyOrNull(cacheKey, cacheFile)
        } finally {
            // No-op once the move took it away; cleans up every failure path.
            tempFile?.delete()
        }
    }

    /**
     * The key when something is already cached for it, else null.
     *
     * Returning null costs the tab its icon rather than merely leaving it stale: it reaches
     * `updateFavicon(null)`, which resets the tab to the default globe. So a save that could not
     * improve on the cache reports what the cache still holds.
     */
    private fun existingKeyOrNull(
        cacheKey: String,
        cacheFile: File,
    ): String? = cacheKey.takeIf { cacheFile.exists() }

    /**
     * Loads a favicon from the cache.
     * @param cacheKey The cache key generated from the URL
     * @return ai.rever.boss.plugin.api.TabIcon.Image if found, null if not found or on error
     */
    fun loadFavicon(cacheKey: String): ai.rever.boss.plugin.api.TabIcon.Image? {
        try {
            val cacheFile = File(cacheDir, "$cacheKey.png")

            if (!cacheFile.exists()) {
                return null
            }

            // Read PNG file
            val bufferedImage = ImageIO.read(cacheFile)
            if (bufferedImage == null) {
                logger.warn(LogCategory.BROWSER, "Failed to read cached favicon", mapOf("cacheKey" to cacheKey))
                return null
            }

            // Convert to Compose ImageBitmap
            val imageBitmap = bufferedImage.toComposeImageBitmap()
            val painter = BitmapPainter(imageBitmap)
            return ai.rever.boss.plugin.api.TabIcon
                .Image(painter)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error loading favicon", mapOf("cacheKey" to cacheKey), error = e)
            return null
        }
    }

    /**
     * Clears all cached favicons.
     * Useful for cleanup or troubleshooting.
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error clearing cache", error = e)
        }
    }

    /**
     * Removes stale cache entries older than the specified number of days.
     * @param daysOld Remove files older than this many days (default: 30)
     */
    fun cleanupStaleEntries(daysOld: Int = 30) {
        try {
            val cutoffTime = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
            var removedCount = 0

            cacheDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                    removedCount++
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error cleaning up cache", error = e)
        }
    }

    /**
     * Gets the total size of the favicon cache in bytes.
     */
    fun getCacheSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Gets the number of cached favicons.
     */
    fun getCacheCount(): Int = cacheDir.listFiles()?.size ?: 0
}
