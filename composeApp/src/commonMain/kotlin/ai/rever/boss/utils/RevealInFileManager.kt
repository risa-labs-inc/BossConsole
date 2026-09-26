package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException

private val revealLogger = BossLogger.forComponent("RevealInFileManager")

private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

/**
 * Reveal [path] in the OS file manager, selecting the file where supported
 * (macOS Finder, Windows Explorer) or opening its containing folder (Linux).
 * No-op when [path] is blank; returns a failure (and logs) if the OS command
 * can't be launched.
 *
 * This is the single canonical reveal implementation — `FileSystemUtils.revealInFolder`
 * and `FileSystemDataProviderImpl.revealInFileManager` delegate here so the OS-specific
 * command lives in exactly one place.
 *
 * Security: This is a shared host utility. For plugin-originated reveals, use
 * [revealInFileManager] with [allowedRoots] to enforce scoped security boundaries.
 * When called without [allowedRoots], this is used for host-initiated reveals and
 * skips validation (the host is trusted).
 */
fun revealInFileManager(
    path: String,
    allowedRoots: Set<File>? = null,
): Result<Unit> {
    if (path.isBlank()) return Result.success(Unit)

    val validatedPath =
        if (allowedRoots != null) {
            try {
                validatePathForReveal(path, allowedRoots)
            } catch (e: SecurityException) {
                revealLogger.warn(
                    LogCategory.FILE,
                    "Reveal in file manager denied: path outside allowed boundary",
                    mapOf("path" to path),
                )
                return Result.failure(e)
            }
        } else {
            path
        }

    val file = File(validatedPath)
    return runCatching {
        when {
            osName.contains("mac") -> {
                Runtime.getRuntime().exec(arrayOf("open", "-R", file.absolutePath))
            }

            osName.contains("win") -> {
                val winPath = file.absolutePath
                val explorer = windowsExplorer()
                if (winPath.contains("  ")) {
                    // Limitation: the single-string exec below tokenizes on whitespace and
                    // COLLAPSES consecutive spaces on rejoin, corrupting such paths. Open
                    // the parent folder (no selection) instead of handing Explorer a wrong
                    // path — a lone path token is whole-quoted correctly by the array form.
                    Runtime.getRuntime().exec(arrayOf(explorer, (file.parentFile ?: file).absolutePath))
                } else {
                    // Explorer's non-standard parser wants `/select,"C:\the path"` — only the
                    // path portion quoted, inside a single argv token. The array form can't
                    // produce that: when the path contains spaces the JDK quotes the ENTIRE
                    // token, and Explorer then falls back to opening the default folder.
                    // The single-string exec splits on whitespace and rejoins the tokens
                    // verbatim (single spaces round-trip; doubles are handled above),
                    // reproducing exactly this command line. `"` is illegal in Windows file
                    // names, so the path never needs escaping, and [windowsExplorer] never returns
                    // a path with whitespace in it, so the executable stays one token.
                    @Suppress("DEPRECATION")
                    Runtime.getRuntime().exec("$explorer /select,\"$winPath\"")
                }
            }

            else -> {
                val dir = (file.parentFile ?: file).absolutePath
                try {
                    Runtime.getRuntime().exec(arrayOf("xdg-open", dir))
                } catch (e: IOException) {
                    // Minimal environments may lack xdg-utils; glib's `gio open` is the
                    // most common secondary launcher.
                    revealLogger.debug(
                        LogCategory.FILE,
                        "xdg-open unavailable, falling back to gio open",
                        mapOf("error" to e.toString()),
                    )
                    Runtime.getRuntime().exec(arrayOf("gio", "open", dir))
                }
            }
        }
        Unit
    }.onFailure {
        revealLogger.warn(LogCategory.FILE, "Failed to reveal in file manager", mapOf("path" to path), error = it)
    }
}

/**
 * Validates that [path] is within the [allowedRoots] for reveal operations.
 *
 * This is a scoped validation function used for plugin-originated reveals.
 * It does not use global state and must be called with explicit allowed roots.
 */
private fun validatePathForReveal(
    path: String,
    allowedRoots: Set<File>,
): String {
    val file = File(path).canonicalFile
    for (root in allowedRoots) {
        val canonicalRoot = root.canonicalFile
        if (file.absolutePath == canonicalRoot.absolutePath ||
            file.absolutePath.startsWith(canonicalRoot.absolutePath + File.separator)
        ) {
            return file.absolutePath
        }
    }
    throw SecurityException("Access denied: path '$path' is outside allowed roots for reveal operation")
}

/**
 * The Explorer to launch on Windows: `%SystemRoot%\explorer.exe` when that is a real file, otherwise
 * the bare name, as before.
 *
 * A bare `explorer.exe` is found through `CreateProcess`'s search order, which tries the
 * application's own directory and the current directory before `System32`, so an `explorer.exe`
 * placed in either would run in place of Windows' own. The bare name is still the fallback when
 * `SystemRoot` is unset, not drive-rooted or UNC, contains whitespace (the reveal path's
 * single-string exec splits on it), or has no `explorer.exe` in it.
 *
 * Built as a string rather than a [File] so it reads the same on the non-Windows machines that
 * run its tests.
 */
internal fun windowsExplorer(
    systemRoot: String? = System.getenv("SystemRoot"),
    isFile: (String) -> Boolean = { File(it).isFile },
): String {
    val root = systemRoot?.trimEnd('\\', '/')
    val usable = root != null && root.none(Char::isWhitespace) && WINDOWS_ABSOLUTE.containsMatchIn(root)
    val explorer = "$root\\explorer.exe"
    return if (usable && isFile(explorer)) explorer else "explorer.exe"
}

/** A drive-rooted (`C:\`, not the drive-relative `C:dir`) or UNC (`\\server\share`) Windows path. */
private val WINDOWS_ABSOLUTE = Regex("""^([A-Za-z]:(\\|$)|\\\\[^\\]+\\[^\\]+)""")

/** Platform-appropriate label for the reveal action. */
fun revealInFileManagerLabel(): String =
    when {
        osName.contains("mac") -> "Reveal in Finder"
        osName.contains("win") -> "Reveal in Explorer"
        else -> "Open Containing Folder"
    }
