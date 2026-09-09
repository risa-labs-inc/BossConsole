package ai.rever.boss.search

import ai.rever.boss.plugin.api.FileMatch
import ai.rever.boss.plugin.api.FileReplaceResult
import ai.rever.boss.plugin.api.ProjectSearchProvider
import ai.rever.boss.plugin.api.ReplaceSummary
import ai.rever.boss.services.editor.EditorAPIAccess
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Host implementation of the plugin-facing [ProjectSearchProvider] (boss-plugin-api 1.0.87):
 * project-wide content search, and replace scoped to an explicit file list.
 *
 * Design notes:
 * - File walking is the same shape as [FileIndexer] (skip VCS/build/VCS-adjacent
 *   directories), but the scan is on demand: files are read at search time, so a
 *   fresh checkout needs no reindex. An mtime-keyed result cache makes a repeat
 *   search with the same query and options cheap.
 * - Files containing a NUL byte (binary) or larger than [MAX_FILE_SIZE] are
 *   skipped, so a search over a repo with binaries stays fast and sane.
 * - `wholeWord` wraps the pattern in `\b...\b`, which for regex queries
 *   composes with the caller's pattern.
 * - Replace never touches a file the caller did not name. Open buffers are
 *   edited through the editor's undoable path (EditorAPIAccess.applyEdit);
 *   closed files are written to disk directly.
 */
