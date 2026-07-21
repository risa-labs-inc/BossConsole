package ai.rever.boss.utils

import java.io.File
import java.security.MessageDigest

/**
 * Streaming SHA-256 of a file, as lowercase hex. Shared by the app updater and
 * the browser-engine downloader for release-asset integrity checks.
 */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
