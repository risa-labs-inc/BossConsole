package ai.rever.boss.components.window_panel

import java.io.File

/**
 * Path comparison for "is this file already open in a tab?".
 *
 * The reuse check was a raw string equality on the tab's stored path, so the
 * same file opened from two places produced two tabs whenever the two callers
 * spelled the path differently. They do: the file tree passes the path the
 * host's scanner produced, while the git provider builds `"$projectPath/$rel"`
 * - which doubles the separator whenever the project path ends in one, and
 * never resolves `.`/`..` or a symlinked project root.
 */
internal object TabPaths {
    /**
     * A canonical form for comparison only - never for display or for opening.
     *
     * Falls back to a lexical cleanup when the file cannot be resolved (it may
     * not exist yet, or be unreadable), so an unresolvable path still compares
     * consistently with itself.
     *
     * Cost note: [canonicalPath] is a system call per call, and the tab-match
     * hot path used to pay it for every open event times every open tab.
     * Callers comparing two paths should prefer [pathsMatch], which only pays
     * this when the cheap lexical comparison disagrees.
     */
    fun normalize(path: String): String {
        if (path.isBlank()) return ""
        val lexical = lexicalClean(path)
        return try {
            File(lexical).canonicalPath
        } catch (e: Exception) {
            lexical
        }
    }

    /**
     * Whether two paths name the same file, without paying for [normalize] when
     * the lexical forms already agree - which is the common case, since most
     * openers pass one and the same absolute path. (Lexically equal strings
     * canonicalise identically, so the fast answer is exact, not a shortcut.)
     *
     * Only when the lexical forms DISAGREE does it canonicalise both sides -
     * the case this exists for: the same file spelled with a doubled
     * separator is caught lexically, but a symlinked or renamed project root
     * (`/tmp/proj` vs `/private/tmp/proj`) is caught only on the canonical
     * form.
     */
    fun pathsMatch(
        a: String,
        b: String,
    ): Boolean {
        if (a.isBlank() || b.isBlank()) return a.isBlank() && b.isBlank()
        val lexicalMatch = lexicalClean(a) == lexicalClean(b)
        return lexicalMatch || normalize(a) == normalize(b)
    }

    /**
     * Collapse repeated separators and drop a trailing one.
     *
     * Internal so the two deliberate choices in here are pinnable: backslash is only
     * a separator on Windows, and a leading `//` (UNC host) survives the collapse.
     * On POSIX [normalize] reaches this only when canonicalPath throws, so the
     * tests exercise it directly. The separator is a parameter so the Windows
     * branch is testable on the Linux/macOS runners too - with the default it is
     * unreachable there (`File.separatorChar == '/'`).
     */
    internal fun lexicalClean(
        path: String,
        separatorChar: Char = File.separatorChar,
    ): String {
        // Backslash is a path separator on Windows but a LEGAL filename character on
        // macOS/Linux, so translating it unconditionally turned `a\b.kt` into `a/b.kt`
        // and could canonicalise onto a different real file (focusing the wrong tab).
        val unified = if (separatorChar == '\\') path.replace('\\', '/') else path
        // Collapse repeated slashes, but keep a leading "//" - a Windows UNC path
        // (\\server\share) becomes //server/share, and flattening it to /server/share
        // makes two tabs on different servers compare equal.
        val uncPrefix = if (unified.startsWith("//")) "/" else ""
        val collapsed = uncPrefix + unified.replace(Regex("/{2,}"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }
}
