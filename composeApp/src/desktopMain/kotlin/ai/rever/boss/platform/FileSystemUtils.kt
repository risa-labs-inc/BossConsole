package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.revealInFileManager
import java.io.File
import java.io.IOException

private val logger = BossLogger.forComponent("FileSystemUtils")

/**
 * Cross-platform file system utilities for downloads.
 */
object FileSystemUtils {

    /**
     * Opens the file manager and reveals the specified file.
     * - macOS: Uses 'open -R' to reveal in Finder
     * - Windows: Uses 'explorer /select,' to select in Explorer
     * - Linux: Opens the parent directory in file manager (xdg-open)
     *
     * @param filePath Absolute path to the file to reveal
     */
    fun revealInFolder(filePath: String) {
        revealInFileManager(filePath)
    }

    /**
     * Opens the specified file with the system default application.
     *
     * @param filePath Absolute path to the file to open
     */
    fun openFile(filePath: String) {
        try {
            val osName = System.getProperty("os.name").lowercase()
            val file = File(filePath)

            if (!file.exists()) {
                logger.warn(LogCategory.FILE, "Cannot open file - does not exist", mapOf("path" to filePath))
                return
            }

            when {
                osName.contains("mac") -> {
                    Runtime.getRuntime().exec(arrayOf("open", file.absolutePath))
                }

                osName.contains("windows") -> {
                    Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", file.absolutePath))
                }

                osName.contains("linux") -> {
                    Runtime.getRuntime().exec(arrayOf("xdg-open", file.absolutePath))
                }

                else -> {
                    logger.warn(LogCategory.FILE, "Open file not supported on this OS", mapOf("os" to osName))
                }
            }
        } catch (e: IOException) {
            logger.warn(LogCategory.FILE, "Failed to open file", error = e)
        }
    }

    /**
     * Checks if there is sufficient disk space available for a download.
     * Includes a 100MB safety buffer.
     *
     * @param destinationPath Path where the file will be saved
     * @param requiredBytes Number of bytes needed for the download
     * @return true if sufficient space is available, false otherwise
     */
    fun hasSufficientDiskSpace(destinationPath: String, requiredBytes: Long): Boolean {
        return try {
            val file = File(destinationPath)
            val parentDir = file.parentFile ?: return true // Can't check, assume OK

            val usableSpace = parentDir.usableSpace
            val safetyBuffer = 100 * 1024 * 1024L // 100MB buffer

            usableSpace >= (requiredBytes + safetyBuffer)
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Error checking disk space", error = e)
            true // Assume OK if we can't check
        }
    }

    /**
     * Generates a unique file path by appending (1), (2), etc. if file already exists.
     * Example: "file.txt" -> "file (1).txt" -> "file (2).txt"
     *
     * @param directory Directory where file will be saved
     * @param fileName Original file name
     * @return Absolute path to a unique file (may be the original if no collision)
     */
    fun generateUniqueFilePath(directory: String, fileName: String): String {
        val dir = File(directory)

        // Ensure directory exists
        if (!dir.exists()) {
            dir.mkdirs()
        }

        var file = File(dir, fileName)

        // If no collision, return original path
        if (!file.exists()) {
            return file.absolutePath
        }

        // Handle collision with incrementing counter
        val extension = file.extension
        val nameWithoutExtension = file.nameWithoutExtension
        var counter = 1

        do {
            val newName = if (extension.isNotEmpty()) {
                "$nameWithoutExtension ($counter).$extension"
            } else {
                "$nameWithoutExtension ($counter)"
            }
            file = File(dir, newName)
            counter++
        } while (file.exists() && counter < 1000) // Prevent infinite loop

        return file.absolutePath
    }

    /**
     * Ensures the parent directory of a file path exists, creating it if necessary.
     *
     * @param filePath Absolute path to a file
     * @return true if directory exists or was created, false on failure
     */
    fun ensureParentDirectoryExists(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val parentDir = file.parentFile ?: return true

            if (!parentDir.exists()) {
                parentDir.mkdirs()
            } else {
                true
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to create parent directory", error = e)
            false
        }
    }

    /**
     * Attempts to delete a partial/failed download file.
     * Silently fails if file doesn't exist or can't be deleted.
     *
     * @param filePath Path to the file to clean up
     */
    fun cleanupPartialFile(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
                logger.debug(LogCategory.FILE, "Cleaned up partial download", mapOf("path" to filePath))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.FILE, "Failed to clean up partial file", error = e)
        }
    }

    /**
     * Checks if a directory is writable.
     *
     * @param directoryPath Path to the directory
     * @return true if directory is writable, false otherwise
     */
    fun isDirectoryWritable(directoryPath: String): Boolean {
        return try {
            val dir = File(directoryPath)
            dir.isDirectory && dir.canWrite()
        } catch (e: Exception) {
            false
        }
    }
}
