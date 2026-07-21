package ai.rever.boss.cache

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.pathutils.BossDirectories
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
     * Saves a favicon to the cache.
     * @param url The URL associated with this favicon (used to generate cache key)
     * @param imageBitmap The favicon ImageBitmap to cache
     * @return The cache key if successful, null if the favicon exceeds size limit or on error
     */
    fun saveFavicon(url: String, imageBitmap: ImageBitmap): String? {
        try {
            val cacheKey = generateCacheKey(url)
            val cacheFile = File(cacheDir, "$cacheKey.png")

            // Convert to AWT BufferedImage
            val bufferedImage = imageBitmap.toAwtImage()

            // Check size before writing
            val tempFile = File.createTempFile("favicon_", ".png", cacheDir)
            ImageIO.write(bufferedImage, "PNG", tempFile)

            if (tempFile.length() > MAX_FAVICON_SIZE_BYTES) {
                tempFile.delete()
                logger.debug(LogCategory.BROWSER, "Favicon too large, skipping cache", mapOf("size" to tempFile.length(), "maxSize" to MAX_FAVICON_SIZE_BYTES))
                return null
            }

            // Move to final location
            val renamed = tempFile.renameTo(cacheFile)
            if (!renamed) {
                tempFile.delete()
                logger.warn(LogCategory.BROWSER, "Error saving favicon: rename failed")
                return null
            }
            return cacheKey
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error saving favicon", error = e)
            return null
        }
    }

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
            return ai.rever.boss.plugin.api.TabIcon.Image(painter)
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
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Gets the number of cached favicons.
     */
    fun getCacheCount(): Int {
        return cacheDir.listFiles()?.size ?: 0
    }
}
