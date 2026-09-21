package ai.rever.boss.dashboard

import ai.rever.boss.components.workspaces.CommandProcessor
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Resolves the placeholders a workspace's tabs are written in - `{projectPath}`,
 * `{gitRemoteUrl}`, `{currentFile}`, `{claudeContinueFlag}` - against the selected project.
 *
 * This was `SplitTemplatesManager`, and it also held a second catalogue of the built-in
 * layouts: seven `SplitTemplate`s that mirrored seven of `PredefinedWorkspaces.allWorkspaces`
 * plus custom ones read from `~/.boss/split-templates.json`. Only the home screen read that
 * list, while the top bar, the app menu and the default-workspace setting read the workspace
 * list, and the two had already drifted - the workspace copy has Browser Only and passes
 * `{claudeContinueFlag}`, the template copy had neither. The home screen now reads
 * `WorkspaceManager` like everything else, which left the catalogue with no readers.
 *
 * Nothing in the app ever *wrote* `split-templates.json` (`addCustomTemplate` had no callers),
 * so the only thing lost with it is a hand-edited file. Saving a layout is the workspace
 * button's "Save Space...", and a workspace saved that way now shows on the home screen.
 */
object WorkspacePlaceholders {
    private val logger = BossLogger.forComponent("WorkspacePlaceholders")

    // Also the substitution branches in [processPlaceholders], so a branch and its
    // substitution cannot drift. Every placeholder whose value costs something to compute
    // needs one: a mkdir, a `git` subprocess and a directory listing respectively.
    private const val PROJECT_PATH_PLACEHOLDER = "{projectPath}"
    private const val GIT_REMOTE_URL_PLACEHOLDER = "{gitRemoteUrl}"
    private const val CLAUDE_CONTINUE_FLAG_PLACEHOLDER = "{claudeContinueFlag}"
    private const val CURRENT_FILE_PLACEHOLDER = "{currentFile}"

    /**
     * Every token the pipeline substitutes, as ONE alternation: the pipeline is one scan
     * of the template, never of the values. [Regex.replace] with a transform inserts each
     * value without rescanning it, which is what makes substituted spans inert data -
     * placeholder syntax inside a value is left as literal text instead of being expanded
     * by a later pass. The sequential whole-string `replace` passes this replaces re-scanned
     * everything an earlier pass had substituted in: a project path legitimately named
     * `{claudeContinueFlag}` (braces are legal in POSIX and Windows filenames) came out as
     * `--continue`, and a value containing `{projectPath}` was re-expanded by the raw second
     * stage of the quoting logic into a wrong, doubled path.
     */
    private val placeholderTokens =
        Regex("""\{projectPath\}|\{gitRemoteUrl\}|\{currentFile\}|\{claudeContinueFlag\}""")

    /** Just [PROJECT_PATH_PLACEHOLDER], for [substituteProjectPath]'s single-token scan. */
    private val projectPathToken = Regex("""\{projectPath\}""")

    /**
     * The substitution for one data-placeholder match at [tokenRange] in [template]:
     * [PROJECT_PATH_PLACEHOLDER], [GIT_REMOTE_URL_PLACEHOLDER] and [CURRENT_FILE_PLACEHOLDER]
     * carry arbitrary bytes - paths and URLs may contain quotes, `$()`, backticks, newlines,
     * globs - so in shell-command context ([quote] true: `initialCommand`, which is typed
     * into a live shell) a BARE occurrence is substituted as a shell-quoted literal argument
     * and its metacharacters stay inert. In non-shell context (url, workingDirectory,
     * filePath - not shell-parsed) every occurrence is verbatim. An occurrence the template
     * wraps in an exact symmetric pair of quote characters is left raw: the documented
     * opt-out against double-quoting a template like `cd "{projectPath}"`.
     */
    private fun dataValueFor(
        template: CharSequence,
        tokenRange: IntRange,
        value: String,
        quote: Boolean,
    ): String {
        if (!quote) return value
        return if (isExactlyQuoteWrapped(template, tokenRange)) value else CommandProcessor.quotePath(value)
    }

