package ai.rever.boss.components.plugin

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal class PluginUpdateLeaseBusyException(
    pluginId: String,
) : IllegalStateException("Another installation is already updating $pluginId")

/** Shared disk protocol with Toolbox/host; keep lock files so contenders lock the same inode. */
internal class PluginUpdateLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun acquire(
            pluginDir: File,
            pluginId: String,
        ): Result<PluginUpdateLease> =
            runCatching {
                val directory = File(pluginDir, ".plugin-update-locks")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create plugin update lock directory" }
                val name =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(pluginId.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                val channel =
                    FileChannel.open(
                        File(directory, "$name.lock").toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                val lock =
                    runCatching {
                        try {
                            channel.tryLock()
                        } catch (_: OverlappingFileLockException) {
                            null
                        }
                    }.getOrElse {
                        channel.close()
                        throw it
                    }
                if (lock == null) {
                    channel.close()
                    throw PluginUpdateLeaseBusyException(pluginId)
                }
                PluginUpdateLease(channel, lock)
            }
    }
}
