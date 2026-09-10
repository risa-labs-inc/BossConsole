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
 *    - UNC share roots are treated lexically, not as filesystem roots
 *    - Root-level files (/file.txt or C:\file.txt): Returns empty or drive letter
 *    - Both slash styles are separators, including literal backslashes in POSIX names.
 *      These display-label helpers differ deliberately from host-specific file identity rules.
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
 * This is lexical extraction: root-level files return their filename, UNC share roots
 * return the share name, and a trailing separator returns an empty name.
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
 * Implementation: Normalizes to forward slashes, then extracts the parent folder name,
 * ignoring repeated separators before the filename.
 * Returns an empty string for a filename without a parent path.
 *
 * Root-level POSIX files have an empty parent label; Windows root-level files return
 * the drive prefix (for example, "C:"). UNC files return the containing folder/share
 * label, but a UNC share root itself is not recognized as a filesystem root.
 */
fun String.extractParentName(): String {
    val normalized = this.replace('\\', '/')
    val parentPath = normalized.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
    return parentPath.substringAfterLast('/')
}