class ContentSearchService(
    private val projectPathProvider: () -> String?,
    // How the search reaches the editor's live buffers. Injected, not the global
    // EditorAPIAccess, because that resolves through a process-wide last-window-wins
    // DefaultPlugin - so window 1's search would edit buffers in window 2's editor.
    // DefaultPlugin passes a bridge over its OWN (window-scoped) getPluginAPI; the
    // default keeps the old global behaviour for callers and tests that do not care.
    private val bufferBridge: EditorBufferBridge = GlobalEditorBufferBridge,
    // The files with an editor tab open in this window, or null for "unknown".
    // A buffer can only exist behind an open tab, so a search consults the bridge
    // ONLY for these paths - without this it made one bridge call per walked file
    // (tens of thousands per search on a real repo) to learn "no buffer" for all
    // but a handful. Null preserves the old ask-for-every-file behaviour, which is
    // the safe answer whenever the host cannot enumerate the open tabs.
    //
    // The PROVIDER is nullable too: no provider means no Main hop at all, which
    // is what unit tests and non-UI hosts (no Compose snapshot to snapshot) get.
    private val openEditorPathsProvider: (() -> Set<String>?)? = null,
) : ProjectSearchProvider {
    private val logger = BossLogger.forComponent("ContentSearchService")

    // Access-ordered and self-evicting: at capacity the ELDEST entry is dropped, not
    // the whole map. The old wholesale clear() meant a project above the cap wiped the
    // cache on nearly every scanned file, so the repeat search this exists for hit on
    // nothing. Guarded by `synchronized(cache)` at every access, so a plain
    // LinkedHashMap (not a concurrent map) is safe.
    private val cache =
        object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean = size > MAX_CACHE_ENTRIES
        }

    /**
     * The one search path.
     *
     * [excludePattern] is applied during the WALK, so [maxResults] caps matches the
     * caller wants rather than matches it is about to discard. Filtering the returned
     * list instead - which the codebase panel used to do, with its own copy of the glob
     * compiler - silently returns fewer results than exist whenever the excluded files
     * are reached first.
     */
    override suspend fun searchInProject(
        query: String,
        pathPattern: String?,
        excludePattern: String?,
        isRegex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        maxResults: Int,
    ): List<FileMatch> {
        val projectPath = projectPathProvider() ?: return emptyList()
        if (query.isEmpty()) return emptyList()

        val regex =
            buildRegex(query, isRegex, caseSensitive, wholeWord)
                ?: run {
                    // A pattern the regex compiler rejects is a USER error (a half-typed
                    // `foo(`), not "no matches" - say so, because the empty list is the
                    // same shape an honest no-result returns.
                    logger.warn(
                        LogCategory.GENERAL,
                        "search rejected: invalid regex pattern",
                        mapOf("query" to query.take(120)),
                    )
                    return emptyList()
                }
        val rawOpenPaths = snapshotOpenEditorPaths()
        return withContext(Dispatchers.IO) {
            // The cancellation check the scan hands to the matcher: a caller-
            // supplied pattern can wedge a thread (see [InterruptibleText]), and
            // `ensureActive()` below only runs between files.
            val job = coroutineContext[Job]
            val isCancelled = { job != null && !job.isActive }
            val results = mutableListOf<FileMatch>()
            // Snapshotted once per search, not per file - the whole point.
            val openPaths = openBufferLookupSet(projectPath, rawOpenPaths)
            for (file in walkProjectFiles(projectPath, pathPattern, excludePattern)) {
                ensureActive()
                if (results.size >= maxResults) break

                // An open file is searched as the user sees it, not as the disk
                // has it. Otherwise a replace into a live buffer - or any
                // unsaved edit - leaves the results tree reporting matches the
                // editor no longer shows. The bridge is only asked for files that
                // can actually have a buffer (an open editor tab); null means the
                // open set is unknown and every file is asked, as before.
                val buffer =
                    if (openPaths == null || file.absolutePath in openPaths) {
                        bufferBridge.readBuffer(file.absolutePath)
                    } else {
                        null
                    }
                val cacheKeyArg =
                    cacheKey(projectPath, query, pathPattern, excludePattern, isRegex, caseSensitive, wholeWord, file.absolutePath)
                val key = cacheKeyArg + if (buffer != null) "|buf" else "|disk"
                // The freshness stamp combines BOTH signals for an open file, because
                // each alone has a blind spot:
                // - mtime alone misses buffer edits (an unsaved edit does not touch it),
                //   so the pre-edit matches would be served from cache;
                // - buffer version alone misses a close-reopen. documentVersion is
                //   per-document and restarts when a document is created, so closing a
                //   tab, changing the file on disk and reopening it yields the same key
                //   AND the same version - and the pre-change matches come back.
                val stamp =
                    if (buffer != null) buffer.version * STAMP_MIX + file.lastModified() else file.lastModified()
                val matches: List<FileMatch> =
                    synchronized(cache) {
                        val cached = cache[key]
                        if (cached != null && cached.stamp == stamp) cached.matches else null
                    }
                        ?: scanText(
                            file = file,
                            text = buffer?.content ?: readTextOrNull(file) ?: continue,
                            regex = regex,
                            projectRoot = File(projectPath),
                            isCancelled = isCancelled,
                        )?.also { found ->
                            // Empty results are not cached: on a large project most files
                            // match nothing, and caching them all filled the map and tripped
                            // the wholesale clear below, so a repeat search - the one this
                            // cache exists for - hit on nothing. Re-scanning a no-match file
                            // is cheap; evicting a real hit is not.
                            if (found.isNotEmpty()) {
                                synchronized(cache) { cache[key] = CacheEntry(stamp, found) }
                            }
                        } ?: continue

                for (match in matches) {
                    results.add(match)
                    if (results.size >= maxResults) break
                }
            }
            results.take(maxResults)
        }
    }

    /**
     * Replaces in an EXPLICIT file list - the search-side caps do not apply
     * here, and that is deliberate: [searchInProject] bounds what the user SEES
     * ([maxResults], [MAX_MATCHES_PER_FILE]) because a list is a view, while a
     * half-transformed file is worse than an unhurried one, so
     * [computeReplaced] applies every match.
     *
     * The consequence a consumer has to surface: a file whose matches were
     * capped at 500 in the search view can have thousands replaced, so the
     * [ReplaceSummary] counts are NOT the search counts. Never present the
     * displayed match count as the applied count, or vice versa.
     */
    override suspend fun replaceInProject(
        query: String,
        replacement: String,
        files: List<String>,
        isRegex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        dryRun: Boolean,
    ): ReplaceSummary {
        val projectPath = projectPathProvider() ?: return ReplaceSummary(0, 0, emptyList(), dryRun)
        if (query.isEmpty() || files.isEmpty()) return ReplaceSummary(0, 0, emptyList(), dryRun)
        val regex =
            buildRegex(query, isRegex, caseSensitive, wholeWord)
                ?: run {
                    // Same reasoning as searchInProject: a bad pattern is an error the
                    // caller should see, not a zero-replacement success.
                    logger.warn(
                        LogCategory.GENERAL,
                        "replace rejected: invalid regex pattern",
                        mapOf("query" to query.take(120)),
                    )
                    return ReplaceSummary(0, 0, emptyList(), dryRun)
                }

        return withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val isCancelled = { job != null && !job.isActive }
            var total = 0
            var filesReplaced = 0
            val perFile = mutableListOf<FileReplaceResult>()
            for (rawPath in files) {
                ensureActive()
                val file = resolveFile(rawPath, projectPath)
                val result =
                    if (file == null) {
                        FileReplaceResult(rawPath, 0, "outside the project")
                    } else {
                        replaceInOneFile(file, projectPath, regex, replacement, isRegex, dryRun, isCancelled)
                    }
                if (result.error == null && result.replacements > 0) {
                    total += result.replacements
                    filesReplaced++
                }
                perFile.add(result)
            }
            ReplaceSummary(filesReplaced, total, perFile, dryRun)
        }
    }

    /**
     * The open-tab set as [Dispatchers.Main] sees it.
     *
     * It is Compose snapshot state (SplitView's _rootNode), so it is snapshotted
     * on Main, where the snapshot lives, BEFORE the scan drops to IO: a
     * background read can observe a different snapshot than the UI is showing
     * (docs/THREADING.md is direct about which dispatcher owns UI state), and
     * the set is computed once per search anyway. The canonicalise work stays
     * on IO.
     *
     * Guarded: the provider is optional, and a caller that supplies none
     * (unit tests, non-UI hosts) must not pay a hop to Main on every search -
     * the set is null, so there is nothing to snapshot.
     */
    private suspend fun snapshotOpenEditorPaths(): Set<String>? {
        val provider = openEditorPathsProvider ?: return null
        return withContext(Dispatchers.Main) { provider() }
    }

    // ===== Internals =====

    private fun buildRegex(
        query: String,
        isRegex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
    ): Regex? {
        val pattern =
            if (isRegex) {
                query
            } else {
                Regex.escape(query)
            }
        val wrapped = if (wholeWord) "\\b(?:$pattern)\\b" else pattern
        return try {
            if (caseSensitive) Regex(wrapped) else Regex(wrapped, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves a caller-supplied path, and refuses anything outside the project.
     *
     * Replace is a WRITE reached from the `project_replace` MCP tool, whose permission
     * is named `project.replace` and whose description says "in an EXPLICIT list of
     * files". Both imply confinement that absolute paths did not have: a caller holding
     * that permission could rewrite any file the process can write, `~/.zshrc` included.
     * Relative traversal (`../../etc`) is covered too, since the check is on the
     * canonical path.
     *
     * Returns null when the path escapes; callers report that as a per-file error
     * rather than failing the whole batch.
     *
     * Known limit: a BROKEN symlink (target absent) does not resolve to a real
     * path, so it falls back to the canonical check, which passes for a link
     * inside the project. A write through it would materialise the file at the
     * target, but creating such a link already requires writing inside the
     * project, which a plugin holding `project.replace` can do with plain file
     * I/O anyway - the confinement protects sloppy callers, not adversarial
     * ones.
     */
    internal fun resolveFile(
        rawPath: String,
        projectPath: String,
    ): File? {
        val f = File(rawPath)
        val candidate = if (f.isAbsolute) f else File(projectPath, rawPath)
        // Confinement is decided on the CANONICAL form (so `../` and symlink escapes
        // are caught), but the NON-canonical candidate is what we return and use. The
        // editor keys its open buffers by the plain absolute path a tab was opened
        // with; returning the canonical path here made readBuffer miss the buffer of a
        // file open under a symlinked checkout (/tmp vs /private/tmp), so replace wrote
        // to disk under an unsaved buffer and the next save clobbered it.
        val root = runCatching { File(projectPath).canonicalFile }.getOrNull() ?: return null
        val resolvedCanon = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separatorChar
        var inside = resolvedCanon.path == root.path || resolvedCanon.path.startsWith(rootPath)
        // java.io.File's canonical path does NOT follow symlinks on Windows (NIO's
        // realPath does, on every platform), so a link inside the project pointing
        // outside passes the canonical check there. When both sides resolve to a
        // real path, re-verify on those; both are real-pathed so a symlinked or
        // junctioned project root stays consistent.
        if (inside) {
            val rootReal = runCatching { File(projectPath).toPath().toRealPath() }.getOrNull()
            val candReal = runCatching { candidate.toPath().toRealPath() }.getOrNull()
            if (rootReal != null && candReal != null) {
                val realRootPath = rootReal.toString().trimEnd(File.separatorChar) + File.separatorChar
                inside = candReal == rootReal || candReal.toString().startsWith(realRootPath)
            }
        }
        return if (inside) candidate else null
    }

    /** Canonical path when resolvable, else the absolute one - identity for cycle detection. */
    private fun canonicalOrPath(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /**
     * The open-tab paths expanded into every spelling the walk might produce, or null
     * when the open set is unknown.
     *
     * The walk yields paths under the project root AS OPENED, while a tab can hold the
     * canonical form or vice versa (a project under /tmp is really /private/tmp on
     * macOS - the same mismatch resolveFile documents). Missing a live buffer here
     * would silently search stale disk content, so each tab path is added raw,
     * canonical, and re-expressed under the raw root when the roots differ. O(open
     * tabs), so the cost is a handful of canonicalise calls per search.
     */
    private fun openBufferLookupSet(
        projectPath: String,
        raw: Set<String>?,
    ): Set<String>? {
        if (raw == null) return null
        return raw
            .map { if (File(it).isAbsolute) File(it) else File(projectPath, it) }
            .flatMap { bufferPathSpellings(it, projectPath) }
            .toHashSet()
    }

    /**
     * Every spelling a live buffer for [file] can be registered under, given the
     * project root the walk runs under: the walk's absolute path, the canonical
     * path, and the canonical path re-expressed under the walk's raw root when
     * the two differ (a project under /tmp is really /private/tmp on macOS -
     * the same mismatch [resolveFile] documents).
     *
     * Shared by the search side ([openBufferLookupSet], a set over all open tabs)
     * and the replace side (one file at a time): the tab can hold ANY of the
     * three spellings, and the replace path used to ask under one only. Missing
     * the buffer there meant a disk write under the user's open, unsaved tab -
     * clobbered by the next save.
     */
    private fun bufferPathSpellings(
        file: File,
        projectPath: String,
    ): List<String> {
        val abs = file.absolutePath
        val canon = canonicalOrPath(file)
        val out = linkedSetOf(abs, canon)
        val root = projectPath.trimEnd(File.separatorChar)
        val rootCanon = canonicalOrPath(File(projectPath)).trimEnd(File.separatorChar)
        if (root != rootCanon && canon.startsWith(rootCanon + File.separatorChar)) {
            out.add(root + canon.removePrefix(rootCanon))
        }
        return out.toList()
    }

    private fun cacheKey(
        projectRoot: String,
        query: String,
        pathPattern: String?,
        excludePattern: String?,
        isRegex: Boolean,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        path: String,
        // projectRoot is part of the key because a cached FileMatch carries a path
        // RELATIVE to the root it was found under. The same absolute file walked under
        // a different root (switching between nested projects) would otherwise get its
        // old relative path served from cache until its mtime changed, and a
        // result-click would resolve it against the new root - the wrong file.
    ): String = "$projectRoot|$query|$pathPattern|$excludePattern|$isRegex|$caseSensitive|$wholeWord|$path"

    private fun walkProjectFiles(
        projectPath: String,
        pathPattern: String?,
        excludePattern: String?,
    ): Sequence<File> {
        val root = File(projectPath)
        // The root's canonical path is IN the set from the start, but the root is still
        // always entered (the isRoot branch below). Without seeding it, a symlink
        // `proj/link -> proj` had a canonical path that was not yet "seen", so it was
        // entered, its children registered their canonicals, and the real subtree was
        // then skipped as already-seen - matches came back as `link/src/...`.
        val rootCanon = canonicalOrPath(root)
        val seen = hashSetOf(rootCanon)
        val globs = pathPattern?.let { compileIncludeGlobs(it) }
        // Same compiler for both boxes: include and exclude take identical syntax,
        // so two implementations would be two sets of edge cases to keep in step.
        val excludes = excludePattern?.let { compileIncludeGlobs(it) }
        return root
            .walkTopDown()
            // onEnter DESCENDS on true. The predicate has to be "not skipped",
            // and the root has to pass it: the previous form returned true only
            // for the skip list, so the walk entered node_modules/.git and
            // nothing else - including refusing to enter the project root, which
            // made every search return zero results.
            .onEnter { dir ->
                // A directory symlink pointing at an ancestor makes walkTopDown recurse
                // forever, and ensureActive() only helps if something cancels. A directory
                // is entered once per canonical path; the root always enters (its canonical
                // is pre-seeded, so a link back to it is what gets refused).
                val isRoot = dir.absolutePath == root.absolutePath
                val notSkipped = isRoot || dir.name !in SKIP_DIRECTORIES
                notSkipped && (isRoot || seen.add(canonicalOrPath(dir)))
            }.filter { it.isFile }
            // Skip symlinked files, so the walk and replaceInProject agree: replace
            // refuses a path resolving outside the project (resolveFile), and a followed
            // symlink is exactly such a path. Directory-symlink cycles are handled above.
            .filterNot {
                runCatching {
                    java.nio.file.Files
                        .isSymbolicLink(it.toPath())
                }.getOrDefault(false)
            }.filter { it.length() <= MAX_FILE_SIZE }
            .filter { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                val included = globs.isNullOrEmpty() || globs.any { it.matches(rel) }
                included && excludes?.any { it.matches(rel) } != true
            }.asSequence()
    }

    /**
     * Compile an include pattern the way VS Code's "files to include" box does:
     * comma-separated alternatives, and a pattern with no path separator
     * matching at any depth. Matched against the whole relative path, the
     * "*.kt" a user actually types found only root-level files.
     *
     * A literal name yields two patterns - the file itself, and everything
     * under a folder of that name. Returns null for a blank pattern.
     */
    private fun compileIncludeGlobs(pattern: String): List<Regex>? {
        val parts = pattern.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val expanded = mutableListOf<String>()
        for (raw in parts) {
            val p = raw.removePrefix("./").removePrefix("/").removeSuffix("/")
            if (p.isEmpty()) continue
            val hasWildcard = p.indexOf('*') >= 0 || p.indexOf('?') >= 0
            when {
                p.startsWith(ANY_DEPTH_PREFIX) -> {
                    expanded.add(p)
                }

                hasWildcard -> {
                    expanded.add(ANY_DEPTH_PREFIX + "/" + p)
                }

                else -> {
                    expanded.add(ANY_DEPTH_PREFIX + "/" + p)
                    expanded.add(ANY_DEPTH_PREFIX + "/" + p + "/" + ANY_DEPTH_PREFIX)
                }
            }
        }
        return expanded.map { globToRegex(it) }
    }

    /** File contents, or null when it cannot be read (permissions, races). */
    private fun readTextOrNull(file: File): String? =
        try {
            file.readText()
        } catch (e: Exception) {
            null
        }

    private fun scanText(
        file: File,
        text: String,
        regex: Regex,
        projectRoot: File,
        isCancelled: () -> Boolean,
    ): List<FileMatch>? {
        return try {
            val relative = file.relativeTo(projectRoot).path.replace('\\', '/')
            if ('\u0000' in text) return null
            val lineMap = LineMap(text)
            val matches = mutableListOf<FileMatch>()
            // The matcher reads through [InterruptibleText] rather than the raw
            // string, so a cancel lands inside a wedged pattern instead of at the
            // next suspension point.
            val searchable = InterruptibleText(text, isCancelled)
            var searchFrom = 0
            while (searchFrom <= text.length && matches.size < MAX_MATCHES_PER_FILE) {
                val m = regex.find(searchable, searchFrom) ?: break
                matches.add(
                    FileMatch(
                        path = relative,
                        line = lineMap.lineOf(m.range.first),
                        column = lineMap.columnOf(m.range.first),
                        matchLength = m.value.length,
                        contextLine = lineMap.lineText(lineMap.lineOf(m.range.first)),
                    ),
                )
                // A zero-length match (\b, an empty alternation) has
                // range.last < range.first, so "last + 1" rescanned the same
                // offset until the per-file cap - 500 hits at one position.
                searchFrom = maxOf(m.range.last + 1, m.range.first + 1)
            }
            matches
        } catch (e: CancellationException) {
            // A cancel raised inside the matcher is a CANCELLATION of this
            // search, not a scan error: rethrow so the withContext unwinds
            // instead of the loop quietly moving on to the next file.
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 1-based line numbers and columns from precomputed line-start offsets. */
    internal class LineMap(
        val text: String,
    ) : LineOffsets {
        val lineStarts: IntArray

        init {
            val starts = mutableListOf(0)
            for (i in text.indices) {
                if (text[i] == '\n') starts.add(i + 1)
            }
            lineStarts = starts.toIntArray()
        }

        /** 1-based line number containing [offset]. */
        override fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = lineStarts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo + 1
        }

        /** 1-based column of [offset] within its line. */
        override fun columnOf(offset: Int): Int = offset - lineStarts[lineOf(offset) - 1] + 1

        /**
         * Full text of 1-based line [line], INCLUDING its trailing newline when it
         * has one (the last line of a file without a final newline does not).
         * FileMatch.contextLine carries it through, and the tests pin that.
         */
        fun lineText(line: Int): String {
            val start = lineStarts[line - 1]
            val end = if (line < lineStarts.size) lineStarts[line] else text.length
            return text.substring(start, end)
        }
    }

    private suspend fun replaceInOneFile(
        file: File,
        projectRoot: String,
        regex: Regex,
        replacement: String,
        isRegex: Boolean,
        dryRun: Boolean,
        isCancelled: () -> Boolean,
    ): FileReplaceResult {
        if (!file.isFile) return FileReplaceResult(file.path, 0, "not a file")
        if (file.length() > MAX_FILE_SIZE) return FileReplaceResult(file.path, 0, "file too large")

        return try {
            // Open buffers go through the editor's undoable path - under every
            // spelling the tab may hold the file (see bufferPathSpellings).
            val buffer =
                bufferPathSpellings(file, projectRoot).firstNotNullOfOrNull { bufferBridge.readBuffer(it) }
            if (buffer != null) {
                replaceInBuffer(file, buffer.content, buffer.version, regex, replacement, isRegex, dryRun, isCancelled)
            } else {
                // UTF-8 in, UTF-8 out. A file in another single-byte encoding has no NUL
                // bytes, so it passes the binary check, and round-tripping it through
                // readText/writeText replaces its undecodable bytes with U+FFFD - a
                // silent rewrite of bytes the user never asked to touch. Detect that the
                // decode was lossy and refuse, rather than corrupting the file.
                val text = file.readText()
                if ('\u0000' in text) return FileReplaceResult(file.path, 0, "binary file")
                if ('\uFFFD' in text) return FileReplaceResult(file.path, 0, "not valid UTF-8")
                val outcome = computeReplaced(text, regex, replacement, isRegex, isCancelled)
                if (!dryRun && outcome.count > 0) writeAtomically(file, outcome.text)
                FileReplaceResult(file.path, outcome.count, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileReplaceResult(file.path, 0, e.message ?: "replace failed")
        }
    }

    /**
     * Replace in a live buffer as ONE version-guarded, undoable edit.
     *
     * The replaced content is computed the same way as the disk path
     * ([computeReplaced]), then applied as the single span that actually changed
     * (common prefix and suffix trimmed off). One edit rather than one per match
     * buys three things at once:
     * - the replacement string is expanded EXACTLY as on disk - the two paths can
     *   no longer disagree about whether `$1` is a capture reference or literal text
     *   (the bug that let the same replace write different bytes depending on whether
     *   the file happened to be open);
     * - one `expectedVersion` check covers the whole operation, so a keystroke that
     *   lands mid-replace rejects it cleanly instead of shifting later edits onto
     *   stale offsets;
     * - one undo step, which is what the user means by "undo the replace".
     */
    private suspend fun replaceInBuffer(
        file: File,
        text: String,
        version: Long,
        regex: Regex,
        replacement: String,
        isRegex: Boolean,
        dryRun: Boolean,
        isCancelled: () -> Boolean,
    ): FileReplaceResult {
        val outcome = computeReplaced(text, regex, replacement, isRegex, isCancelled)
        if (dryRun || outcome.count == 0) return FileReplaceResult(file.path, outcome.count, null)

        val span = changedSpan(text, outcome.text)
        val lineMap = LineMap(text)
        val range = BufferEditRange.ofSpan(lineMap, span.oldStart, span.oldEndExclusive)
        val newText = outcome.text.substring(span.newStart, span.newEndExclusive)
        val result =
            bufferBridge.applyEdit(
                path = file.absolutePath,
                startLine = range.startLine,
                startCol = range.startCol,
                endLine = range.endLine,
                endCol = range.endCol,
                newText = newText,
                expectedVersion = version,
            ) ?: return FileReplaceResult(file.path, 0, "editor buffer no longer available")
        return if (result.applied) {
            FileReplaceResult(file.path, outcome.count, null)
        } else {
            FileReplaceResult(file.path, 0, result.reason ?: "edit rejected")
        }
    }

    /**
     * Writes [text] to [file] atomically: a sibling temp file, then an atomic rename.
     * A crash or I/O error mid-write must not leave a truncated source file with no
     * backup - the plugin installer promotes its jar the same way, for the same reason.
     *
     * Known: the rename moves a REGULAR file over the target, so a target that is a
     * symlink becomes a regular file. The project walk filters symlinked files out,
     * but [replaceInProject] takes an explicit caller-supplied list, and a symlink
     * pointing inside the project passes [resolveFile] - so this is only reachable
     * that way, and a replacement there replaces the link, not its referent.
     *
     * The same trade-off is a TOCTOU window, and the two halves of it see
     * DIFFERENT resolutions: [resolveFile] decides confinement on the canonical
     * path, then deliberately returns the NON-canonical one (buffer keys match
     * the tab's raw spelling, so that is the right call), and the write goes to
     * the non-canonical path. A symlink swapped between check and write is not
     * caught here; the window is bounded by the write running immediately after
     * the check in the same call, and the exposure is the explicit-list path
     * above.
     */
    private fun writeAtomically(
        file: File,
        text: String,
    ) {
        // createTempFile is 0600 and ATOMIC_MOVE carries the temp's attributes onto the
        // destination, so without this an executable script loses +x and a shared file
        // becomes owner-only. Capture the target's POSIX bits first, reapply after.
        // Null on a non-POSIX filesystem (the getter throws) - nothing to carry there.
        val perms =
            runCatching {
                java.nio.file.Files
                    .getPosixFilePermissions(file.toPath())
            }.getOrNull()
        // createTempFile requires a prefix of at least 3 chars; a file named "a" or
        // "go" otherwise fails its replace with an opaque per-file error.
        val tmp = File.createTempFile(file.name.padEnd(3, '_'), ".tmp", file.parentFile)
        try {
            tmp.writeText(text)
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            perms?.let {
                runCatching {
                    java.nio.file.Files
                        .setPosixFilePermissions(file.toPath(), it)
                }
            }
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Rare (a temp on a different filesystem); fall back to a plain replace.
            // A plain `tmp.copyTo(file, overwrite = true)` would TRUNCATE the target
            // first - the exact torn state the atomic move exists to prevent, in this
            // rarer case. So move the target aside, move the temp into place, and
            // restore the aside if the second move fails. If even the first move
            // fails (a locked target), FAIL the replace: risking the original
            // content is worse than one file reported as "replace failed".
            val aside = File(file.parentFile, file.name + ".aside-" + System.nanoTime())
            if (!file.renameTo(aside)) {
                tmp.delete()
                throw java.io.IOException("Could not move \"$file\" aside for the non-atomic replace")
            }
            try {
                Files.move(tmp.toPath(), file.toPath())
            } catch (moveFailed: java.io.IOException) {
                if (!aside.renameTo(file)) {
                    logger.error(
                        LogCategory.FILE,
                        "non-atomic replace failed and the target could not be restored; " +
                            "the old content is at ${aside.path}",
                    )
                }
                throw moveFailed
            }
            aside.delete()
            perms?.let {
                runCatching {
                    java.nio.file.Files
                        .setPosixFilePermissions(file.toPath(), it)
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** The result of a bounded replace: the new text and how many matches it replaced. */
    internal data class ReplaceOutcome(
        val text: String,
        val count: Int,
    )

    /**
     * Replaces EVERY match, via Java's reference `Matcher`
     * expansion - NOT a hand-written one.
     *
     * `appendReplacement`/`appendTail` are the exact semantics
     * [ProjectSearchProvider.replaceInProject]'s KDoc promises: `$1`..`$9` and
     * `${name}` capture references and `\` escaping for a regex query, and - via
     * [java.util.regex.Matcher.quoteReplacement] - a truly literal replacement for a
     * literal query, so a `$` or `\` in the replacement text is not misread as a
     * group reference. It also advances past zero-width matches on its own, and
     * throws on a bad group reference exactly as `Regex.replace` does, rather than
     * silently substituting the empty string. The returned [ReplaceOutcome.count] is
     * the authority for both the dry-run preview and the applied count, so the two
     * can never disagree.
     */
    internal fun computeReplaced(
        text: String,
        regex: Regex,
        replacement: String,
        isRegex: Boolean,
        isCancelled: () -> Boolean = { false },
    ): ReplaceOutcome {
        // The matcher reads through [InterruptibleText] too: replace is
        // reachable from a plugin with the same untrusted patterns, and a
        // wedged file here holds no lock but still parks an IO thread.
        val matcher = regex.toPattern().matcher(InterruptibleText(text, isCancelled))
        val repl =
            if (isRegex) {
                replacement
            } else {
                java.util.regex.Matcher
                    .quoteReplacement(replacement)
            }
        val sb = StringBuffer(text.length)
        var count = 0
        // No match cap here: the file is already bounded to MAX_FILE_SIZE, and a
        // capped replace leaves the file half-transformed with a success report - worse
        // than the work of finishing. The loop terminates because a file has finitely
        // many matches (zero-width matches self-advance via appendReplacement).
        while (matcher.find()) {
            matcher.appendReplacement(sb, repl)
            count++
        }
        matcher.appendTail(sb)
        return ReplaceOutcome(sb.toString(), count)
    }

    /** The single span that differs between [old] and [new] (common ends trimmed). */
    internal data class Span(
        val oldStart: Int,
        val oldEndExclusive: Int,
        val newStart: Int,
        val newEndExclusive: Int,
    )

    internal fun changedSpan(
        old: String,
        new: String,
    ): Span {
        val min = minOf(old.length, new.length)
        var p = 0
        while (p < min && old[p] == new[p]) p++
        var s = 0
        while (s < min - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        return Span(
            oldStart = p,
            oldEndExclusive = old.length - s,
            newStart = p,
            newEndExclusive = new.length - s,
        )
    }

    /** Glob with `**` path segments and `*`/`?` within a segment. */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> {
                    sb.append("(?:[^/]+/)*")
                    i += 3
                }

                glob.startsWith("**", i) -> {
                    sb.append(".*")
                    i += 2
                }

                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }

                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }

                else -> {
                    sb.append(Regex.escape(c.toString()))
                    i++
                }
            }
        }
        return Regex(sb.toString())
    }

    /**
     * [stamp] is the file's mtime, mixed with the buffer version when one is open.
     * See the call site for why neither signal is sufficient alone.
     */
    private data class CacheEntry(
        val stamp: Long,
        val matches: List<FileMatch>,
    )

    private companion object {
        const val MAX_FILE_SIZE: Long = 1_048_576 // 1 MiB
        const val MAX_MATCHES_PER_FILE = 500
        const val MAX_CACHE_ENTRIES = 500

        /** Odd multiplier so a buffer-version bump and an mtime change cannot cancel out. */
        const val STAMP_MIX = 1_000_003L

        /** Recursive-wildcard glob segment; kept out of literals with a slash. */
        const val ANY_DEPTH_PREFIX = "**"

        val SKIP_DIRECTORIES =
            setOf(
                ".git",
                ".hg",
                ".svn",
                "node_modules",
                ".build",
                "build",
                ".gradle",
                ".idea",
                "dist",
                "out",
                "target",
                "__pycache__",
            )
    }
}

/**
 * How [ContentSearchService] reaches the editor's live buffers. An interface so it is
 * WINDOW-SCOPED (bound to a specific DefaultPlugin's registry, not the process global)
 * and fakeable in tests - the replace-into-a-buffer path is otherwise unreachable.
 */
interface EditorBufferBridge {
    suspend fun readBuffer(path: String): ai.rever.boss.plugin.api.BufferSnapshot?

    suspend fun applyEdit(
        path: String,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        newText: String,
        expectedVersion: Long,
    ): ai.rever.boss.plugin.api.EditResult?
}

/** Backs onto the process-global [EditorAPIAccess]; the default, for callers with no window. */
object GlobalEditorBufferBridge : EditorBufferBridge {
    override suspend fun readBuffer(path: String) = EditorAPIAccess.readBuffer(path)

    override suspend fun applyEdit(
        path: String,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        newText: String,
        expectedVersion: Long,
    ) = EditorAPIAccess.applyEdit(path, startLine, startCol, endLine, endCol, newText, expectedVersion)
}

/**
 * A [CharSequence] view of [text] for matcher work that stops the moment
 * [isCancelled] goes true.
 *
 * A caller-supplied pattern is not trusted: `(a+)+$` against a long line is
 * catastrophic backtracking, and the Java matcher is not interruptible, so
 * without a check inside the character stream a wedged file pins a
 * `Dispatchers.IO` thread for as long as the pattern keeps spinning - and
 * `project_search` is reachable from a plugin, not just the search box.
 * `ensureActive()` between files cannot reach this: it never suspends.
 *
 * The check reads the CALLER's job, not `Thread.interrupted()`: structured
 * concurrency does not interrupt a thread running a CPU-bound loop, so the
 * flag would stay clear for the whole wedged run. The matcher re-reads
 * characters on every backtrack step, so each read is the check, and a
 * cancel lands mid-pattern rather than at the next suspension point.
 *
 * A tripped check throws [CancellationException], so the callers rethrow it
 * past their `catch (Exception)` - which would otherwise turn the cancel
 * into a "scan error" and let the loop move on to the next file.
 */
internal class InterruptibleText(
    private val text: String,
    private val isCancelled: () -> Boolean,
) : CharSequence {
    override val length: Int
        get() = text.length

    override fun get(index: Int): Char {
        if (isCancelled()) throw CancellationException("search cancelled")
        return text[index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence {
        if (isCancelled()) throw CancellationException("search cancelled")
        return text.subSequence(startIndex, endIndex)
    }

    override fun toString(): String = text
}
