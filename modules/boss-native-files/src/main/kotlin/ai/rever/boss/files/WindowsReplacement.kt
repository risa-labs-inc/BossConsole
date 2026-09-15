package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.io.IOException
import java.util.UUID

/** Windows cannot directly replace a directory link with the classic rename operation. */
internal object WindowsReplacement {
    fun move(
        source: Pointer,
        target: Pointer,
        parent: Pointer,
        name: String,
        moveSource: () -> Unit = { WindowsRename.move(source, parent, name, false) },
    ) {
        val backup = ".boss-replace-${UUID.randomUUID()}"
        // Move the held link, never delete it before the source move succeeds. Exclusive
        // renames also refuse a concurrent replacement instead of overwriting somebody else.
        WindowsRename.move(target, parent, backup, false)
        try {
            moveSource()
        } catch (failure: IOException) {
            try {
                WindowsRename.move(target, parent, name, false)
            } catch (restore: IOException) {
                // Keep both the concurrent replacement and the original link. The latter stays
                // recoverable under its staging name rather than being silently destroyed.
                throw IOException("Move failed; original destination retained at $backup", failure).also {
                    it.addSuppressed(restore)
                }
            }
            throw failure
        }
        // Delete the original held link only after commit, without resolving the backup path.
        Memory(4).use { disposition ->
            disposition.setInt(0, 1)
            WindowsApi.check(
                WindowsApi.kernel
                    .getFunction("SetFileInformationByHandle")
                    .invokeInt(arrayOf<Any>(target, 4, disposition, 4)),
                "Remove committed replacement backup",
            )
        }
    }
}
