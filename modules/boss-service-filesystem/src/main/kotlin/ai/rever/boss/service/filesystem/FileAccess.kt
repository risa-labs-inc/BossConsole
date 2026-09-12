package ai.rever.boss.service.filesystem

import ai.rever.boss.files.CreationPermissions
import ai.rever.boss.files.NativeDirectory
import com.sun.jna.Platform
import java.nio.file.Path

/** Owns the validation-to-use boundary. Visible names are labels and are never used for subsequent I/O. */
internal class FileAccess(
    val policy: FilePathPolicy = FilePathPolicy(),
    private val afterAnchor: (Path) -> Unit = {},
) {
    fun entry(
        path: String,
        createParents: Boolean = false,
        followLeaf: Boolean = false,
    ): FileEntryHandle {
        policy.validate(path)
        val visible = Path.of(path).toAbsolutePath().normalize()
        val candidate =
            if (followLeaf) {
                policy.resolve(visible)
            } else {
                val parent = requireNotNull(visible.parent) { "An entry must have a parent directory" }
                policy.resolve(parent).resolve(visible.fileName)
            }
        policy.authorize(candidate)
        val parent = NativeDirectory.open(requireNotNull(candidate.parent), createParents, CreationPermissions.INHERIT)
        var delivered = false
        try {
            // Darwin's path comparisons are case-sensitive even on a case-insensitive volume.
            // Ask for the stored directory-entry name without following its final link.
            val name = if (Platform.isMac()) parent.info(candidate.fileName.toString())?.canonicalName else null
            val authorized = if (name == null) candidate else candidate.resolveSibling(name)
            policy.authorize(authorized)
            afterAnchor(authorized)
            return FileEntryHandle(parent, authorized, visible).also { delivered = true }
        } finally {
            if (!delivered) parent.close()
        }
    }

    fun directory(
        path: String,
        followLeaf: Boolean = true,
    ): FileDirectoryHandle {
        policy.validate(path)
        val visible = Path.of(path).toAbsolutePath().normalize()
        if (visible.parent == null) {
            val candidate = policy.resolve(visible)
            val directory = NativeDirectory.open(candidate)
            var delivered = false
            try {
                afterAnchor(candidate)
                return FileDirectoryHandle(directory, candidate, visible).also { delivered = true }
            } finally {
                if (!delivered) directory.close()
            }
        }
        val entry = entry(path, followLeaf = followLeaf)
        var delivered = false
        try {
            val directory = entry.parent.child(entry.name)
            return FileDirectoryHandle(directory, entry.canonical, entry.visible, entry).also { delivered = true }
        } finally {
            if (!delivered) entry.close()
        }
    }
}

internal class FileEntryHandle(
    val parent: NativeDirectory,
    val canonical: Path,
    val visible: Path,
) : AutoCloseable {
    val name: String get() = canonical.fileName.toString()

    override fun close() = parent.close()
}

internal class FileDirectoryHandle(
    val directory: NativeDirectory,
    val canonical: Path,
    val visible: Path,
    val entry: FileEntryHandle? = null,
) : AutoCloseable {
    override fun close() {
        try {
            directory.close()
        } finally {
            entry?.close()
        }
    }
}
