package ai.rever.boss.files

import com.sun.jna.Native
import com.sun.jna.Platform
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path

data class FileInfo(
    val size: Long,
    val isRegularFile: Boolean,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val identity: String,
    val modifiedMillis: Long,
)

/**
 * Operations relative to owned directory handles. Names are single components and never followed as links.
 * The caller supplies trusted roots and owns authorization, revocation, and traversal limits.
 * An opened directory remains the same object if renamed, including by another same-user process.
 */
interface NativeDirectory : AutoCloseable {
    val identity: String

    fun child(
        name: String,
        create: Boolean = false,
    ): NativeDirectory

    fun file(
        name: String,
        create: Boolean = false,
    ): SeekableByteChannel

    fun info(name: String): FileInfo?

    fun delete(
        name: String,
        directory: Boolean = false,
    )

    fun move(
        source: String,
        destination: NativeDirectory,
        name: String,
        overwrite: Boolean,
    )

    /** The callback permits early termination without allocating an unbounded list. */
    fun entries(visit: (String) -> Boolean)

    fun restrictToOwner()

    companion object {
        fun open(
            path: Path,
            create: Boolean = false,
        ): NativeDirectory {
            check(Native.POINTER_SIZE == 8) { "Native file operations require a supported 64-bit platform" }
            val absolute = physicalSystemPath(path.toAbsolutePath().normalize())
            var current =
                when {
                    Platform.isWindows() -> {
                        WindowsDirectory.openRoot(absolute.root)
                    }

                    Platform.isMac() || (Platform.isLinux() && Platform.ARCH in setOf("x86-64", "aarch64")) -> {
                        PosixDirectory.openRoot(absolute.root)
                    }

                    else -> {
                        throw IOException("Handle-relative file operations are unavailable on this platform")
                    }
                }
            var delivered = false
            try {
                for (component in absolute) {
                    val next = current.child(component.toString(), create)
                    current.close()
                    current = next
                }
                delivered = true
                return current
            } finally {
                if (!delivered) current.close()
            }
        }
    }
}

private fun physicalSystemPath(path: Path): Path {
    // macOS exposes these OS-owned aliases at the filesystem root, including java.io.tmpdir's /var.
    // Resolve their fixed system locations without following caller-created symlinks at any depth.
    val first = path.firstOrNull()?.toString()
    val standardAlias = Platform.isMac() && first in setOf("var", "tmp", "etc")
    return if (standardAlias) Path.of("/private").resolve(path.subpath(0, path.nameCount)) else path
}

internal fun component(name: String): String {
    require(name.isNotEmpty() && name != "." && name != "..") { "Expected a file name" }
    require(name.none { it == '/' || it == '\u0000' }) { "Expected one file name component" }
    require(!Platform.isWindows() || name.none { it == '\\' || it == ':' }) { "Windows stream paths are not allowed" }
    return name
}
