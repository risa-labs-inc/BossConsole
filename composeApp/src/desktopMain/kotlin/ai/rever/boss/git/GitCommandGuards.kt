package ai.rever.boss.git

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What every git command BOSS runs on the user's behalf carries before its own arguments.
 *
 * BOSS runs git in whatever folder is open, on a timer, without being asked: the status poll, the top
 * bar's branch, the diff tabs. A folder that arrived as an archive, a copied working tree or a backup
 * brings its own `.git/config`, and git reads a program to run out of that file. `core.fsmonitor` is
 * the clearest case: any value that is not `true` or `false` is a command that git executes on
 * `git status` and most other commands that touch the index, so merely opening the folder in BOSS ran
 * whatever the repository's author wrote there.
 *
 * `filter.<name>.clean`/`.process` is the same hazard through a different door. git runs a clean filter
 * whenever it has to re-hash a tracked file to decide whether it changed, and `git status` does exactly
 * that for every file whose stat data no longer matches the index - the normal state of a folder that
 * just came out of an archive or a copy, since unpacking gives every file a new inode and ctime. The
 * driver is attached through `.git/info/attributes`, which is not part of the tracked tree, so nothing
 * about the working tree shows it is there.
 *
 * `git clone` never copies a repository's config, which is why this does not show up through the usual
 * route. It does through anything that hands over a `.git` directory.
 *
 * The overrides are passed as `-c`, which outranks every config file, so they hold whatever the
 * repository, the user or the system says. The costs: the built-in filesystem monitor daemon
 * (`core.fsmonitor=true`, an opt-in speed-up for very large working trees) is off for BOSS's own
 * commands, and a repository that set up its own filter driver with `git lfs install --local` sees
 * those files read as unconverted in BOSS's status, though not in the user's own terminal. Neither
 * blanks a GLOBAL-scope driver: `git lfs install` (no `--local`) writes to the user's own gitconfig,
 * which a hostile `.git/config` cannot reach, so LFS installed the ordinary way keeps working.
 *
 * Not covered: `core.sshCommand`, `diff.external` and hooks. Each of those is either something a user
 * legitimately configures per repository or runs only for an action the user chose, so they are left
 * to git's own rules.
 */
internal object GitCommandGuards {
    private const val DISCOVERY_TIMEOUT_SECONDS = 5L
    private val filterCleanOrProcessKey = Regex("""^filter\.(.+)\.(?:clean|process)$""")

    /** The full command line for `git <args>` run in [workingDir]. */
    fun command(
        args: Array<out String>,
        workingDir: File,
    ): List<String> = listOf("git", "-c", "core.fsmonitor=false") + filterOverrides(workingDir) + args

    /**
     * `-c filter.<name>.clean= -c filter.<name>.process=` for every LOCAL-scope filter driver named in
     * [workingDir]'s own `.git/config`. Scoped to `local` (`git config --local`, which reads only that
     * file) so a driver installed globally, such as an ordinary `git lfs install`, is never touched -
     * only a driver a hostile `.git/config` brought with it is blanked.
     *
     * Best-effort: a discovery failure (git missing, the read timing out) yields no overrides rather than
     * failing the caller's real command, since this is one read-only `git config` call ahead of every git
     * command BOSS runs and must not become a new way for status polling to hang.
     */
    private fun filterOverrides(workingDir: File): List<String> =
        localFilterDriverNames(workingDir).sorted().flatMap { name ->
            listOf("-c", "filter.$name.clean=", "-c", "filter.$name.process=")
        }

    private fun localFilterDriverNames(workingDir: File): Set<String> =
        try {
            val process =
                ProcessBuilder("git", "config", "--local", "--get-regexp", """^filter\..*\.(clean|process)$""")
                    .directory(workingDir)
                    .redirectErrorStream(false)
                    .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptySet()
            }
            // Exit 1 with empty output means "no local filter.*.clean/process key", not a fault; the
            // regex below is what actually decides what came back, so a nonzero exit here is not treated
            // as failure on its own.
            output
                .lineSequence()
                .mapNotNull { line -> filterCleanOrProcessKey.find(line.substringBefore(' ')) }
                .map { it.groupValues[1] }
                .toSet()
        } catch (_: IOException) {
            emptySet()
        }
}
