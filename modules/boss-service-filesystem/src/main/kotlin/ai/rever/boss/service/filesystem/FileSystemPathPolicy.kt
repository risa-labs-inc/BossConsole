package ai.rever.boss.service.filesystem

import io.grpc.Status
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Best-effort system-path guard for host RPCs, not a sandbox against concurrent filesystem mutation.
 *
 * Selects an allowed canonical candidate. Only a no-follow handle open may turn it into authority.
 *
 * Two tiers: malformed input (parent traversal components, invalid paths) is rejected with
 * INVALID_ARGUMENT, while a resolved candidate inside a blocked root is denied with
 * PERMISSION_DENIED. Only a component exactly equal to `..` counts as traversal; ordinary names
 * such as `report..txt` are accepted.
 */
internal class FileSystemPathPolicy(
    blocked: List<Path> = systemRoots(),
) {
    private val blockedRoots =
        blocked
            .flatMap { path ->
                listOfNotNull(path.toAbsolutePath().normalize(), runCatching { path.toRealPath() }.getOrNull())
            }.distinct()

    fun validate(raw: String) {
        try {
            val parsed = Path.of(raw)
            if (parsed.any { it.toString() == ".." }) {
                throw Status.INVALID_ARGUMENT
                    .withDescription("Parent traversal components are not allowed: $raw")
                    .asRuntimeException()
            }
            authorize(parsed.toAbsolutePath().normalize())
        } catch (e: InvalidPathException) {
            throw Status.INVALID_ARGUMENT
                .withDescription("Invalid filesystem path")
                .withCause(e)
                .asRuntimeException()
        }
    }

    fun allowed(path: Path): Boolean = blockedRoots.none(path::startsWith)

    fun authorize(path: Path) {
        if (!allowed(path)) throw FilePathDeniedException(path)
    }

    fun authorizeMutation(path: Path) {
        authorize(path)
        // Moving an ancestor would relocate a protected subtree beyond the configured denylist.
        if (blockedRoots.any { it.startsWith(path) }) throw FilePathDeniedException(path)
    }

    fun resolve(path: Path): Path = resolve(path, 0)

    private fun resolve(
        path: Path,
        links: Int,
    ): Path {
        if (links >= 40) throw FileSystemLoopException(path.toString())
        var ancestor = path
        while (true) {
            try {
                // Following exists() would mistake dangling links for missing components.
                Files.readAttributes(ancestor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                break
            } catch (failure: NoSuchFileException) {
                ancestor = ancestor.parent ?: throw failure
            }
        }
        val base =
            if (Files.isSymbolicLink(ancestor)) {
                val target = Files.readSymbolicLink(ancestor)
                val absolute = if (target.isAbsolute) target else ancestor.parent.resolve(target)
                resolve(absolute, links + 1)
            } else {
                ancestor.toRealPath()
            }
        val resolved = base.resolve(ancestor.relativize(path)).normalize()
        authorize(resolved)
        return resolved
    }

    companion object {
        private fun systemRoots(): List<Path> {
            val roots = mutableListOf(Path.of("/etc"), Path.of("/sys"), Path.of("/proc"))
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                System.getenv("SystemRoot")?.let { roots.add(Path.of(it)) }
            }
            // macOS exposes /etc through /private/etc.
            return roots + roots.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
        }
    }
}

internal class FilePathDeniedException(
    path: Path,
) : io.grpc.StatusRuntimeException(
        io.grpc.Status.PERMISSION_DENIED
            .withDescription("Access to system path is not allowed: $path"),
    )
