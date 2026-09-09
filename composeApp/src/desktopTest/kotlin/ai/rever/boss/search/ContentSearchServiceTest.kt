package ai.rever.boss.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The find-in-files engine behind the codebase panel's SEARCH tab and the
 * `project_search` MCP tool.
 *
 * The first two tests pin the walk itself, which had an inverted `onEnter`
 * predicate: `walkTopDown().onEnter { … }` descends on **true**, and the
 * predicate returned true only for the skip list - so the walk entered
 * `node_modules` and `.git`, refused to enter the project root, and every
 * search in the app returned zero results.
 */
class ContentSearchServiceTest {
    private fun tree(root: File) {
        File(root, "top.kt").writeText("val needle = 1\n")
        File(root, "src/main").mkdirs()
        File(root, "src/main/Deep.kt").writeText("// needle here\nfun f() {}\n")
        File(root, "src/main/Other.java").writeText("// needle in java\n")
        File(root, "node_modules/pkg").mkdirs()
        File(root, "node_modules/pkg/index.js").writeText("needle in a dependency\n")
        File(root, "build").mkdirs()
        File(root, "build/gen.kt").writeText("needle in generated output\n")
    }

    @Test
    fun `finds matches at the project root and at depth`(
        @TempDir dir: File,
    ) = runBlocking {
        tree(dir)
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val hits = service.searchInProject(query = "needle")

        val paths = hits.map { it.path }.toSet()
        assertTrue("top.kt" in paths, "root-level file not scanned: $paths")
        assertTrue("src/main/Deep.kt" in paths, "nested file not scanned: $paths")
    }

    @Test
    fun `skips the ignored directories`(
        @TempDir dir: File,
    ) = runBlocking {
        tree(dir)
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val paths = service.searchInProject(query = "needle").map { it.path }

        assertTrue(paths.none { it.startsWith("node_modules/") }, "walked node_modules: $paths")
        assertTrue(paths.none { it.startsWith("build/") }, "walked build: $paths")
    }

    @Test
    fun `a bare extension glob matches at any depth`(
        @TempDir dir: File,
    ) = runBlocking {
        tree(dir)
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        // What a user actually types in "files to include". Matched against the
        // whole relative path, `*.kt` used to find only root-level files.
        val paths = service.searchInProject(query = "needle", pathPattern = "*.kt").map { it.path }.toSet()

        assertEquals(setOf("top.kt", "src/main/Deep.kt"), paths)
    }

    @Test
    fun `a comma separated include list matches any alternative`(
        @TempDir dir: File,
    ) = runBlocking {
        tree(dir)
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val paths =
            service
                .searchInProject(query = "needle", pathPattern = "*.java, *.kt")
                .map { it.path }
                .toSet()

        assertEquals(setOf("top.kt", "src/main/Deep.kt", "src/main/Other.java"), paths)
    }

    @Test
    fun `a directory include matches everything under it`(
        @TempDir dir: File,
    ) = runBlocking {
        tree(dir)
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val paths = service.searchInProject(query = "needle", pathPattern = "src").map { it.path }.toSet()

        assertEquals(setOf("src/main/Deep.kt", "src/main/Other.java"), paths)
    }

    // ---- exclude globs (moved here from the codebase plugin's PathGlob) ------
    //
    // These lived in the plugin, over its own copy of the glob compiler, filtering
    // the list the engine returned. That made the cap apply BEFORE the exclude, so
    // excluding a busy directory returned fewer results than existed. The exclude
    // now runs in the walk, and these cases moved with it.

    /** A tree the SKIP_DIRECTORIES list does not already hide, so excludes are what filters. */
    private fun excludeTree(root: File) {
        File(root, "src/main").mkdirs()
        File(root, "src/main/Main.kt").writeText("needle\n")
        File(root, "src/main/deep/More.kt").apply { parentFile.mkdirs() }.writeText("needle\n")
        File(root, "web/static").mkdirs()
        File(root, "web/static/app.min.js").writeText("needle\n")
        File(root, "web/static/app.js").writeText("needle\n")
        File(root, "module/gen").mkdirs()
        File(root, "module/gen/Out.kt").writeText("needle\n")
        File(root, "notsrc").mkdirs()
        File(root, "notsrc/Main.kt").writeText("needle\n")
    }

    private fun search(
        dir: File,
        exclude: String?,
    ): Set<String> =
        runBlocking {
            ContentSearchService(projectPathProvider = { dir.absolutePath })
                .searchInProject(
                    query = "needle",
                    pathPattern = null,
                    excludePattern = exclude,
                    isRegex = false,
                    caseSensitive = false,
                    wholeWord = false,
                    maxResults = 200,
                ).map { it.path }
                .toSet()
        }

