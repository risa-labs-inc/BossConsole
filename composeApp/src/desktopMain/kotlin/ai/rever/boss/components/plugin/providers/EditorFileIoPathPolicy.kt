package ai.rever.boss.components.plugin.providers

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * The containment gate for the host's surviving editor file doors: readFileContentSafe and
 * writeFileContentSafe, which serve the editor-tab plugin's `editor_read_file` and
 * `editor_write_file` MCP tools through EditorContentProvider.
 *
 * Until now these were the one programmatic file surface in the app with no gate at all -
 * every absolute path, every `..` chain and every symlink was read and written verbatim, so
 * the tools could read /etc/passwd straight up. Every sibling door already runs a
 * canonicalized gate (the file service's FileSystemPathPolicy, the file panel's
 * home-directory confinement, the open_workspace workspaces-directory confinement), and
 * this gives the editor doors the same invariant: parse (failing closed on an unparseable
 * path), refuse traversal sequences, refuse system roots on the lexically normalized
 * spelling - on every platform, before any filesystem call - then refuse system roots on
 * the symlink-resolved real path of the nearest existing ancestor, failing closed when
 * that ancestor exists but cannot be resolved - so a save target that does not exist yet
 * inherits the containment of the directory it would land in, a `..`-free spelling cannot
 * aim a link at a system file, and ///etc/passwd is refused as /etc/passwd on every
 * platform. Allowed paths come back normalized-absolute, so a door reads or writes the
 * very file the gate checked.
 *
 * This copy lives next to the doors it guards because the house keeps small self-contained
 * copies over cross-module dependency edges (FileSystemServiceImpl.moveReplacing is the
 * precedent); the same-shaped gate lives in boss-app-editor for the editor service's own
 * doors. Best-effort path guard, not a sandbox against concurrent filesystem mutation.
 */
internal data class EditorIoPathCheck(
    /** The absolute, normalized path the door may read or write, or null when refused. */
    val path: Path?,
    /** The user-facing refusal, or null when the path is allowed. */
    val refusal: String?,
)

internal object EditorFileIoPathPolicy {
    /**
     * The system roots as pure lexical spellings - /etc, /sys, /proc and, on Windows, the
     * SystemRoot - never resolved. The lexical gate below refuses a root by spelling
     * alone, before any filesystem call: ///etc/passwd and \etc\passwd both normalize to
     * /etc/passwd here, which is what refuses them on a platform whose drive has no /etc
     * for the resolved gate to resolve.
     */
    private val lexicalRoots: List<String> =
        mutableListOf("/etc", "/sys", "/proc").also { roots ->
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                System.getenv("SystemRoot")?.let { roots.add(it.replace('\\', '/')) }
            }
        }

    private val blockedRoots: List<Path> by lazy {
        val roots = lexicalRoots.map { Path.of(it) }
        // macOS exposes /etc through /private/etc; resolving every root means an alias
        // spelling cannot dodge the check either.
        roots + roots.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
    }

    /**
     * Contains [raw] for the editor file doors. At most one of the outcome's fields is set,
     * and every failure mode - a path the filesystem cannot represent, a traversal
     * sequence, a system root by any spelling, lexical or resolved - is a refusal: the
     * gate fails closed.
     */
    fun confine(raw: String): EditorIoPathCheck {
        val refusal =
            parseRefusal(raw)
                ?: traversalRefusal(raw)
                ?: lexicalRefusal(raw)
                ?: resolvedRefusal(raw)
        return if (refusal == null) {
            EditorIoPathCheck(Path.of(raw).toAbsolutePath().normalize(), null)
        } else {
            EditorIoPathCheck(null, refusal)
        }
    }

    private fun parseRefusal(raw: String): String? =
        try {
            Path.of(raw)
            null
        } catch (_: InvalidPathException) {
            "Invalid filesystem path: $raw"
        }

    private fun traversalRefusal(raw: String): String? =
        if (raw.contains("..")) "Path traversal sequences ('..') are not allowed: $raw" else null

    /**
     * Refuses a system root by spelling alone: the caller path is normalized lexically -
     * separators unified, redundant separators, `.` and `..` resolved, all with no
     * filesystem involvement - and compared against the lexical system roots. This is the
     * platform-independent layer; the resolved gate below cannot see ///etc/passwd on a
     * platform where that path has no existing /etc ancestor to resolve.
     */
    private fun lexicalRefusal(raw: String): String? {
        val spelling = lexicalSpelling(raw)
        return if (lexicallyBlocked(spelling)) blockedMessage(raw) else null
    }

    /**
     * Lexically normalizes [raw] with no filesystem involvement: separators are unified,
     * redundant separators and `.` segments collapse, and `..` segments pop the segment
     * before them (kept when there is nothing to pop). A drive prefix or a leading
     * separator is kept as the root, so `C:\Windows` stays `C:/Windows` and
     * `///etc/passwd` becomes `/etc/passwd` on every platform.
     */
    private fun lexicalSpelling(raw: String): String {
        var rest = raw.replace('\\', '/')
        val root = StringBuilder()
        if (rest.length >= 2 && rest[1] == ':' && rest[0].isLetter()) {
            root.append(rest.take(2))
            rest = rest.substring(2)
        } else if (rest.startsWith("/")) {
            root.append('/')
            rest = rest.trimStart('/')
        }
        val segments = mutableListOf<String>()
        rest.split('/').filter { it.isNotEmpty() && it != "." }.forEach { segment ->
            if (segment == ".." && segments.isNotEmpty() && segments.last() != "..") {
                segments.removeAt(segments.size - 1)
            } else {
                segments.add(segment)
            }
        }
        return root.toString() + segments.joinToString("/")
    }

    private fun lexicallyBlocked(spelling: String) = lexicalRoots.any { spelling == it || spelling.startsWith("$it/") }

    /**
     * Refuses system roots on the lexical absolute path and then on the real path of its
     * nearest existing ancestor, resolved through links - the same two-step the file
     * service's FileSystemPathPolicy runs for its writes. A nearest existing node that
     * cannot be resolved to a real path - a link whose target is gone - is refused with
     * the same containment refusal: it can still be aimed anywhere, so only the
     * not-yet-existing tail of a path may inherit an ancestor's containment.
     */
    private fun resolvedRefusal(raw: String): String? {
        val absolute = Path.of(raw).toAbsolutePath().normalize()
        if (isBlocked(absolute)) return blockedMessage(raw)
        var ancestor: Path? = absolute
        while (ancestor != null && !Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.parent
        val resolved =
            ancestor?.let { existing ->
                try {
                    existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
        // The nearest existing node cannot be resolved to a real path - a link whose
        // target is gone - so the gate fails closed rather than guess where it points:
        // only the not-yet-existing tail of a path may inherit an ancestor's containment.
        return when {
            resolved == null -> if (ancestor == null) null else blockedMessage(raw)
            isBlocked(resolved) -> blockedMessage(raw)
            else -> null
        }
    }

    private fun isBlocked(path: Path): Boolean = blockedRoots.any { path.startsWith(it) }

    private fun blockedMessage(raw: String): String = "Access to system paths is not allowed: $raw"
}
