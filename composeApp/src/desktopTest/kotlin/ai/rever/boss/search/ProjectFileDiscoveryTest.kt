package ai.rever.boss.search

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectFileDiscoveryTest {
    @Test
    fun `file budget and invalid root report incomplete discovery`(
        @TempDir root: File,
    ) = runBlocking {
        repeat(3) { File(root, "file-$it.txt").writeText("needle") }
        val limited = ProjectFileDiscovery.discover(root.absolutePath, maxFiles = 2)
        assertTrue(limited.incompleteReason?.contains("file budget") == true)
        assertTrue(ProjectFileDiscovery.discover(File(root, "missing").path).incompleteReason != null)
    }

    @Test
    fun `directory budget reports incomplete discovery`(
        @TempDir root: File,
    ) = runBlocking {
        File(root, "nested").mkdir()
        val limited = ProjectFileDiscovery.discover(root.absolutePath, maxDirectories = 1)
        assertTrue(limited.incompleteReason?.contains("directory budget") == true)
    }

    @Test
    fun `failed reindex clears stale files and exposes the reason`(
        @TempDir root: File,
    ) = runBlocking {
        File(root, "visible.txt").writeText("needle")
        val indexer = FileIndexer()
        indexer.indexProject(root.absolutePath)
        assertTrue(indexer.indexedFiles.value.isNotEmpty())

        indexer.indexProject(File(root, "missing").absolutePath)
        assertTrue(indexer.indexedFiles.value.isEmpty())
        assertTrue(indexer.indexError.value?.contains("cannot be resolved") == true)

        indexer.indexProject(root.absolutePath)
        assertTrue(indexer.indexError.value == null)
        assertEquals(1, indexer.indexedFiles.value.size)
    }

    @Test
    fun `non UTF8 gitignore fails closed`(
        @TempDir root: File,
    ) = runBlocking {
        File(root, ".gitignore").writeBytes(byteArrayOf(0xff.toByte()))
        File(root, "visible.txt").writeText("needle")
        assertTrue(ProjectFileDiscovery.discover(root.absolutePath).incompleteReason != null)
    }

    @Test
    fun `oversized gitignore fails closed`(
        @TempDir root: File,
    ) = runBlocking {
        File(root, ".gitignore").writeText("\n".repeat(1_048_577))
        assertTrue(ProjectFileDiscovery.discover(root.absolutePath).incompleteReason != null)
    }

    @Test
    fun `invalid bracket range fails closed without escaping discovery`(
        @TempDir root: File,
    ) = runBlocking {
        File(root, ".gitignore").writeText("[z-a].txt\n")
        assertTrue(ProjectFileDiscovery.discover(root.absolutePath).incompleteReason != null)
    }

    @Test
    fun `POSIX gitignore class excludes matching files in both search modes`(
        @TempDir root: File,
    ) {
        File(root, ".gitignore").writeText("[[:alpha:]].txt\n")
        File(root, "a.txt").writeText("needle")
        File(root, "1.txt").writeText("needle")

        assertEquals(setOf("1.txt"), content(root))
        assertEquals(setOf("1.txt"), index(root).filter { it.endsWith(".txt") }.toSet())
    }

    @Test
    fun `an in root directory alias does not duplicate results`(
        @TempDir root: File,
    ) = runBlocking {
        val source = File(root, "source").apply { mkdirs() }
        val target = File(source, "one.txt").apply { writeText("needle") }
        assumeTrue(createDirectoryLink(File(root, "alias").toPath(), source.toPath()))
        val discovery = ProjectFileDiscovery.discover(root.absolutePath)
        assertEquals(null, discovery.incompleteReason)
        assertEquals(1, discovery.files.count { it.file.toPath().toRealPath() == target.toPath().toRealPath() })
    }

    private fun index(root: File): Set<String> {
        val indexer = FileIndexer()
        runBlocking { indexer.indexProject(root.absolutePath) }
        return indexer.indexedFiles.value
            .map { it.relativePath.replace('\\', '/') }
            .toSet()
    }

    private fun content(root: File): Set<String> = runBlocking { contentSuspend(root) }

    private suspend fun contentSuspend(root: File): Set<String> =
        ContentSearchService(projectPathProvider = { root.absolutePath })
            .searchInProject(query = "needle", maxResults = 500)
            .map { it.path }
            .toSet()

    @Test
    fun `nested gitignores with negated and anchored rules agree in both search modes`(
        @TempDir root: File,
    ) {
        File(root, ".gitignore").writeText(
            """
            /root-only.md
            *.tmp
            !keep.tmp
            generated/
            """.trimIndent(),
        )
        File(root, "root-only.md").writeText("needle")
        File(root, "nested/root-only.md").apply { parentFile.mkdirs() }.writeText("needle")
        File(root, "drop.tmp").writeText("needle")
        File(root, "nested/drop.tmp").apply { parentFile.mkdirs() }.writeText("needle")
        File(root, "nested/keep.tmp").writeText("needle")
        File(root, "generated/out.txt").apply { parentFile.mkdirs() }.writeText("needle")
        File(root, "nested/.gitignore").writeText("*.txt\n!kept.txt\n!deep/local.txt\n/local.txt\n")
        File(root, "nested/kept.txt").writeText("needle")
        File(root, "nested/local.txt").writeText("needle")
        File(root, "nested/deep/local.txt").apply { parentFile.mkdirs() }.writeText("needle")

        val expected =
            setOf(
                "nested/root-only.md",
                "nested/keep.tmp",
                "nested/kept.txt",
                "nested/deep/local.txt",
            )
        assertEquals(expected, content(root))
        assertTrue(expected.all { it in index(root) }, "filename index ignored a searchable file: ${index(root)}")
        val indexed = index(root)
        assertFalse(
            indexed.any {
                it in setOf("root-only.md", "drop.tmp", "nested/drop.tmp", "generated/out.txt", "nested/local.txt")
            },
        )
    }

    @Test
    fun `useful dotfiles are included unless a gitignore explicitly excludes them`(
        @TempDir root: File,
    ) {
        File(root, ".editorconfig").writeText("needle")
        File(root, ".env.local").writeText("needle")
        File(root, ".gitignore").writeText(".env.local\n")

        assertEquals(setOf(".editorconfig"), content(root))
        val indexed = index(root)
        assertTrue(".editorconfig" in indexed)
        assertFalse(".env.local" in indexed)
    }

    @Test
    fun `gitignore comments escaped prefixes and directory patterns are applied`(
        @TempDir root: File,
    ) {
        File(root, ".gitignore").writeText("# comment\n\\#private.txt\n\\!private.txt\nlogs/\n")
        File(root, "#private.txt").writeText("needle")
        File(root, "!private.txt").writeText("needle")
        File(root, "logs/run.txt").apply { parentFile.mkdirs() }.writeText("needle")
        File(root, "visible.txt").writeText("needle")

        assertEquals(setOf("visible.txt"), content(root))
        assertTrue("visible.txt" in index(root))
        assertFalse(index(root).any { it in setOf("#private.txt", "!private.txt", "logs/run.txt") })
    }

    @Test
    fun `directory negation reopens traversal without unignoring its descendants`(
        @TempDir root: File,
    ) {
        File(root, ".gitignore").writeText("*\n!src/\n")
        File(root, "src/private.txt").apply { parentFile.mkdirs() }.writeText("needle")
        File(root, "src/.gitignore").writeText("!public.txt\n")
        File(root, "src/public.txt").writeText("needle")

        assertEquals(setOf("src/public.txt"), content(root))
        assertEquals(setOf("src/public.txt"), index(root).filter { it.endsWith(".txt") }.toSet())
    }

    @Test
    fun `gitignore classes escapes and only boundary recursive wildcards are special`(
        @TempDir root: File,
    ) {
        File(root, ".gitignore").writeText(
            "*.[oa]\n[Dd]ebug/\n\\[literal].txt\n[[]open.txt\n[]]close.txt\nfoo**/bar.txt\n" +
                "**/lead.txt\na/**/middle.txt\ntail/**\n",
        )
        listOf(
            "drop.o",
            "drop.a",
            "Debug/x.txt",
            "debug/x.txt",
            "[literal].txt",
            "[open.txt",
            "]close.txt",
            "foo/any/bar.txt",
            "lead.txt",
            "a/middle.txt",
            "a/x/middle.txt",
            "tail/x.txt",
        ).forEach {
            File(root, it).apply { parentFile.mkdirs() }.writeText("needle")
        }
        File(root, "foobar.txt").writeText("needle")
        File(root, "keep.txt").writeText("needle")

        val expected = setOf("foobar.txt", "foo/any/bar.txt", "keep.txt")
        assertEquals(expected, content(root))
        assertEquals(expected, index(root).filter { it.endsWith(".txt") }.toSet())
    }

    @Test
    fun `default generated and dependency directories are excluded from both modes`(
        @TempDir root: File,
    ) {
        File(root, "build/output.txt").apply { parentFile.mkdirs() }.writeText("needle")
        File(root, "node_modules/pkg/index.txt").apply { parentFile.mkdirs() }.writeText("needle")
        File(root, "src/keep.txt").apply { parentFile.mkdirs() }.writeText("needle")

        assertEquals(setOf("src/keep.txt"), content(root))
        assertEquals(setOf("src/keep.txt"), index(root).filter { it.endsWith(".txt") }.toSet())
    }

    @Test
    fun `deep valid paths remain discoverable without a depth cutoff`(
        @TempDir root: File,
    ) {
        var directory = root
        repeat(40) {
            directory = File(directory, "level$it").apply { mkdir() }
        }
        val deep = File(directory, "Deep.txt").apply { writeText("needle") }
        val relative = deep.relativeTo(root).path.replace('\\', '/')

        assertTrue(relative in content(root))
        assertTrue(relative in index(root))
    }

    @Test
    fun `cycles and outside-root directory links do not escape either search mode`(
        @TempDir base: File,
    ) = runBlocking {
        val root = File(base, "project").apply { mkdirs() }
        File(root, "inside.txt").writeText("needle")
        val inside = File(root, "inside").apply { mkdir() }
        File(inside, "nested.txt").writeText("needle")
        val outside = File(base, "outside").apply { mkdir() }
        File(outside, "secret.txt").writeText("needle")
        val cycle = inside.toPath().resolve("cycle")
        val escape = root.toPath().resolve("escape")
        assumeTrue(createDirectoryLink(cycle, root.toPath()), "host cannot create directory links")
        assumeTrue(createDirectoryLink(escape, outside.toPath()), "host cannot create directory links")

        val paths = withTimeout(5_000) { contentSuspend(root) }
        assertTrue("inside.txt" in paths)
        assertTrue("inside/nested.txt" in paths)
        assertFalse(paths.any { it.contains("secret") || it.contains("escape") }, paths.toString())
        assertFalse(index(root).any { it.contains("secret") || it.contains("escape") })
    }

    @Test
    fun `in-root file links are excluded from ordinary search and replace discovery`(
        @TempDir root: File,
    ) {
        val target = File(root, "target.txt").apply { writeText("needle") }
        val link = root.toPath().resolve("linked.txt")
        assumeTrue(createFileLink(link, target.toPath()), "host cannot create file links")

        assertEquals(setOf("target.txt"), content(root))
        assertEquals(setOf("target.txt"), index(root).filter { it.endsWith(".txt") }.toSet())
    }

    private fun createDirectoryLink(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: FileSystemException) {
            false
        }

    private fun createFileLink(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: FileSystemException) {
            false
        }
}
