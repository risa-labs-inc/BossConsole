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
    val modifiedNanos: Long = modifiedMillis * 1_000_000,
)

enum class CreationPermissions { OWNER_ONLY, INHERIT }

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
        permissions: CreationPermissions = CreationPermissions.OWNER_ONLY,
    ): NativeDirectory

    fun file(
        name: String,
        create: Boolean = false,
        writable: Boolean = create,
        permissions: CreationPermissions = CreationPermissions.OWNER_ONLY,
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

    /** Copy one entry exclusively; directories must be empty and links are copied without traversal. */
    fun copyEntry(
        source: String,
        destination: NativeDirectory,
        name: String,
    )

    /** The callback permits early termination without allocating an unbounded list. */
    fun entries(visit: (String) -> Boolean)

    fun watch(session: DirectoryWatchSession? = null): NativeDirectoryWatch

    fun restrictToOwner()

    companion object {
        fun open(
            path: Path,
            create: Boolean = false,
            permissions: CreationPermissions = CreationPermissions.OWNER_ONLY,
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
                    val next = current.child(component.toString(), create, permissions)
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

class CrossDeviceMoveException : IOException("Move crosses filesystem volumes")