    @Test
    fun `a blank exclude excludes nothing`(
        @TempDir dir: File,
    ) {
        excludeTree(dir)
        assertEquals(search(dir, null), search(dir, ""))
    }

    @Test
    fun `an extension exclude applies at any depth`(
        @TempDir dir: File,
    ) {
        excludeTree(dir)
        val paths = search(dir, "*.min.js")
        assertTrue("web/static/app.min.js" !in paths, paths.toString())
        assertTrue("web/static/app.js" in paths, paths.toString())
    }

    @Test
    fun `a bare folder name excludes everything under it, at any depth`(
        @TempDir dir: File,
    ) {
        excludeTree(dir)
        val paths = search(dir, "gen")
        assertTrue("module/gen/Out.kt" !in paths, paths.toString())
        assertTrue("src/main/Main.kt" in paths, paths.toString())
    }

    @Test
    fun `comma separated excludes are alternatives`(
        @TempDir dir: File,
    ) {
        excludeTree(dir)
        val paths = search(dir, "gen, *.min.js")
        assertTrue("module/gen/Out.kt" !in paths, paths.toString())
        assertTrue("web/static/app.min.js" !in paths, paths.toString())
        assertTrue("src/main/Main.kt" in paths, paths.toString())
    }

    @Test
    fun `a single-segment wildcard does not cross a separator`(
        @TempDir dir: File,
    ) {
        excludeTree(dir)
        val paths = search(dir, "src/*.kt")
        assertTrue("src/main/deep/More.kt" in paths, paths.toString())
    }

    @Test
    fun `an exclude is anchored, not a substring test`(
        @TempDir dir: File,
    ) {
        excludeTree(dir)
        val paths = search(dir, "src")
        assertTrue("src/main/Main.kt" !in paths, paths.toString())
        assertTrue("notsrc/Main.kt" in paths, "'src' must not exclude 'notsrc': $paths")
    }

    @Test
    fun `the exclude runs inside the walk, so the cap is not spent on excluded files`(
        @TempDir dir: File,
    ) {
        // The bug this whole change exists for. 30 files in noise/, 3 in keep/,
        // cap of 5: filtering AFTER the cap can return 0, because the cap is
        // reached before a single keep/ file is scanned.
        File(dir, "noise").mkdirs()
        repeat(30) { File(dir, "noise/n$it.kt").writeText("needle\n") }
        File(dir, "keep").mkdirs()
        repeat(3) { File(dir, "keep/k$it.kt").writeText("needle\n") }

        val paths =
            runBlocking {
                ContentSearchService(projectPathProvider = { dir.absolutePath })
                    .searchInProject(
                        query = "needle",
                        pathPattern = null,
                        excludePattern = "noise",
                        isRegex = false,
                        caseSensitive = false,
                        wholeWord = false,
                        maxResults = 5,
                    ).map { it.path }
                    .toSet()
            }

        assertEquals(3, paths.size, "the cap was spent on excluded files: $paths")
        assertTrue(paths.all { it.startsWith("keep/") }, paths.toString())
    }

    @Test
    fun `line and column are 1-based and point at the match`(
        @TempDir dir: File,
    ) = runBlocking {
        File(dir, "a.txt").writeText("first\n  needle\n")
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val hit = service.searchInProject(query = "needle").single()

        assertEquals(2, hit.line)
        assertEquals(3, hit.column)
        assertEquals("needle".length, hit.matchLength)
        assertEquals("  needle\n", hit.contextLine)
    }

    @Test
    fun `a zero-length regex match does not rescan the same offset`(
        @TempDir dir: File,
    ) = runBlocking {
        File(dir, "a.txt").writeText("ab\n")
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        // `x*` matches empty at every offset. Advancing by `range.last + 1`
        // never moved past a zero-length match, so this used to return the
        // per-file cap (500) of identical positions.
        val hits = service.searchInProject(query = "x*", isRegex = true)

        assertTrue(hits.size <= 4, "zero-length match looped: ${hits.size} hits")
    }

    @Test
    fun `a zero-length regex replace terminates instead of hanging`(
        @TempDir dir: File,
    ) = runBlocking {
        File(dir, "a.txt").writeText("abc\n")

        // The buffer path built its match list with no cap and no advance past
        // a zero-length match, so this grew a list until the app stopped
        // responding. It has to come back bounded.
        val summary =
            ContentSearchService(projectPathProvider = { dir.absolutePath }).replaceInProject(
                query = "x*",
                replacement = "-",
                files = listOf("a.txt"),
                isRegex = true,
                dryRun = true,
            )

        assertTrue(summary.totalReplacements <= 500, "unbounded match list: ${summary.totalReplacements}")
    }

