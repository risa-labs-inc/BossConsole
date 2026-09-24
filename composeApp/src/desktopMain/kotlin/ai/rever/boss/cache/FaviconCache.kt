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
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * File-based cache for browser tab favicons.
 * Stores favicons as PNG files in the application's cache directory.
 */
@Suppress("TooManyFunctions")
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
    ): String? = saveFavicon(url, imageBitmap, cacheDir)

    /**
     * [saveFavicon] against an explicit directory, so the branch that decides whether a tab keeps
     * its icon can be tested without writing to the developer's real `~/.boss` cache.
     *
     * **Must not throw.** Everything is inside the `try`, including the cache-key hash and the
     * cache-file path: the caller is a JxBrowser event listener, and while it happens to be
     * wrapped today, that is not this function's guarantee to spend.
     */
    internal fun saveFavicon(
        url: String,
        imageBitmap: ImageBitmap,
        dir: File,
    ): String? {
        var cacheFile: File? = null
        var tempFile: File? = null
        return try {
            val cacheKey = generateCacheKey(url)
            cacheFile = File(dir, "$cacheKey.png")
            val bufferedImage = imageBitmap.toAwtImage()

            // Written to a temp file first so the size limit is checked against the encoded PNG,
            // and so a failure part-way through encoding cannot leave a torn icon in the cache.
            tempFile = File.createTempFile("favicon_", ".png", dir)
            ImageIO.write(bufferedImage, "PNG", tempFile)

            if (tempFile.length() > MAX_FAVICON_SIZE_BYTES) {
                logger.debug(
                    LogCategory.BROWSER,
                    "Favicon too large, skipping cache",
                    mapOf(
                        "size" to tempFile.length(),
                        "maxSize" to MAX_FAVICON_SIZE_BYTES,
                        "servedStaleCache" to cacheFile.exists(),
                    ),
                )
                existingKeyOrNull(cacheKey, cacheFile)
            } else {
                // NOT File.renameTo: that does not overwrite an existing file on Windows, so every
                // favicon after the first for a given URL failed there - and the cache outlives the
                // process, so "the first" was usually some previous run. See File.atomicMoveFrom.
                cacheFile.atomicMoveFrom(tempFile)
                cacheKey
            }
        } catch (e: Exception) {
            // servedStaleCache separates "favicons are stale" from "favicons are gone" in a log,
            // which is the distinction that made the Windows rename bug findable at all.
            logger.warn(
                LogCategory.BROWSER,
                "Error saving favicon",
                mapOf("servedStaleCache" to (cacheFile?.exists() ?: false)),
                error = e,
            )
            cacheFile?.let { existingKeyOrNull(generateCacheKeyOrNull(url), it) }
        } finally {
            // No-op once the move took it away; cleans up every failure path.
            tempFile?.delete()
        }
    }

    /** [generateCacheKey] for the failure path, where hashing may be the thing that failed. */
    private fun generateCacheKeyOrNull(url: String): String? =
        try {
            generateCacheKey(url)
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Could not hash favicon URL", mapOf("error" to e.toString()))
            null
        }

    /**
     * The key when something is already cached under it, else null.
     *
     * Returning null costs the tab its icon rather than merely leaving it stale: it reaches
     * `updateFavicon(null)`, which resets the tab to the default globe. So a save that could not
     * improve on the cache reports what the cache still holds.
     *
     * Note the stale icon is then pinned until something rewrites it — `cleanupStaleEntries` only
     * ages files out after 30 days — so a site that switches to a >100 KB icon keeps showing the
     * old one for a month. Accepted: an outdated favicon beats none.
     */
    private fun existingKeyOrNull(
        cacheKey: String?,
        cacheFile: File,
    ): String? = cacheKey?.takeIf { cacheFile.exists() }

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
        clearCacheIn(cacheDir)
    }

    /**
     * Removes stale cache entries older than the specified number of days.
     * @param daysOld Remove files older than this many days (default: 30)
     */
    fun cleanupStaleEntries(daysOld: Int = 30) {
        cleanupStaleEntriesIn(cacheDir, daysOld)
    }

    /**
     * Gets the total size of the favicon cache in bytes.
     */
    fun getCacheSize(): Long =
        cacheDir
            .listFiles()
            ?.filter { !Files.isSymbolicLink(it.toPath()) }
            ?.sumOf { it.length() } ?: 0L

    /**
     * Gets the number of cached favicons.
     */
    fun getCacheCount(): Int = cacheDir.listFiles()?.count { !Files.isSymbolicLink(it.toPath()) } ?: 0

    /**
     * Clears all entries inside [dir]. Symlinks are deliberately skipped: `File.delete`
     * follows symlinks, so a planted link would have its *target* deleted. Real cache
     * entries under the directory are still removed.
     */
    internal fun clearCacheIn(dir: File) {
        try {
            dir.listFiles()?.forEach { entry ->
                if (!Files.isSymbolicLink(entry.toPath())) {
                    entry.delete()
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error clearing cache", error = e)
        }
    }

    /**
     * Test seam: drives [clearCacheIn] against an explicit directory rather than the
     * production cache root, so a symlink planted in a temp directory can be observed.
     */
    @Suppress("unused")
    internal fun clearCacheInDirectoryForTest(dir: File) = clearCacheIn(dir)

    /**
     * Removes stale entries (older than [daysOld] days) inside [dir]. Symlinks are
     * skipped for the same reason as [clearCacheIn] - `File.delete` follows them, so
     * a sweep that did not check would delete whatever the link points at.
     */
    internal fun cleanupStaleEntriesIn(
        dir: File,
        daysOld: Int,
    ) {
        try {
            val cutoffTime = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)

            dir.listFiles()?.forEach { file ->
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error cleaning up cache", error = e)
        }
    }

    /**
     * Test seam: drives [cleanupStaleEntriesIn] against an explicit directory.
     */
    @Suppress("unused")
    internal fun cleanupStaleEntriesInDirectoryForTest(
        dir: File,
        daysOld: Int,
    ) = cleanupStaleEntriesIn(dir, daysOld)
}
