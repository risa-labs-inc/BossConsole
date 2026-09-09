package ai.rever.boss.plugin.language

/**
 * The canonical file-name/extension to language-id table (BossConsole#75).
 *
 * Before this module existed, this exact table lived only inside `composeApp`'s
 * `EditorLanguages`, which could not be depended on by anything outside `composeApp` -
 * so `modules/boss-app-editor`'s `EditorServiceImpl` kept its own smaller, independently
 * hand-maintained copy. The two disagreed: this table names `.sh`/`.bash`/`.zsh` as
 * `bash`, the other as `shell`, and the other lacked more than forty ids this table
 * carries (`fortran`, `delphi`, `latex`, `lisp`, `tcl`, `clojure`, `batch`, `diff`, …).
 * Both now read from here. Consumers may keep protocol-specific defaults: the OOP
 * editor uses `plaintext` for unknown files instead of [TEXT], and additionally
 * recognizes protobuf files.
 *
 * This module is deliberately dependency-free: `boss-app-editor` compiles to a GraalVM
 * native image and has never depended on anything under `plugin-platform` before, so
 * pulling in Compose or any other UI dependency here would carry into that native-image
 * build for no reason a pure data table needs.
 *
 * Two other copies of this mapping are known to exist and are **not** consolidated here:
 * the editor-tab plugin's own `LanguageDetection` (a separate repository,
 * `boss-plugin-editor-tab`, which cannot depend on a module published from this one
 * without a new plugin-api release) and BossEditor's lexer registry (also a separate
 * repository - the ids this table produces are only useful insofar as BossEditor has a
 * lexer registered for them, which is why `EditorLanguagesTest`-style coverage can pin
 * this table's own consistency but never BossEditor's).
 *
 * `FileIcons.forSpecialFileName`/`FileIcons.forFile` in `plugin-icons` is also not
 * consolidated onto this table: it keys icon selection directly off the raw extension or
 * filename to preserve icon-level distinctions this table has no id for (`package.json`,
 * `yarn.lock` and `pnpm-lock.yaml` are all valid JSON/YAML but get three different brand
 * icons) - it never computes a language id in the first place, so there is no shared value
 * to route through here without first redesigning icon selection to work at a coarser
 * granularity than it does today.
 */
object LanguageIds {
    const val TEXT = "text"

    /**
     * Language id for [filePath], or [TEXT] when nothing matches.
     *
     * File *name* patterns are tried before the extension, because the files that most
     * need identifying have no extension: `substringAfterLast('.')` yields `""` for both
     * `Dockerfile` and `Makefile`.
     *
     * The extension is read from the file name, not the whole path. Reading it from the
     * path let a dot in a parent directory leak into the answer -
     * `/srv/v1.2/Makefile` produced the "extension" `2/Makefile`.
     */
    fun detect(filePath: String): String {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        forFileName(fileName)?.let { return it }
        return forExtension(fileName.substringAfterLast('.', "")) ?: TEXT
    }

    /** Language id for a bare [extension] (no leading dot), or `null` when unknown. */
    fun forExtension(extension: String): String? = EXTENSIONS[extension.lowercase()]

    /**
     * The extension-to-language table, for whoever has to stay in step with it.
     *
     * Exposed for exactly one reason: `boss-file-types.json` declares which file types
     * BOSS asks the OS to make it the default for, and it must claim neither more nor
     * less than what this table can highlight. Read-only by construction: `EXTENSIONS`
     * is an immutable `Map`, so this hands out no way to change it.
     */
    fun extensions(): Map<String, String> = EXTENSIONS

    /**
     * Languages identified by file name rather than extension.
     *
     * Checked before extensions so that `Dockerfile.dev` is a Dockerfile rather than
     * whatever `.dev` might otherwise suggest.
     *
     * Deliberately omits `CMakeLists.txt`: CMake is not Make, and a Make lexer would hunt
     * for tab-indented recipes that do not exist while missing `if()/endif()` and
     * `${VAR}`. No highlighting beats confidently wrong highlighting.
     */
    private fun forFileName(fileName: String): String? {
        val lower = fileName.lowercase()
        EXACT_NAMES[lower]?.let { return it }
        return PREFIXED_NAMES.firstNotNullOfOrNull { (prefix, language) ->
            language.takeIf { lower.startsWith(prefix) }
        }
    }

    /** Whole file names. Bare only - `Gemfile.lock` is generated data, not Ruby. */
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
