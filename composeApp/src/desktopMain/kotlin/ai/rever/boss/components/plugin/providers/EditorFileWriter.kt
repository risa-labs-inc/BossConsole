package ai.rever.boss.components.plugin.providers

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes for the editor's file API must not truncate the user's only copy before
 * replacement content is complete. Each save stages its own sibling and requires
 * atomic replacement; unsupported filesystems report failure without an in-place retry.
 * Power loss may lose the rename; parent-directory durability is not guaranteed.
 * Process termination may leave a sibling staging file. No automatic sweep deletes these.
 * The disk-write operation is injectable to test partial-output failures.
 */
internal class EditorFileWriter(
    private val cleanup: (Path) -> Unit = { Files.deleteIfExists(it) },
    private val writeContent: (File, String) -> Unit = { file, text ->
        file.outputStream().use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    },
) {
    // All failures require cleanup; fatal errors are rethrown unchanged, never contained here.
    @Suppress("TooGenericExceptionCaught")
    fun write(
        filePath: String,
        content: String,
    ) {
        val requested = File(filePath).absoluteFile.toPath()
        // Resolve existing links so replacing a symlink updates its target, not
        // the link itself. A dangling link fails safely instead of being removed.
        val existing = Files.exists(requested) || Files.isSymbolicLink(requested)
        val target = if (existing) requested.toRealPath() else requested
        if (existing && (!Files.isRegularFile(target) || !Files.isWritable(target))) {
            throw IOException("Editor target is not a writable regular file: $target")
        }
        Files.createDirectories(target.parent)
        val temporary =
            if (!existing && Files.getFileAttributeView(target.parent, PosixFileAttributeView::class.java) != null) {
                // The filesystem applies the current umask, just as for ordinary file creation.
                Files.createTempFile(
                    target.parent,
                    ".boss-editor-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw-rw-")),
                )
            } else {
                Files.createTempFile(target.parent, ".boss-editor-", ".tmp")
            }
        try {
            if (existing) copyPermissions(target, temporary)
            writeContent(temporary.toFile(), content)
            // Windows may deny two simultaneous replacements of the same directory entry.
            // Only promotion is serialized; staging and syncing remain concurrent.
            synchronized(promotionLock) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            // Cleanup cannot turn a committed save into failure or replace its original cause.
            try {
                cleanup(temporary)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun copyPermissions(
        target: Path,
        temporary: Path,
    ) {
        Files.getFileAttributeView(target, PosixFileAttributeView::class.java)?.let { source ->
            val attributes = source.readAttributes()
            val destination =
                requireView(temporary, PosixFileAttributeView::class.java)
            destination.setOwner(attributes.owner())
            destination.setGroup(attributes.group())
            destination.setPermissions(attributes.permissions())
        }
        Files.getFileAttributeView(target, AclFileAttributeView::class.java)?.let { source ->
            val destination =
                requireView(temporary, AclFileAttributeView::class.java)
            destination.acl = source.acl
        }
        Files.getFileAttributeView(target, DosFileAttributeView::class.java)?.let { source ->
            val attributes = source.readAttributes()
            val destination =
                requireView(temporary, DosFileAttributeView::class.java)
            destination.setHidden(attributes.isHidden)
            destination.setSystem(attributes.isSystem)
            destination.setArchive(attributes.isArchive)
            destination.setReadOnly(attributes.isReadOnly)
        }
    }

    private fun <T : FileAttributeView> requireView(
        path: Path,
        type: Class<T>,
    ): T = Files.getFileAttributeView(path, type) ?: throw IOException("Cannot preserve ${type.simpleName} for $path")

    private companion object {
        val promotionLock = Any()
    }
}
