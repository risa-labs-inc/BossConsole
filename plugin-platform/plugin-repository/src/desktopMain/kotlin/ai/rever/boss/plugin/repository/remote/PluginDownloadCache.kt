package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.pathutils.BossDirectories
import java.io.File
import java.security.MessageDigest

/**
 * Local cache for downloaded plugin JARs.
 *
 * Caches downloaded JARs to avoid re-downloading plugins that haven't changed.
 * Uses SHA-256 verification to ensure cache integrity.
 *
 * @param cacheDir Directory to store cached JARs (defaults to ~/.boss/plugin-cache)
 */
class PluginDownloadCache(
    private val cacheDir: File = BossDirectories.resolve("plugin-cache"),
) {
    private val logger = BossLogger.forComponent("PluginDownloadCache")

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
            logger.debug(LogCategory.SYSTEM, "Created plugin cache directory", mapOf("path" to cacheDir.absolutePath))
        }
    }

    /**
     * Get a cached JAR if it exists and matches the expected SHA-256 hash.
     *
     * @param pluginId The plugin ID
     * @param version The plugin version
     * @param expectedSha256 The expected SHA-256 hash (must be non-blank)
     * @return The cached file if valid, null otherwise
     */
    fun getCachedJar(
        pluginId: String,
        version: String,
        expectedSha256: String,
    ): File? {
        val cachedFile = getCacheFile(pluginId, version)

        if (!cachedFile.exists()) {
            return null
        }

        // Verify SHA-256. A blank or mismatching hash invalidates the cache —
        // we never load unhashed or tampered JARs from cache.
        val actualSha256 = cachedFile.sha256()
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            logger.warn(
                LogCategory.SYSTEM,
                "Cached JAR SHA-256 mismatch, removing",
                mapOf(
                    "pluginId" to pluginId,
                    "version" to version,
                    "expected" to expectedSha256.take(16) + "...",
                    "actual" to actualSha256.take(16) + "...",
                ),
            )
            cachedFile.delete()
            return null
        }

        logger.debug(
            LogCategory.SYSTEM,
            "Found valid cached JAR",
            mapOf(
                "pluginId" to pluginId,
                "version" to version,
            ),
        )

        return cachedFile
    }

    /**
     * Cache a downloaded JAR file.
     *
     * @param pluginId The plugin ID
     * @param version The plugin version
     * @param sourceFile The downloaded JAR file to cache
     * @return The cached file
     */
    fun cacheJar(
        pluginId: String,
        version: String,
        sourceFile: File,
    ): File {
        val cachedFile = getCacheFile(pluginId, version)

        // Ensure parent directory exists
        cachedFile.parentFile?.mkdirs()

        // Copy to cache
        sourceFile.copyTo(cachedFile, overwrite = true)

        logger.debug(
            LogCategory.SYSTEM,
            "Cached plugin JAR",
            mapOf(
                "pluginId" to pluginId,
                "version" to version,
                "size" to cachedFile.length(),
            ),
        )

        return cachedFile
    }

    /**
     * Remove a specific cached JAR.
     *
     * @param pluginId The plugin ID
     * @param version The plugin version
     * @return true if removed, false if not found
     */
    fun removeCachedJar(
        pluginId: String,
        version: String,
    ): Boolean {
        val cachedFile = getCacheFile(pluginId, version)

        if (!cachedFile.exists()) {
            return false
        }

        val deleted = cachedFile.delete()

        if (deleted) {
            logger.debug(
                LogCategory.SYSTEM,
                "Removed cached JAR",
                mapOf(
                    "pluginId" to pluginId,
                    "version" to version,
                ),
            )

            // Clean up empty parent directory
            val pluginDir = cachedFile.parentFile
            if (pluginDir?.listFiles()?.isEmpty() == true) {
                pluginDir.delete()
            }
        }

        return deleted
    }

    /**
     * Remove all cached JARs for a plugin (all versions).
     *
     * @param pluginId The plugin ID
     * @return Number of versions removed
     */
    fun removeAllVersions(pluginId: String): Int {
        val pluginDir = File(cacheDir, sanitizePluginId(pluginId))

        if (!pluginDir.exists()) {
            return 0
        }

        val files = pluginDir.listFiles() ?: return 0
        val count = files.size

        pluginDir.deleteRecursively()

        logger.info(
            LogCategory.SYSTEM,
            "Removed all cached versions",
            mapOf(
                "pluginId" to pluginId,
                "count" to count,
            ),
        )

        return count
    }

    /**
     * Clear the entire cache.
     *
     * @return Number of files removed
     */
    fun clearCache(): Int {
        val files = cacheDir.walkTopDown().filter { it.isFile }.toList()
        val count = files.size

        cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        logger.info(
            LogCategory.SYSTEM,
            "Cleared plugin cache",
            mapOf(
                "filesRemoved" to count,
            ),
        )

        return count
    }

    /**
     * Get the total size of the cache in bytes.
     */
    fun getCacheSize(): Long =
        cacheDir
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    /**
     * Get the number of cached plugins.
     */
    fun getCachedPluginCount(): Int = cacheDir.listFiles()?.count { it.isDirectory } ?: 0

    /**
     * Get the number of cached JAR files.
     */
    fun getCachedFileCount(): Int =
        cacheDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .count()

    /**
     * List all cached plugins with their versions.
     *
     * @return Map of plugin ID to list of cached versions
     */
    fun listCachedPlugins(): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()

        cacheDir.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
            val versions =
                pluginDir
                    .listFiles()
                    ?.filter { it.isFile && it.extension == "jar" }
                    ?.map { it.nameWithoutExtension.substringAfterLast("_") }
                    ?: emptyList()

            if (versions.isNotEmpty()) {
                result[pluginDir.name] = versions
            }
        }

        return result
    }

    /**
     * Clean up old cache entries.
     * Removes cache entries older than the specified number of days.
     *
     * @param maxAgeDays Maximum age in days (default: 30)
     * @return Number of files removed
     */
    fun cleanOldEntries(maxAgeDays: Int = 30): Int {
        val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        var removed = 0

        cacheDir
            .walkTopDown()
            .filter { it.isFile && it.lastModified() < cutoffTime }
            .forEach { file ->
                if (file.delete()) {
                    removed++
                }
            }

        // Clean up empty directories
        cacheDir
            .walkBottomUp()
            .filter { it.isDirectory && it != cacheDir && (it.listFiles()?.isEmpty() == true) }
            .forEach { it.delete() }

        if (removed > 0) {
            logger.info(
                LogCategory.SYSTEM,
                "Cleaned old cache entries",
                mapOf(
                    "removed" to removed,
                    "maxAgeDays" to maxAgeDays,
                ),
            )
        }

        return removed
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun getCacheFile(
        pluginId: String,
        version: String,
    ): File {
        val sanitizedId = sanitizePluginId(pluginId)
        return File(cacheDir, "$sanitizedId/${sanitizedId}_$version.jar")
    }

    private fun sanitizePluginId(pluginId: String): String {
        // Replace characters that might cause issues in file paths
        return pluginId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
