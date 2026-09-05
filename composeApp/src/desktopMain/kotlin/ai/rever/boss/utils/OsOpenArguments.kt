package ai.rever.boss.utils

import java.io.File
import java.net.URI
import java.net.URISyntaxException

/**
 * Turns the `argv` the OS launched BOSS with into `boss://` deep links.
 *
 * Windows and Linux do not send an open-file event: the shell and the desktop
 * file hand the path to the process as an argument. Before this, `main.kt` looked
 * only for args starting `boss://`, `http://` or `https://`, so a file path in
 * `argv` reached one of two dead ends - a *running* instance logged "No URL to
 * send" and `exitProcess(0)`, and a cold start handed the path to Clikt, which
 * has no bare-path argument (only `boss file <path>`) and failed with a usage
 * error. Double-clicking a file therefore did nothing on either platform, even
 * with the association correctly registered.
 *
 * Pure, with the filesystem injected, so the interesting rule - telling a CLI
 * invocation apart from an OS open request - is testable without a filesystem or
 * a running app.
 */
internal object OsOpenArguments {
    /** URL schemes that are already a link and pass through untouched. */
    private val LINK_PREFIXES = listOf("boss://", "http://", "https://")

    /**
     * The subcommands `createBossCLI` registers.
     *
     * Their presence as the first non-flag argument means the operator is using
     * the CLI, and this object must return nothing so Clikt gets the args
     * intact. Without this test, `boss file /tmp/x.md` would be opened twice:
     * once as an extracted deep link here and once by `BossFileCommand`.
     *
     * Kept in sync with `createBossCLI` by `OsOpenArgumentsTest`, which fails if
     * a subcommand is added there and not here - the failure mode is a
     * double-open, which is easy to miss and hard to attribute.
     */
    internal val CLI_SUBCOMMANDS = setOf("url", "workspace", "file", "folder", "terminal", "status", "mcp", "completion")

    /** What a path on disk is, for deciding which deep link to build. */
    internal enum class OpenTargetKind { FILE, DIRECTORY, ABSENT }

    /**
     * Deep links for everything in [args] the OS is asking BOSS to open, or an
     * empty list when [args] is a CLI invocation, a flag-only launch, or empty.
     *
     * A **directory** becomes a `boss://folder` link rather than being dropped.
     * `boss://folder` and `boss folder` both exist, dropping a project folder on
     * the app is an obvious thing to do, and the previous file-only predicate made
     * it silently do nothing.
     *
     * @param kindOf what a path names: a file, a directory, or nothing. Injected
     *   for tests; the caller passes the real filesystem. A path that does not
     *   exist is deliberately **not** turned into a link, because it is far more
     *   likely a mistyped flag or an argument this function does not know about
     *   than something worth opening.
     */
    fun deepLinksFrom(
        args: Array<String>,
        kindOf: (String) -> OpenTargetKind = ::openTargetKindOf,
    ): List<String> {
        if (args.isEmpty()) return emptyList()

        // A CLI invocation is claimed by Clikt. Matched on the FIRST non-flag
        // argument, which is both what the KDoc above describes and what Clikt
        // actually parses. `args.any { it in CLI_SUBCOMMANDS }` matched at any
        // position, so an OS open request whose path segment happened to be
        // exactly `file`, `url`, `folder`, `terminal` or `workspace` was silently
        // dropped as a CLI call.
        val firstNonFlag = args.firstOrNull { !it.startsWith("-") }
        if (firstNonFlag in CLI_SUBCOMMANDS) return emptyList()

        return args.mapNotNull { arg ->
            when {
                LINK_PREFIXES.any { arg.startsWith(it, ignoreCase = true) } -> {
                    arg
                }

                // Flags are never paths. Checked before the filesystem because a
                // file called `-n` in the working directory would otherwise turn a
                // flag into an open request.
                arg.startsWith("-") -> {
                    null
                }

                // A local path as a URL. The Linux desktop entry uses `Exec=%U`,
                // which is what lets it accept both links and files, and file
                // managers hand `%U` a `file://` URL rather than a bare path - so
                // without this the association would launch BOSS and open
                // nothing, which is the bug this whole object exists to fix, one
                // layer down.
                arg.startsWith(FILE_URL_PREFIX, ignoreCase = true) -> {
                    pathFromFileUrl(arg)?.let { linkForPath(it, kindOf) }
                }

                else -> {
                    linkForPath(arg, kindOf)
                }
            }
        }
    }

    /** The real filesystem's answer. One stat, not two. */
    internal fun openTargetKindOf(path: String): OpenTargetKind {
        val file = File(path)
        return when {
            file.isDirectory -> OpenTargetKind.DIRECTORY
            file.isFile -> OpenTargetKind.FILE
            else -> OpenTargetKind.ABSENT
        }
    }

    /** The deep link for a path, or null when it names nothing on disk. */
    private fun linkForPath(
        path: String,
        kindOf: (String) -> OpenTargetKind,
    ): String? =
        when (kindOf(path)) {
            OpenTargetKind.FILE -> fileDeepLinkFor(File(path).absolutePath)
            OpenTargetKind.DIRECTORY -> folderDeepLinkFor(File(path).absolutePath)
            OpenTargetKind.ABSENT -> null
        }

    private const val FILE_URL_PREFIX = "file://"

    /**
     * The local path inside a `file://` URL, or null when it names another host.
     *
     * `file://host/path` is a remote path this process cannot open, and treating
     * its host as the first path segment would silently open the wrong file. An
     * empty host and `localhost` are both the local machine.
     */
    private fun pathFromFileUrl(url: String): String? =
        try {
            val uri = URI(url)
            val host = uri.host
            when {
                host == null || host.isEmpty() -> {
                    File(uri).absolutePath
                }

                // `File(URI)` rejects any URI with an authority component, so a
                // `file://localhost/tmp/x` from a file manager has to have the
                // redundant host removed before it can be turned into a path.
                // Without this it landed in the IllegalArgumentException below
                // and the file silently did not open.
                host.equals("localhost", ignoreCase = true) -> {
                    File(URI("file", null, uri.path, null, null)).absolutePath
                }

                // `file://somehost/path` is a path on another machine that this
                // process cannot read. Dropping the host and opening the local
                // path of the same name would open the wrong file.
                else -> {
                    null
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: URISyntaxException) {
            null
        }
}
