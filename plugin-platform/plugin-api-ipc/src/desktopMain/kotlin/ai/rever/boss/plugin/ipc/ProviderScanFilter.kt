package ai.rever.boss.plugin.ipc

/**
 * Provider-contract visibility rules for IPC scan results.
 *
 * The kernel's FileSystemService is a general-purpose API: its scan walks
 * top-down with a NAME-only hidden filter, so it descends into hidden
 * directories and returns their non-dot children (e.g. /root/.git/config)
 * in a FLAT entry list, and it never skips build/node_modules. The
 * FileSystemDataProvider CONTRACT (as implemented by the in-process
 * scanner) hides everything below a hidden dir when showHidden is off and
 * always skips build/node_modules — this filter re-establishes those
 * semantics proxy-side so the provider behaves identically in- and
 * out-of-process.
 *
 * Public (not internal) so composeApp's test suite can assert the skip-list
 * stays equal to the in-process scanner's — the two live in modules that
 * can't share a constant.
 */
object ProviderScanFilter {
    /** Keep in sync with composeApp's scannerSkippedDirectoryNames (CodeBase.kt). */
    val skippedDirectoryNames = setOf("build", "node_modules")

    /** Name-only visibility, for local (non-recursive) listings. */
    fun isVisibleLocalEntry(
        name: String,
        showHidden: Boolean,
    ): Boolean = (showHidden || !name.startsWith(".")) && name !in skippedDirectoryNames

    /**
     * Visibility of a scanned entry, considering every path segment BELOW
     * the scanned root (an entry inside build/ has an innocent-looking name
     * of its own; likewise children of a hidden dir when showHidden is off).
     * Segments above the root are ignored so explicitly scanning inside
     * build/ or a dot-directory still works, matching the in-process scanner.
     *
     * Paths are compared on separator-normalized copies (so a forward-slash
     * caller root matches the kernel's backslash absolutePath on Windows),
     * and descent requires a path-segment boundary ("/a/.foo" must not claim
     * "/a/.foobar/config"). If the shapes still disagree (case, symlinks,
     * `..` normalization), degrades to a name-only check on the entry's own
     * filename rather than misreading ancestor segments as being below the
     * root — which would silently empty scans of projects living under
     * dot-directories (e.g. ~/.config/...).
     */
    fun isVisibleProviderEntry(
        rootPath: String,
        entryPath: String,
        showHidden: Boolean,
    ): Boolean {
        val root = rootPath.replace('\\', '/').trimEnd('/')
        val entry = entryPath.replace('\\', '/')

        if (entry.trimEnd('/') == root) return false // the root itself is not an entry

        if (!entry.startsWith("$root/")) {
            // Not provably below the root — name-only degradation (see KDoc)
            return isVisibleLocalEntry(entry.trimEnd('/').substringAfterLast('/'), showHidden)
        }

        val segments = entry.removePrefix("$root/").split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.any { it in skippedDirectoryNames }) return false
        return showHidden || segments.none { it.startsWith(".") }
    }
}
