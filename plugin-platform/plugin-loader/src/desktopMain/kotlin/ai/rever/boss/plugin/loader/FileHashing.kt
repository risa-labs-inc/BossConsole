package ai.rever.boss.plugin.loader

import java.io.File
import java.security.MessageDigest

/**
 * Shared file hashing for plugin integrity checks. Both the loader
 * (load-time signature anchor) and the store repository (download checksum)
 * hash JARs the same way, so keep it in one place.
 */
object FileHashing {
    /** Lowercase hex SHA-256 of [file], streamed so large JARs stay memory-flat. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