    @Test
    fun `a per-file replace failure is reported, not swallowed`(
        @TempDir dir: File,
    ) = runBlocking {
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        // A path that is not a file: the summary has to carry the reason so a
        // caller can say why nothing changed.
        val summary =
            service.replaceInProject(
                query = "needle",
                replacement = "pin",
                files = listOf("does-not-exist.txt"),
                dryRun = false,
            )

        assertEquals(0, summary.totalReplacements)
        assertTrue(
            summary.files.any { !it.error.isNullOrBlank() },
            "no per-file error reported: ${summary.files}",
        )
    }

    @Test
    fun `the search-then-replace flow the panel performs actually writes`(
        @TempDir dir: File,
    ) = runBlocking {
        val a = File(dir, "src/A.kt").also { it.parentFile.mkdirs() }
        a.writeText("val needle = 1\nval other = needle\n")
        val b = File(dir, "B.kt")
        b.writeText("// needle\n")
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        // 1. Exactly what the SEARCH tab does: scan, then feed the result
        //    paths (project-relative, as reported) into a dry run.
        val hits = service.searchInProject(query = "needle")
        val paths = hits.map { it.path }.distinct()
        assertTrue(paths.isNotEmpty(), "no hits to replace")

        val preview =
            service.replaceInProject(
                query = "needle",
                replacement = "pin",
                files = paths,
                dryRun = true,
            )
        assertEquals(3, preview.totalReplacements, "dry run miscounted: ${preview.files}")
        assertTrue(preview.files.isNotEmpty(), "dry run reported no per-file rows to apply")

        // 2. Then applies using the paths the preview reported back.
        val applied =
            service.replaceInProject(
                query = "needle",
                replacement = "pin",
                files = preview.files.map { it.path },
                dryRun = false,
            )

        assertEquals(3, applied.totalReplacements, "apply reported nothing: ${applied.files}")
        assertEquals("val pin = 1\nval other = pin\n", a.readText())
        assertEquals("// pin\n", b.readText())
    }

    @Test
    fun `a regex replace supports capture-group references`(
        @TempDir dir: File,
    ) = runBlocking {
        val f = File(dir, "a.kt")
        f.writeText("fun oldName(x: Int)\n")
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val applied =
            service.replaceInProject(
                query = "fun (\\w+)\\(",
                replacement = "fun new_$1(",
                files = listOf("a.kt"),
                isRegex = true,
                dryRun = false,
            )

        assertEquals(1, applied.totalReplacements, "regex replace failed: ${applied.files}")
        assertEquals("fun new_oldName(x: Int)\n", f.readText())
    }

    @Test
    fun `dry run replace counts without writing`(
        @TempDir dir: File,
    ) = runBlocking {
        val file = File(dir, "a.txt")
        file.writeText("needle needle\n")
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val summary =
            service.replaceInProject(
                query = "needle",
                replacement = "pin",
                files = listOf("a.txt"),
                dryRun = true,
            )

        assertEquals(2, summary.totalReplacements)
        assertEquals("needle needle\n", file.readText())
    }

    // ---- round-2 regressions ----

    @Test
    fun `a lookahead regex replaces every counted match and reports the truth`(
        @TempDir dir: File,
    ) = runBlocking {
        // The cut-and-replace implementation reported 2 and wrote 1 here: the cut
        // landed after the second match, whose lookahead reads past it, so re-matching
        // the truncated prefix found only the first.
        val f = File(dir, "a.txt").apply { writeText("ab ab") }
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val summary =
            service.replaceInProject(
                query = "a(?=b)",
                replacement = "X",
                files = listOf(f.absolutePath),
                isRegex = true,
                dryRun = false,
            )

        assertEquals("Xb Xb", f.readText())
        assertEquals(2, summary.totalReplacements)
    }

    @Test
    fun `capture group references survive the disk replace path`(
        @TempDir dir: File,
    ) = runBlocking {
        val f = File(dir, "b.txt").apply { writeText("foo=1\nbar=2\n") }
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        service.replaceInProject(
            query = "(\\w+)=(\\d)",
            replacement = "$2:$1",
            files = listOf(f.absolutePath),
            isRegex = true,
            dryRun = false,
        )

        assertEquals("1:foo\n2:bar\n", f.readText())
    }

    // ---- round-4 regressions ----

    @Test
    fun `replacing in an executable script keeps its executable bit`(
        @TempDir dir: File,
    ) = runBlocking {
        // writeAtomically lands a fresh temp file on the target's inode, and
        // createTempFile is owner-only 0600 - so a replace in a git hook or a
        // build script silently cleared +x until the perms were carried across.
        val script = File(dir, "hook.sh").apply { writeText("#!/bin/sh\necho needle\n") }
        if (!script.setExecutable(true)) return@runBlocking // non-POSIX filesystem: nothing to pin
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        service.replaceInProject(
            query = "needle",
            replacement = "pin",
            files = listOf("hook.sh"),
            dryRun = false,
        )

        assertEquals("#!/bin/sh\necho pin\n", script.readText())
        assertTrue(script.canExecute(), "the replace dropped the executable bit")
    }

