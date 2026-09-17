package ai.rever.boss.service.filesystem

import io.grpc.Status
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * Best-effort system-path guard for host RPCs, not a sandbox against concurrent filesystem mutation.
 */
internal class FileSystemPathPolicy(
    private val blockedRoots: List<Path> = systemRoots(),
) {
    fun validate(
        raw: String,
        followFinalLink: Boolean = true,
    ) {
        try {
            val path = Path.of(raw)
            if (raw.contains("..")) invalid("Path traversal sequences are not allowed")
            val absolute = path.toAbsolutePath().normalize()
            checkBlocked(absolute)
            // Missing destinations still inherit the real path of their nearest existing parent.
            var ancestor: Path? = if (followFinalLink) absolute else absolute.parent
            while (ancestor != null && !Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = ancestor.parent
            if (ancestor != null) {
                val resolved = ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize()
                checkBlocked(resolved)
            }
        } catch (e: InvalidPathException) {
            throw Status.INVALID_ARGUMENT
                .withDescription("Invalid filesystem path")
                .withCause(e)
                .asRuntimeException()
        }
    }

    private fun checkBlocked(path: Path) {
        if (blockedRoots.any { path.startsWith(it.toAbsolutePath().normalize()) }) {
            invalid("Access to system paths is not allowed")
        }
    }

    private fun invalid(message: String): Nothing {
        val status = Status.INVALID_ARGUMENT.withDescription(message)
        throw status.asRuntimeException()
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
