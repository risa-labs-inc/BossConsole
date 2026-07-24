package ai.rever.boss.plugin.events

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private val fileRefLogger = BossLogger.forComponent("FileEventBus")

/**
 * Event emitted when a file should be opened in the editor.
 *
 * @property filePath The file path to open
 * @property fileName The file name (extracted from path)
 * @property line 1-based line to navigate to (0 = don't navigate)
 * @property column 1-based column to navigate to (0 = don't navigate)
 * @property sourceWindowId The window that initiated this event (required for multi-window support)
 */
data class FileOpenEvent(
    val filePath: String,
    val fileName: String,
    val line: Int = 0,
    val column: Int = 0,
    val sourceWindowId: String,
)

/**
 * Callback interface for file open tracking.
 * Implement this to track file opens (e.g., recent files tracking).
 */
fun interface FileOpenCallback {
    fun onFileOpen(
        filePath: String,
        projectPath: String,
    )
}

/**
 * Strips the file protocol prefix from a path if present.
 * Handles various file URI formats:
 * - file:///path (Unix absolute, most common)
 * - file://path (some systems)
 * - file:/path (shorthand)
 * - file:path (minimal)
 *
 * Path sanitization note: This function only strips the prefix. Callers should
 * validate the resulting path using [validateFilePath] before opening files.
 *
 * Special character handling: File paths with spaces, Unicode characters, or other
 * special characters are preserved as-is after stripping the prefix. The underlying
 * file system APIs handle these correctly.
 */
fun stripFilePrefix(path: String): String =
    when {
        path.startsWith("file:///") -> path.removePrefix("file://")

        // Keep leading / for absolute path
        path.startsWith("file://") -> path.removePrefix("file://")

        path.startsWith("file:/") -> path.removePrefix("file:")

        path.startsWith("file:") -> path.removePrefix("file:")

        else -> path
    }

/**
 * Result of file path validation.
 */
sealed class FileValidationResult {
    data class Valid(
        val canonicalPath: String,
    ) : FileValidationResult()

    data class Invalid(
        val reason: String,
    ) : FileValidationResult()
}

/**
 * Parsed file reference with optional line and column numbers.
 *
 * @property path The file path (URL-decoded, without line:column suffix)
 * @property line 1-based line number (0 = not specified)
 * @property column 1-based column number (0 = not specified)
 */
data class ParsedFileReference(
    val path: String,
    val line: Int = 0,
    val column: Int = 0,
)

/**
 * Checks if a string starts with a Windows drive letter pattern (e.g., "C:").
 *
 * @receiver The string to check
 * @return true if the string starts with a drive letter followed by colon
 */
private fun String.hasWindowsDriveLetter(): Boolean = length >= 2 && this[0].isLetter() && this[1] == ':'

/**
 * Parses a file reference that may include line and column numbers.
 *
 * Handles formats:
 * - `/path/to/file.kt` -> path only
 * - `/path/to/file.kt:123` -> path + line
 * - `/path/to/file.kt:123:45` -> path + line + column
 * - `C:\path\file.kt:123` -> Windows path + line (drive letter preserved)
 * - URL-encoded paths (e.g., `%20` for spaces)
 *
 * Note: Windows paths with drive letters (e.g., `C:\`) have a colon after
 * the drive letter which must not be confused with line number separator.
 *
 * JVM-only: Uses java.net.URLDecoder. Desktop target only.
 *
 * @param fileUrl The file URL (with or without file: prefix already stripped)
 * @return ParsedFileReference with path, line, and column
 */
fun parseFileReference(fileUrl: String): ParsedFileReference {
    // URL-decode the path first (handles %20 for spaces, etc.)
    // JVM-only: Desktop target only
    val decoded =
        try {
            java.net.URLDecoder.decode(fileUrl, "UTF-8")
        } catch (e: Exception) {
            // Fall back to original if decoding fails
            fileRefLogger.debug(
                LogCategory.FILE,
                "URL-decode of file reference failed - using raw value",
                mapOf("error" to e.toString()),
            )
            fileUrl
        }

    // Check for Windows drive letter pattern (e.g., C:\)
    val isWindowsPath = decoded.hasWindowsDriveLetter()

    // Find line:column suffix by looking for :digits pattern from the end
    // For Windows paths, skip the first colon (drive letter)
    val startIndex = if (isWindowsPath) 2 else 0
    val lastColonIndex = decoded.lastIndexOf(':')
    val secondLastColonIndex = decoded.lastIndexOf(':', lastColonIndex - 1)

    // Check if what's after the last colon looks like a number
    val afterLastColon =
        if (lastColonIndex > startIndex) {
            decoded.substring(lastColonIndex + 1)
        } else {
            null
        }

    val afterSecondLastColon =
        if (secondLastColonIndex > startIndex && lastColonIndex > secondLastColonIndex) {
            decoded.substring(secondLastColonIndex + 1, lastColonIndex)
        } else {
            null
        }

    return when {
        // Pattern: path:line:column
        afterSecondLastColon != null &&
            afterSecondLastColon.toIntOrNull() != null &&
            afterLastColon?.toIntOrNull() != null -> {
            ParsedFileReference(
                path = decoded.substring(0, secondLastColonIndex),
                line = afterSecondLastColon.toInt(),
                column = afterLastColon.toInt(),
            )
        }

        // Pattern: path:line
        afterLastColon?.toIntOrNull() != null && lastColonIndex > startIndex -> {
            ParsedFileReference(
                path = decoded.substring(0, lastColonIndex),
                line = afterLastColon.toInt(),
            )
        }

        // Pattern: path only
        else -> {
            ParsedFileReference(path = decoded)
        }
    }
}