    @Test
    fun `a file with a name shorter than three chars can still be replaced`(
        @TempDir dir: File,
    ) = runBlocking {
        // createTempFile throws "Prefix string too short" for a prefix under 3
        // chars, which surfaced as an opaque per-file failure for a file named "a".
        val f = File(dir, "a").apply { writeText("needle\n") }
        val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

        val summary =
            service.replaceInProject(
                query = "needle",
                replacement = "pin",
                files = listOf("a"),
                dryRun = false,
            )

        assertEquals(1, summary.totalReplacements, "replace failed: ${summary.files}")
        assertEquals("pin\n", f.readText())
    }

    @Test
    fun `an authoritative empty open-tab set skips the buffer bridge entirely`(
        @TempDir dir: File,
    ) = runBlocking {
        tree(dir)
        var bridgeCalls = 0
        val countingBridge =
            object : EditorBufferBridge {
                override suspend fun readBuffer(path: String): ai.rever.boss.plugin.api.BufferSnapshot? {
                    bridgeCalls++
                    return null
                }

                override suspend fun applyEdit(
                    path: String,
                    startLine: Int,
                    startCol: Int,
                    endLine: Int,
                    endCol: Int,
                    newText: String,
                    expectedVersion: Long,
                ): ai.rever.boss.plugin.api.EditResult? = null
            }
        val service =
            ContentSearchService(
                projectPathProvider = { dir.absolutePath },
                bufferBridge = countingBridge,
                openEditorPathsProvider = { emptySet() },
            )

        val hits = service.searchInProject(query = "needle")

        assertTrue(hits.isNotEmpty(), "the search itself must still work")
        assertEquals(0, bridgeCalls, "no editor tab is open, so the bridge must not be asked per file")
    }

    @Test
    fun `an open tab's buffer still overlays the disk when the open set is provided`(
        @TempDir dir: File,
    ) = runBlocking {
        tree(dir)
        val open = File(dir, "top.kt").absolutePath
        val bridge =
            object : EditorBufferBridge {
                override suspend fun readBuffer(path: String): ai.rever.boss.plugin.api.BufferSnapshot? =
                    if (path == open) {
                        ai.rever.boss.plugin.api.BufferSnapshot(
                            path = path,
                            content = "val unsavedNeedle = 2\n",
                            version = 1L,
                            isModified = true,
                        )
                    } else {
                        null
                    }

                override suspend fun applyEdit(
                    path: String,
                    startLine: Int,
                    startCol: Int,
                    endLine: Int,
                    endCol: Int,
                    newText: String,
                    expectedVersion: Long,
                ): ai.rever.boss.plugin.api.EditResult? = null
            }
        val service =
            ContentSearchService(
                projectPathProvider = { dir.absolutePath },
                bufferBridge = bridge,
                openEditorPathsProvider = { setOf(open) },
            )

        val paths = service.searchInProject(query = "unsavedNeedle").map { it.path }

        assertEquals(listOf("top.kt"), paths, "the live buffer's content was not searched")
    }

    @Test
    fun `a cancelling caller unwinds a catastrophic-backtracking regex instead of pinning the thread`(
        @TempDir dir: File,
    ) {
        // The security control this class exists for: [InterruptibleText] re-checks
        // the CALLER'S job on every character read, so `(a+)+$` against a long line
        // - catastrophic backtracking in the non-interruptible Java matcher - must
        // unwind on cancel rather than pinning a Dispatchers.IO thread (and with it
        // every git/search behind the shared pools). Pinned the same way the
        // isSafeRefName guard is pinned: a check that rots silently is worse than
        // none, and only a test can prove it still trips.
        // The trailing 'b' is what makes it catastrophic: the run of 'a's matches,
        // the '$' then fails at the 'b', and the matcher backtracks exponentially.
        // A pure 'a' line would simply match at the end and finish in milliseconds,
        // proving nothing.
        runBlocking {
            File(dir, "long.kt").writeText("a".repeat(40_000) + "b\n")
            val service = ContentSearchService(projectPathProvider = { dir.absolutePath })

            val finished: Boolean? =
                withTimeoutOrNull(20_000L) {
                    val job: Job =
                        launch(Dispatchers.IO) {
                            service.searchInProject(query = "(a+)+\$", isRegex = true)
                        }
                    // Let the matcher start spinning before the cancel lands.
                    kotlinx.coroutines.delay(1_000)
                    job.cancel()
                    job.join()
                    true
                }

            assertTrue(
                finished == true,
                "the cancel did not unwind the wedged matcher within 20s - the check inside the " +
                    "character stream has stopped working",
            )
        }
    }
}
