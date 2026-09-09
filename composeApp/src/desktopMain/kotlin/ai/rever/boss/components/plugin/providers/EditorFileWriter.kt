package ai.rever.boss.components.plugin.providers

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclFileAttributeView

/**
 * Writes for the editor's file API must not truncate the user's only copy before
 * replacement content is complete. Each save stages its own sibling and requires
 * atomic replacement; unsupported filesystems report failure without an in-place retry.
 * The disk-write operation is injectable to test partial-output failures.
 */
internal class EditorFileWriter(
    private val writeContent: (File, String) -> Unit = { file, text ->
        file.outputStream().use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    },
) {
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
        val temporary = Files.createTempFile(target.parent, ".boss-editor-", ".tmp")
        try {
            if (existing) {
                // Copy attributes with the existing file before replacing its
                // contents. This preserves executable modes and supported metadata.
                Files.copy(target, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                // COPY_ATTRIBUTES does not promise ACL preservation on every provider.
                Files.getFileAttributeView(target, AclFileAttributeView::class.java)?.let { sourceAcl ->
                    Files.getFileAttributeView(temporary, AclFileAttributeView::class.java).acl = sourceAcl.acl
                }
            }
            writeContent(temporary.toFile(), content)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