    /**
     * Whether [tokenRange] sits inside one quote region that the template opened exactly
     * at the token's left edge and closes exactly at its right edge - `"{token}"` or
     * `'{token}'`, nothing wider and nothing narrower. Only that shape is honoured as the
     * raw-emission opt-out; every other adjacency means the value is NOT wrapped and is
     * shell-quoted so its metacharacters stay inert:
     * - one-sided quotes: `{token}'s` - an apostrophe after the token closes nothing;
     * - neighbouring regions: `'prefix'{token}'suffix'` - both adjacent quotes close and
     *   reopen other regions, so the token sits between quoted spans, bare;
     * - mismatched types: `"{token}'`;
     * - backslash-escaped quotes: a backslash before the quote makes it a literal
     *   character, not a region delimiter.
     *
     * The neighbouring-region case is why this walks the template prefix: if the quote at
     * the token's left edge closes a region that opened earlier, the token sits outside
     * any quote, and trusting adjacency alone would emit a metacharacter value raw.
     */
    private fun isExactlyQuoteWrapped(
        template: CharSequence,
        tokenRange: IntRange,
    ): Boolean {
        val open = template.getOrNull(tokenRange.first - 1)
        val close = template.getOrNull(tokenRange.last + 1)
        if (open != close || (open != '"' && open != '\'')) return false
        if (template.getOrNull(tokenRange.first - 2) == '\\') return false
        var inQuote: Char? = null
        for (i in 0 until tokenRange.first - 1) {
            val c = template[i]
            if (inQuote == null) {
                if (c == '"' || c == '\'') inQuote = c
            } else if (c == inQuote) {
                inQuote = null
            }
        }
        return inQuote == null
    }

    /**
     * Substitute `{projectPath}` with [pathValue].
     *
     * When [quote] is false (raw paths: workingDirectory/filePath/url) every
     * occurrence is replaced verbatim. When true (shell command context),
     * *bare* occurrences are shell-quoted so spaces/quotes survive as one
     * argument — but occurrences a template already wraps in a quote (e.g. a
     * user who worked around the bug with `cd "{projectPath}"`) are left raw,
     * to avoid double-quoting like `cd "'…'"`.
     *
     * ONE scan of [content]: the value is inserted without being rescanned, so a path
     * value that itself contains `{projectPath}` - braces are legal in a filename on
     * POSIX and Windows alike - stays byte-intact. The two-stage form this replaces
     * re-expanded exactly that: the raw second stage re-scanned the text the quoting
     * stage had just substituted in.
     */
    internal fun substituteProjectPath(
        content: String,
        pathValue: String,
        quote: Boolean,
    ): String =
        projectPathToken.replace(content) { match ->
            dataValueFor(content, match.range, pathValue, quote)
        }

    /**
     * Process placeholders in template content.
     *
     * Available placeholders:
     * - {projectPath}: Current project directory path
     * - {gitRemoteUrl}: Git remote origin URL converted to web URL
     * - {currentFile}: Currently open file path
     *
     * Post-substitution invariants. Both matter because the shell context below
     * (`quoteProjectPath = true`, a tab's `initialCommand`) is typed into a live shell,
     * and a Space file - shareable across machines since #1194 - can put any of these
     * tokens in a template:
     * - substituted spans are inert data: ONE scan of the template substitutes each
     *   token, and text that arrives via a value is never rescanned, so placeholder
     *   syntax inside a substituted value stays literal (see [placeholderTokens]);
     * - in shell command context every DATA placeholder ({projectPath}, {gitRemoteUrl},
     *   {currentFile}) is substituted shell-quoted when bare (see [dataValueFor]), so a
     *   value carrying `$()`, backticks, semicolons, newlines or globs cannot execute or
     *   split at the shell boundary. {claudeContinueFlag} is exempt: its value is
     *   app-generated ("--continue" or empty), never data.
     *
     * @param content The content string with placeholders
     * @param projectPath The current project path, or null/blank for no project. This function
     *   handles the no-project case for all three project placeholders consistently, so a
     *   caller may pass a raw path straight from window state - but note that every production
     *   caller resolves first (it needs the same directory for a tab's `workingDirectory`), so
     *   the no-project branch below is reached only by a direct caller. Passing an
     *   already-resolved path is not a second answer, just a no-op.
     * @param currentFile The currently open file (optional)
     * @param quoteProjectPath When true, shell command content: every data placeholder is
     *   substituted as a shell-quoted argument, so a path with spaces/quotes — like
     *   `AI Workflow Tools' Exports` — survives as one argument. Pass true ONLY for shell
     *   command content (e.g. `cd {projectPath} && claude`). Leave
     *   false for raw paths (workingDirectory, filePath, url), which are NOT
     *   shell-parsed and must not be quoted. When true, a placeholder should
     *   stand alone as a whole argument (`{projectPath}/sub` becomes `'…'/sub`,
     *   which POSIX concatenates but PowerShell does not).
     * @return The content with placeholders replaced
     */
    fun processPlaceholders(
        content: String,
        projectPath: String?,
        currentFile: String? = null,
        quoteProjectPath: Boolean = false,
    ): String {
        var result = content

        // One reading of "is there a project" for all three project placeholders. They used to
        // disagree about a blank path: {projectPath} treated it as absent, while the two below
        // took it as a real path - getClaudeContinueFlag("") looks in ~/.claude/projects/
        // itself.
        //
        // Reachable only by a direct caller. Every production caller resolves first, because
        // it needs the same directory for a tab's workingDirectory, so with no project
        // selected all three see ~/BossProjects and take the has-a-project branch - see
        // DefaultWorkingDirectory. What this buys is that the branches agree with each
        // other, whichever one a caller lands on.
        val selectedProject = DefaultWorkingDirectory.selectedOrNull(projectPath)

        // Computed on the first token that needs each value, never before. The sequential
        // passes this replaces guarded each lookup for the same reason: producing a value
        // costs something - ensureDefaultDirectory *creates a directory*, getGitRemoteUrl
        // forks `git remote get-url origin` and waits on it, checkClaudeSessionExists lists
        // ~/.claude/projects - and paying that for content that never mentions the
        // placeholder made it a side effect of a function called processPlaceholders.
        val pathValue by lazy {
            selectedProject ?: DefaultWorkingDirectory.ensureDefaultDirectory()
        }
        val gitRemoteUrl by lazy {
            selectedProject?.let { getGitRemoteUrl(it) } ?: "https://google.com"
        }
        val claudeContinueFlag by lazy { getClaudeContinueFlag(selectedProject) }

        // ONE scan of the template (see [placeholderTokens]): each token's value is
        // inserted verbatim where the token stands in the template and is never rescanned,
        // so text that arrives via a value cannot inject placeholder syntax or rewrite
        // command structure. With no current file, {currentFile} stays literal, as the
        // sequential pipeline already left it.
        val template = result
        result =
            placeholderTokens.replace(template) { match ->
                when (match.value) {
                    PROJECT_PATH_PLACEHOLDER -> {
                        dataValueFor(template, match.range, pathValue, quoteProjectPath)
                    }

                    GIT_REMOTE_URL_PLACEHOLDER -> {
                        dataValueFor(template, match.range, gitRemoteUrl, quoteProjectPath)
                    }

                    CURRENT_FILE_PLACEHOLDER -> {
                        currentFile
                            ?.let { dataValueFor(template, match.range, it, quoteProjectPath) }
                            ?: match.value
                    }

                    CLAUDE_CONTINUE_FLAG_PLACEHOLDER -> {
                        claudeContinueFlag
                    }

                    else -> {
                        match.value
                    }
                }
            }

        // Normalize command separators for current platform (MUST be last step)
        result = CommandProcessor.normalizeCommand(result)

        return result
    }

