package ai.rever.boss.service.filesystem

import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Selects an allowed canonical candidate. Only a no-follow handle open may turn it into authority. */
internal class FilePathPolicy(
    blocked: List<Path> = listOf(Path.of("/etc"), Path.of("/sys"), Path.of("/proc")),
) {
    private val blockedRoots =
        blocked
            .flatMap { path ->
                listOfNotNull(path.toAbsolutePath().normalize(), runCatching { path.toRealPath() }.getOrNull())
            }.distinct()

    fun validate(path: String) {
        require(!path.contains("..")) { "Path traversal sequences ('..') are not allowed: $path" }
        authorize(Path.of(path).toAbsolutePath().normalize())
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
}

internal class FilePathDeniedException(
    path: Path,
) : IllegalArgumentException("Access to system path is not allowed: $path")
