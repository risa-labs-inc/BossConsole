package ai.rever.boss.components.plugin.language

/**
 * The host's single answer to "what language is this file?".
 *
 * There were three copies of this map in the host with different membership, and
 * they disagreed: one knew 27 extensions, one knew 12 and never lowercased, and a
 * third carried `pyw`/`mjs` the others lacked. None could identify `Dockerfile` or
 * `Makefile`, because every one of them keyed on an extension and those files have
 * none.
 *
 * Everything host-side routes here now, so a fix lands once instead of three times
 * — and `editor_detect_language` cannot report one language while the editor tab
 * highlights another.
 *
 * The editor-tab plugin keeps its own `LanguageDetection` (separate artifact, cannot
 * depend on this) and the ids must stay byte-identical to it: `properties` not
 * `ini`, `batch` not `bat`.
 *
 * The tables are data rather than a `when` chain — a 40-branch `when` is both harder
 * to scan and, at complexity 44, something detekt rightly objects to.
 */
object EditorLanguages {
    /**
     * Language id for [filePath], or `"text"` when nothing matches.
     *
     * File *name* patterns are tried before the extension, because the files that
     * most need identifying have no extension: `substringAfterLast('.')` yields `""`
     * for both `Dockerfile` and `Makefile`.
     *
     * The extension is read from the file name, not the whole path. Reading it from
     * the path let a dot in a parent directory leak into the answer —
     * `/srv/v1.2/Makefile` produced the "extension" `2/Makefile`.
     */
    fun detect(filePath: String): String {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        forFileName(fileName)?.let { return it }
        return EXTENSIONS[fileName.substringAfterLast('.', "").lowercase()] ?: TEXT
    }

    /**
     * Languages identified by file name rather than extension.
     *
     * Checked before extensions so that `Dockerfile.dev` is a Dockerfile rather than
     * whatever `.dev` might otherwise suggest.
     *
     * Deliberately omits `CMakeLists.txt`: CMake is not Make, and a Make lexer would
     * hunt for tab-indented recipes that do not exist while missing `if()/endif()`
     * and `${VAR}`. No highlighting beats confidently wrong highlighting.
     */
    private fun forFileName(fileName: String): String? {
        val lower = fileName.lowercase()
        EXACT_NAMES[lower]?.let { return it }
        return PREFIXED_NAMES.firstNotNullOfOrNull { (prefix, language) ->
            language.takeIf { lower.startsWith(prefix) }
        }
    }

    private const val TEXT = "text"

    /** Whole file names. Bare only — `Gemfile.lock` is generated data, not Ruby. */
    private val EXACT_NAMES =
        mapOf(
            "dockerfile" to "dockerfile",
            "containerfile" to "dockerfile",
            "makefile" to "makefile",
            "gnumakefile" to "makefile",
            ".env" to "properties",
            "gemfile" to "ruby",
            "rakefile" to "ruby",
        )

    /** Name prefixes, for the `Dockerfile.dev` / `.env.local` family. */
    private val PREFIXED_NAMES =
        listOf(
            "dockerfile." to "dockerfile",
            "containerfile." to "dockerfile",
            "makefile." to "makefile",
            ".env." to "properties",
        )

    private val EXTENSIONS =
        mapOf(
            "kt" to "kotlin",
            "kts" to "kotlin",
            "java" to "java",
            "js" to "javascript",
            "jsx" to "javascript",
            "mjs" to "javascript",
            "cjs" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "py" to "python",
            "pyw" to "python",
            "json" to "json",
            "xml" to "xml",
            "html" to "html",
            "htm" to "html",
            "css" to "css",
            "scss" to "css",
            "sass" to "css",
            "md" to "markdown",
            "markdown" to "markdown",
            "toml" to "toml",
            "gradle" to "groovy",
            "swift" to "swift",
            "c" to "c",
            "h" to "c",
            "cpp" to "cpp",
            "cc" to "cpp",
            "cxx" to "cpp",
            "hpp" to "cpp",
            "cs" to "csharp",
            "rs" to "rust",
            "go" to "go",
            "rb" to "ruby",
            "php" to "php",
            "pl" to "perl",
            "pm" to "perl",
            "lua" to "lua",
            "sh" to "bash",
            "bash" to "bash",
            "zsh" to "bash",
            "yml" to "yaml",
            "yaml" to "yaml",
            "sql" to "sql",
            "r" to "r",
            "scala" to "scala",
            // Languages the editor has lexers for that no host map ever named.
            "dockerfile" to "dockerfile",
            "mk" to "makefile",
            "mak" to "makefile",
            "properties" to "properties",
            "ini" to "properties",
            "cfg" to "properties",
            "env" to "properties",
            "diff" to "diff",
            "patch" to "diff",
            "bat" to "batch",
            "cmd" to "batch",
            "clj" to "clojure",
            "cljs" to "clojure",
            "cljc" to "clojure",
            "edn" to "clojure",
            "tex" to "latex",
            "sty" to "latex",
            "cls" to "latex",
            "bib" to "latex",
            "lisp" to "lisp",
            "lsp" to "lisp",
            "el" to "lisp",
            "scm" to "lisp",
            "tcl" to "tcl",
            "f" to "fortran",
            "f90" to "fortran",
            "f95" to "fortran",
            "f03" to "fortran",
            "for" to "fortran",
            "d" to "d",
            "pas" to "delphi",
            "dpr" to "delphi",
            "dfm" to "delphi",
            "vb" to "visualbasic",
            "vbs" to "visualbasic",
            "as" to "actionscript",
            "jsp" to "jsp",
            "jspx" to "jsp",
        )
}
