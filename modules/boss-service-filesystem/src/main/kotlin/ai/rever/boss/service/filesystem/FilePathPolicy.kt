package ai.rever.boss.service.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The path policy of the filesystem service.
 *
 * Two judgements, both of which a request must pass:
 *
 * - [validate] reads the request string: no `..`, no literal system prefix. Cheap, and it is the
 *   error a caller sees for an honest typo.
 * - [authorize] re-judges the same path as the OS will actually traverse it. The string check is
 *   what a symlink defeats: a symlinked parent named `/tmp/link/passwd` passes it and the open
 *   lands on `/etc/passwd`, because `openRegularFile`'s `O_NOFOLLOW` guards only the final
 *   component - the intermediate directories are followed by the kernel, as they are for
 *   scan, delete, rename and watch. The deepest existing ancestor of the request (a request may
 *   name a file that does not exist yet) is resolved, and the denylist is applied to that
 *   resolved path, which `toRealPath` has normalized so every `..` is already gone.
 *
 * [authorize] returns the resolved path for callers that want it; handlers keep operating on the
 * requested name so caller-facing paths stay exactly as they were written - a scan through an
 * alias still reports the alias, which is pinned by `FileSystemLimitsTest`.
 */
internal object FilePathPolicy {
    /** Paths that must not be accessed via IPC — prevents privilege-escalation via path injection. */
    private val blockedPathPrefixes = listOf("/etc", "/sys", "/proc")

    /**
     * The same prefixes as the OS resolves them, so the denylist survives a check made against a
     * resolved path. On macOS `/etc` is a symlink to `/private/etc`, so judging only the literal
     * would refuse `/etc/passwd` while allowing the very same directory under its canonical name;
     * on Linux and Windows the resolved root is the literal one, and an unresolvable literal stays
     * literal rather than failing startup.
     */
    private val blockedRoots: List<Path> =
        blockedPathPrefixes
            .map(Paths::get)
            .flatMap { literal ->
                sequenceOf(literal, runCatching { literal.toRealPath() }.getOrNull())
            }.filterNotNull()
            .distinct()

    /** Throws [IllegalArgumentException] on violation. */
    fun validate(path: String) {
        require(!path.contains("..")) { "Path traversal sequences ('..') are not allowed: $path" }
        blockedPathPrefixes.forEach { prefix ->
            require(!path.startsWith(prefix)) { "Access to system path '$prefix' is not allowed: $path" }
        }
    }

    fun authorize(path: String): Path {
        validate(path)
        val requested = Paths.get(path).toAbsolutePath()
        var ancestor = requested
        while (!Files.exists(ancestor)) {
            val parent = ancestor.parent ?: break
            ancestor = parent
        }
        val resolved =
            if (ancestor == requested) {
                ancestor.toRealPath()
            } else {
                ancestor.toRealPath().resolve(requested.subpath(ancestor.nameCount, requested.nameCount))
            }
        blockedRoots.forEach { blocked ->
            require(!resolved.startsWith(blocked)) {
                "Access to system path '$blocked' is not allowed: $path"
            }
        }
        return resolved
    }
}
