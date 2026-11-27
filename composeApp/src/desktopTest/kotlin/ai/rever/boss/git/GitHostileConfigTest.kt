package ai.rever.boss.git

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a folder must not run a program its `.git/config` names.
 *
 * `core.fsmonitor` set to anything but `true` or `false` is a command git executes on `git status` and on most
 * other commands that touch the index. BOSS runs git in the open folder without being asked (status poll, top bar
 * branch, diff tabs), so a `.git` directory that arrived in an archive or a copied working tree ran its author's
 * command as soon as the folder was opened. `git clone` does not copy config, which is why this is not the usual
 * route in; anything that hands over a `.git` directory is.
 *
 * The integration test builds a real repository whose config plants a marker file, checks that plain git really
 * does run it on this machine (so the guard is being tested against a live hazard, not an assumed one), and then
 * asks BOSS for the status of that folder.
 */
class GitHostileConfigTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the fsmonitor override precedes the subcommand and the arguments are untouched`() {
        val command = GitCommandGuards.command(arrayOf("status", "--porcelain=v1"), temp.toFile())

        assertEquals(listOf("git", "-c", "core.fsmonitor=false", "status", "--porcelain=v1"), command)
    }

    @Test
    fun `a local filter driver is blanked, and a repository with none adds nothing extra`() {
        val repo = File(temp.toFile(), "filter-repo").apply { mkdirs() }
        initRepo(repo)
        val withoutFilter = listOf("git", "-c", "core.fsmonitor=false", "status")
        assertEquals(withoutFilter, GitCommandGuards.command(arrayOf("status"), repo))

        git(repo, "config", "filter.evil.clean", "cat")
        git(repo, "config", "filter.evil.process", "cat")
        val command = GitCommandGuards.command(arrayOf("status"), repo)

        assertEquals(
            listOf(
                "git",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "filter.evil.clean=",
                "-c",
                "filter.evil.process=",
                "status",
            ),
            command,
        )
    }

    @Test
    fun `a repository's fsmonitor command does not run when BOSS reads its status`() {
        assumeTrue(git(temp.toFile(), "--version").first == 0, "git is not available")
        val repo = File(temp.toFile(), "repo").apply { mkdirs() }
        val marker = File(temp.toFile(), "fsmonitor-ran.txt")
        initRepo(repo)
        File(repo, "a.txt").writeText("changed\n")
        git(repo, "config", "core.fsmonitor", "sh -c 'echo RAN > \"${marker.path.replace('\\', '/')}\"; exit 1'")

        // Control: without the guard git runs the planted command on this machine.
        git(repo, "status", "--porcelain")
        assumeTrue(marker.exists(), "this git does not run fsmonitor commands, so there is nothing to guard")
        marker.delete()

        val statuses = runBlocking { GitService.getStatus(repo.absolutePath) }

        assertFalse(marker.exists(), "the repository's core.fsmonitor command ran")
        assertEquals(listOf("a.txt"), statuses.map { it.path }, "the status itself must still work")
    }

    @Test
    fun `every git BOSS starts in DesktopGitService goes through the guards`() {
        val source = source("composeApp/src/desktopMain/kotlin/ai/rever/boss/git/DesktopGitService.kt")

        assertFalse(
            source.contains("ProcessBuilder(\"git\", *args)"),
            "a git command is built without GitCommandGuards",
        )
        val callSite = Regex("""ProcessBuilder\(GitCommandGuards\.command\(args, File\(workingDir\)\)\)""")
        assertEquals(2, callSite.findAll(source).count(), "both the local and the remote runner must use the guards")
    }

    /**
     * The reproduction from review: a `filter.<name>.clean` in `.git/config`, attached through
     * `.git/info/attributes` rather than the tracked tree, runs on `git status` whenever a file's stat
     * data no longer matches the index - which an mtime bump stands in for here, the same as the
     * archive/copy scenario this whole guard exists for. `core.fsmonitor=false` alone does not stop it;
     * this is the second door the fsmonitor-only fix left open.
     */
    @Test
    fun `a repository's clean filter does not run when BOSS reads its status`() {
        assumeTrue(git(temp.toFile(), "--version").first == 0, "git is not available")
        val repo = File(temp.toFile(), "filter-clean-repo").apply { mkdirs() }
        val marker = File(temp.toFile(), "filter-clean-ran.txt")
        initRepo(repo)
        git(repo, "config", "filter.evil.clean", "sh -c 'echo RAN >> \"${marker.path.replace('\\', '/')}\"; cat'")
        repo.resolve(".git/info/attributes").apply { parentFile.mkdirs() }.writeText("* filter=evil\n")
        File(repo, "a.txt").setLastModified(System.currentTimeMillis() + 60_000)

        // Control: without the guard git runs the planted filter on this machine.
        git(repo, "status", "--porcelain")
        assumeTrue(marker.exists(), "this git does not run clean filters on status, so there is nothing to guard")
        marker.delete()
        File(repo, "a.txt").setLastModified(System.currentTimeMillis() + 120_000)

        val statuses = runBlocking { GitService.getStatus(repo.absolutePath) }

        assertFalse(marker.exists(), "the repository's filter.evil.clean command ran")
        assertEquals(emptyList(), statuses, "an unpack-only mtime bump must still read as no changes")
    }

    @Test
    fun `a repository's process filter does not run when BOSS reads its status`() {
        assumeTrue(git(temp.toFile(), "--version").first == 0, "git is not available")
        val repo = File(temp.toFile(), "filter-process-repo").apply { mkdirs() }
        val marker = File(temp.toFile(), "filter-process-ran.txt")
        initRepo(repo)
        git(repo, "config", "filter.evil.process", "sh -c 'echo RAN >> \"${marker.path.replace('\\', '/')}\"; cat'")
        repo.resolve(".git/info/attributes").apply { parentFile.mkdirs() }.writeText("* filter=evil\n")
        File(repo, "a.txt").setLastModified(System.currentTimeMillis() + 60_000)

        git(repo, "status", "--porcelain")
        assumeTrue(marker.exists(), "this git does not run process filters on status, so there is nothing to guard")
        marker.delete()
        File(repo, "a.txt").setLastModified(System.currentTimeMillis() + 120_000)

        runBlocking { GitService.getStatus(repo.absolutePath) }

        assertFalse(marker.exists(), "the repository's filter.evil.process command ran")
    }

    /**
     * `diff.<driver>.textconv` from the repository's own config runs on every diff tab opened on a
     * modified file, under the same `--no-ext-diff`/`--no-color` flags that already pin the diff shape;
     * only `--no-textconv` was missing from the list.
     */
    @Test
    fun `the diff tabs refuse the repository's own textconv driver`() {
        val source = source("composeApp/src/desktopMain/kotlin/ai/rever/boss/git/DesktopGitService.kt")
        val declaration = source.substringAfter("val DIFF_SHAPE_FLAGS").substringBefore("\n\n")

        assertTrue(declaration.contains("\"--no-textconv\""), declaration)
    }

    private fun initRepo(repo: File) {
        git(repo, "init", "-q", ".")
        File(repo, "a.txt").writeText("original\n")
        git(repo, "add", "a.txt")
        git(repo, "-c", "user.email=t@example.test", "-c", "user.name=t", "commit", "-q", "-m", "init")
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git did not finish: ${args.toList()}")
        return process.exitValue() to output
    }

    private fun source(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("source file not found: $relativePath")
    }
}