/**
 * Validates a file path for safety and existence.
 *
 * Checks performed:
 * 1. Path is not empty
 * 2. File exists on disk
 * 3. Path points to a file (not a directory)
 * 4. File is readable
 *
 * Note on path traversal: We use canonicalFile to resolve ".." sequences,
 * but we don't restrict which directories can be accessed since the editor
 * operates in user context and should be able to open any file the user can access.
 * The main protection is against non-existent files and directories.
 *
 * JVM-only: Uses java.io.File. Desktop target only.
 *
 * @param filePath The file path to validate (should have file: prefix already stripped)
 * @return FileValidationResult.Valid with canonical path, or FileValidationResult.Invalid with reason
 */
fun validateFilePath(filePath: String): FileValidationResult {
    if (filePath.isBlank()) {
        return FileValidationResult.Invalid("Empty file path")
    }

    return try {
        val file = java.io.File(filePath).canonicalFile

        when {
            !file.exists() -> FileValidationResult.Invalid("File does not exist: ${file.absolutePath}")
            !file.isFile -> FileValidationResult.Invalid("Not a file (may be a directory): ${file.absolutePath}")
            !file.canRead() -> FileValidationResult.Invalid("File is not readable: ${file.absolutePath}")
            else -> FileValidationResult.Valid(file.absolutePath)
        }
    } catch (e: java.io.IOException) {
        FileValidationResult.Invalid("Invalid file path: ${e.message}")
    } catch (e: SecurityException) {
        FileValidationResult.Invalid("Access denied: ${e.message}")
    }
}

/**
 * Extract file name from a path, handling both Unix (/) and Windows (\) separators.
 */
fun String.extractFileName(): String = this.substringAfterLast('/').substringAfterLast('\\')

object FileEventBus {
    private val logger = BossLogger.forComponent("FileEventBus")
    private val _fileOpenEvents =
        MutableSharedFlow<FileOpenEvent>(
            replay = 0, // Don't replay past events to new subscribers (new windows)
            extraBufferCapacity = 10, // Buffer up to 10 events if collector not ready yet
        )
    val fileOpenEvents: SharedFlow<FileOpenEvent> = _fileOpenEvents.asSharedFlow()

    // Callback for file tracking (e.g., recent files)
    private var fileOpenCallback: FileOpenCallback? = null

    /**
     * Register a callback to be notified when files are opened.
     * This is used for tracking recent files, etc.
     */
    fun setFileOpenCallback(callback: FileOpenCallback?) {
        fileOpenCallback = callback
    }

    /**
     * Opens a file in the editor.
     *
     * @param filePath The file path to open. May include "file:" prefix (will be stripped).
     * @param line 1-based line number to navigate to (0 = don't navigate)
     * @param column 1-based column number to navigate to (0 = don't navigate)
     * @param sourceWindowId The window that initiated this event (required for multi-window support)
     * @param projectPath The current project path for tracking recent files
     */
    suspend fun openFile(
        filePath: String,
        line: Int = 0,
        column: Int = 0,
        sourceWindowId: String,
        projectPath: String = "",
    ) {
        // Strip file: prefix if present (may come from terminal hyperlinks)
        val cleanPath = stripFilePrefix(filePath)
        val fileName = cleanPath.extractFileName().ifEmpty { "untitled" }

        // Notify callback for tracking (e.g., recent files)
        fileOpenCallback?.onFileOpen(cleanPath, projectPath)

        logger.debug(
            LogCategory.FILE,
            "Opening file",
            mapOf(
                "path" to cleanPath,
                "line" to line,
                "column" to column,
                "window" to sourceWindowId,
            ),
        )
        _fileOpenEvents.emit(FileOpenEvent(cleanPath, fileName, line, column, sourceWindowId))
    }
}