    /**
     * Get the Git remote origin URL for a project and convert it to a web URL.
     */
    private fun getGitRemoteUrl(projectPath: String): String {
        return try {
            val process =
                ProcessBuilder("git", "remote", "get-url", "origin")
                    .directory(File(projectPath))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val url = reader.readLine()?.trim() ?: return "https://google.com"
            val exitCode = process.waitFor()

            // Validate: git must succeed and output must look like a URL/remote
            if (exitCode != 0 || (
                    !url.startsWith("git@") && !url.startsWith("https://") &&
                        !url.startsWith("http://") && !url.startsWith("ssh://")
                )
            ) {
                return "https://google.com"
            }

            // Convert SSH URL to HTTPS if needed
            convertGitUrlToWebUrl(url)
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Error getting git remote", mapOf("error" to e.toString()))
            "https://google.com"
        }
    }

    /**
     * Convert a Git URL (SSH or HTTPS) to a web URL.
     * Examples:
     * - git@github.com:user/repo.git -> https://github.com/user/repo
     * - https://github.com/user/repo.git -> https://github.com/user/repo
     */
    private fun convertGitUrlToWebUrl(gitUrl: String): String {
        var url = gitUrl.trim()

        // Handle SSH format: git@github.com:user/repo.git
        if (url.startsWith("git@")) {
            url = url.removePrefix("git@")
            url = url.replace(":", "/")
            url = "https://$url"
        }

        // Remove .git suffix
        if (url.endsWith(".git")) {
            url = url.removeSuffix(".git")
        }

        return url
    }

    /**
     * Check if a valid Claude session exists for the given project.
     * Valid sessions are non-empty .jsonl files that are not agent sub-sessions.
     */
    private fun checkClaudeSessionExists(projectPath: String): Boolean {
        return try {
            val userHome = System.getProperty("user.home")
            val encodedPath = projectPath.replace("/", "-").replace("\\", "-")
            val claudeProjectDir = File("$userHome/.claude/projects/$encodedPath")

            if (!claudeProjectDir.exists() || !claudeProjectDir.isDirectory) {
                return false
            }

            // Look for non-empty .jsonl files that are not agent sessions
            claudeProjectDir.listFiles()?.any { file ->
                file.isFile &&
                    file.name.endsWith(".jsonl") &&
                    !file.name.startsWith("agent-") &&
                    file.length() > 0
            } ?: false
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Error checking Claude session", mapOf("error" to e.toString()))
            false
        }
    }

    /**
     * Get the appropriate Claude CLI flags based on session existence.
     * Returns "--continue" if a valid session exists, empty string otherwise.
     */
    private fun getClaudeContinueFlag(projectPath: String?): String {
        if (projectPath == null) return ""
        return if (checkClaudeSessionExists(projectPath)) "--continue" else ""
    }
}
