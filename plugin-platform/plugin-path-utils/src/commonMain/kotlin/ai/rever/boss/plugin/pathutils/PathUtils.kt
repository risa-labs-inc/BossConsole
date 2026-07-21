package ai.rever.boss.plugin.pathutils

/**
 * Path utility functions for cross-platform file path handling.
 *
 * This is the single source of truth for path utilities used across all modules:
 * - composeApp (via delegation)
 * - bosseditor (direct dependency)
 * - plugin-icons (direct dependency)
 *
 * KNOWN LIMITATIONS:
 *
 * 1. EDGE CASES NOT HANDLED:
 *    - UNC paths (\\server\share\file.txt): May not extract parent correctly
 *    - Root-level files (/file.txt or C:\file.txt): Returns empty or drive letter
 *    - Network paths with mixed separators: Behavior may be unpredictable
 *    - Paths with trailing separators: Not normalized automatically
 *
 * 2. SIMPLE IMPLEMENTATION:
 *    Uses basic string manipulation rather than File/Path APIs for simplicity and
 *    to avoid platform-specific behavior. This makes the code predictable but limited.
 */

/**
 * Extract file or folder name from a path, handling both Unix (/) and Windows (\) separators.
 *
 * Examples:
 * - "/path/to/file.txt" -> "file.txt"
 * - "C:\Users\file.txt" -> "file.txt"
 * - "C:/mixed\path/file.txt" -> "file.txt"
 *
 * Edge cases:
 * - "" -> "" (empty string returns empty)
 * - "/" -> "" (root path returns empty)
 * - "file.txt" -> "file.txt" (no path returns the string itself)
 *
 * Note: Does not handle edge cases like UNC paths (\\server\share) or root files correctly.
 */
fun String.extractFileName(): String = this.substringAfterLast('/').substringAfterLast('\\')

/**
 * Extract parent folder name from a path, handling both Unix (/) and Windows (\) separators.
 *
 * Examples:
 * - "/path/to/file.txt" -> "to"
 * - "C:\Users\Documents\file.txt" -> "Documents"
 * - "C:/mixed\path/file.txt" -> "path"
 *
 * Implementation: Normalizes to forward slashes, then extracts the parent folder name.
 * Returns the parent path itself if no parent folder can be determined.
 *
 * Note: Does not handle edge cases like UNC paths (\\server\share) or root files correctly.
 */
fun String.extractParentName(): String {
    val normalized = this.replace('\\', '/')
    val parentPath = normalized.substringBeforeLast('/')
    return parentPath.substringAfterLast('/').ifEmpty { parentPath }
}
