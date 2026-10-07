package ai.rever.boss.components.plugin

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal class PluginUpdateLeaseBusyException(
    pluginId: String,
) : IllegalStateException("Another installation is already updating $pluginId")

/** Shared disk and bootstrap-JDK process protocol with Toolbox; never delete lock files. */
internal class PluginUpdateLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val owners: ConcurrentHashMap<String, Any>,
    private val path: String,
    private val token: Any,
) : Closeable {
    override fun close() {
        cleanup(channel, lock, owners, path, token)
    }

    companion object {
        private const val PROCESS_OWNERS = "boss.plugins.updateLease.processOwners"
        private val logger = BossLogger.forComponent("PluginUpdateLease")

        @Suppress("TooGenericExceptionCaught")
        fun acquire(
            pluginDir: File,
            pluginId: String,
        ): Result<PluginUpdateLease> {
            var channel: FileChannel? = null
            var lock: FileLock? = null
            var owners: ConcurrentHashMap<String, Any>? = null
            var path: String? = null
            val token = Any()
            return try {
                val directory = File(pluginDir, ".plugin-update-locks")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create plugin update lock directory" }
                val name =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(pluginId.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                val file = File(directory, "$name.lock").canonicalFile
                path = file.path
                owners = processOwners()
                // On POSIX, closing ANY descriptor for this inode can release the process's
                // existing lock. Reject same-JVM contenders before opening another channel.
                claimProcessOwner(owners, path, token, pluginId)
                channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) throw PluginUpdateLeaseBusyException(pluginId)
                Result.success(PluginUpdateLease(channel, lock, owners, path, token))
            } catch (failure: Exception) {
                cleanup(channel, lock, owners, path, token)
                Result.failure(failure)
            } catch (failure: Throwable) {
                try {
                    cleanup(channel, lock, owners, path, token)
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        private fun claimProcessOwner(
            owners: ConcurrentHashMap<String, Any>,
            path: String,
            token: Any,
            pluginId: String,
        ) {
            if (owners.putIfAbsent(path, token) != null) throw PluginUpdateLeaseBusyException(pluginId)
        }

        @Suppress("UNCHECKED_CAST")
        private fun processOwners(): ConcurrentHashMap<String, Any> {
            val properties = System.getProperties()
            return synchronized(properties) {
                val existing = properties[PROCESS_OWNERS]
                if (existing == null) {
                    ConcurrentHashMap<String, Any>().also { properties[PROCESS_OWNERS] = it }
                } else {
                    check(existing is ConcurrentHashMap<*, *>) { "Invalid plugin update process lease registry" }
                    existing as ConcurrentHashMap<String, Any>
                }
            }
        }

        private fun cleanup(
            channel: FileChannel?,
            lock: FileLock?,
            owners: ConcurrentHashMap<String, Any>?,
            path: String?,
            token: Any,
        ) {
            cleanupPluginUpdateLease(
                release = { lock?.release() },
                close = { channel?.close() },
                afterClose = {
                    // A failed close that leaves a live descriptor retains the process fence.
                    if ((channel == null || !channel.isOpen) && path != null) owners?.remove(path, token)
                },
                reportFailure = { phase, error ->
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Plugin update lease cleanup failed",
                        mapOf("phase" to phase, "error" to error),
                    )
                },
            )
        }
    }
}

/** Local callbacks keep cleanup fault tests independent of live installer/global state. */
internal fun cleanupPluginUpdateLease(
    release: () -> Unit,
    close: () -> Unit,
    afterClose: () -> Unit,
    reportFailure: (String, String) -> Unit,
) {
    val failures =
        listOfNotNull(
            cleanupPluginUpdateLeaseAction("release", release, reportFailure),
            cleanupPluginUpdateLeaseAction("close", close, reportFailure),
            cleanupPluginUpdateLeaseAction("unfence", afterClose, reportFailure),
        )
    val primary = failures.firstOrNull() ?: return
    failures.drop(1).forEach { if (it !== primary) primary.addSuppressed(it) }
    throw primary
}

@Suppress("TooGenericExceptionCaught")
private fun cleanupPluginUpdateLeaseAction(
    phase: String,
    action: () -> Unit,
    reportFailure: (String, String) -> Unit,
): Throwable? =
    try {
        action()
        null
    } catch (failure: Exception) {
        try {
            reportFailure(phase, failure.javaClass.simpleName)
            null
        } catch (_: Exception) {
            null // Ordinary diagnostics cannot invalidate a completed installation.
        } catch (fatal: Throwable) {
            fatal
        }
    } catch (fatal: Throwable) {
        fatal
    }
