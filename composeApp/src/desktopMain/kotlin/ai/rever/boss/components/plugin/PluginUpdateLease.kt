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
import java.util.Properties
import java.util.UUID

internal class PluginUpdateLeaseBusyException(
    pluginId: String,
) : IllegalStateException("Another installation is already updating $pluginId")

/** Shared disk and bootstrap-JDK process protocol with Toolbox; never delete lock files. */
internal class PluginUpdateLease private constructor(
    private val channelClose: PluginUpdateLeaseChannelClose,
    private val lock: FileLock,
    private val owners: Properties,
    private val ownerKey: String,
    private val token: String,
    private val reportFailure: (String, String) -> Unit,
) : Closeable {
    @Synchronized
    override fun close() {
        reportFailure.cleanup(channelClose, lock, owners, ownerKey, token)
    }

    companion object {
        private val logger = BossLogger.forComponent("PluginUpdateLease")

        @Suppress("TooGenericExceptionCaught")
        fun acquire(
            pluginDir: File,
            pluginId: String,
            openChannel: (File) -> FileChannel = {
                FileChannel.open(it.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            },
            reportFailure: (String, String) -> Unit = ::reportCleanupFailure,
        ): Result<PluginUpdateLease> {
            val channelClose = PluginUpdateLeaseChannelClose()
            var lock: FileLock? = null
            var owners: Properties? = null
            var ownerKey: String? = null
            val token = UUID.randomUUID().toString()
            return try {
                val directory = File(pluginDir, ".plugin-update-locks")
                check(directory.mkdirs() || directory.isDirectory) { "Cannot create plugin update lock directory" }
                val name =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(pluginId.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                val file = File(directory, "$name.lock").canonicalFile
                ownerKey = PluginUpdateProcessRegistry.ownerKey(file.path)
                owners = PluginUpdateProcessRegistry.owners()
                // On POSIX, closing ANY descriptor for this inode can release the process's
                // existing lock. Reject same-JVM contenders before opening another channel.
                claimProcessOwner(owners, ownerKey, token, pluginId)
                val channel = openChannel(file)
                channelClose.attach(channel)
                lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) throw PluginUpdateLeaseBusyException(pluginId)
                Result.success(PluginUpdateLease(channelClose, lock, owners, ownerKey, token, reportFailure))
            } catch (failure: Exception) {
                cleanupFailedAcquisition(failure) { reportFailure.cleanup(channelClose, lock, owners, ownerKey, token) }
                Result.failure(failure)
            } catch (failure: Throwable) {
                cleanupFailedAcquisition(failure) { reportFailure.cleanup(channelClose, lock, owners, ownerKey, token) }
                throw failure
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun cleanupFailedAcquisition(
            failure: Throwable,
            cleanup: () -> Unit,
        ) {
            try {
                cleanup()
            } catch (cleanupFailure: Throwable) {
                val primary = if (failure is Exception) cleanupFailure else failure
                val secondary = if (failure is Exception) failure else cleanupFailure
                if (primary !== secondary) primary.addSuppressed(secondary)
                throw primary
            }
        }

        private fun claimProcessOwner(
            owners: Properties,
            ownerKey: String,
            token: String,
            pluginId: String,
        ) {
            if (owners.putIfAbsent(ownerKey, token) != null) throw PluginUpdateLeaseBusyException(pluginId)
        }

        private fun reportCleanupFailure(
            phase: String,
            error: String,
        ) {
            logger.warn(
                LogCategory.SYSTEM,
                "Plugin update lease cleanup failed",
                mapOf("phase" to phase, "error" to error),
            )
        }

        private fun ((String, String) -> Unit).cleanup(
            channelClose: PluginUpdateLeaseChannelClose,
            lock: FileLock?,
            owners: Properties?,
            ownerKey: String?,
            token: String,
        ) {
            cleanupPluginUpdateLease(
                release = { if (lock?.isValid == true) lock.release() },
                close = channelClose::close,
                afterClose = {
                    // isOpen becomes false before native close; only a successful close confirms teardown.
                    if (channelClose.confirmed && ownerKey != null) owners?.remove(ownerKey, token)
                },
                reportFailure = this,
            )
        }
    }
}

/** A failed native close is sticky even though AbstractInterruptibleChannel reports closed. */
private class PluginUpdateLeaseChannelClose {
    private var channel: FileChannel? = null
    var confirmed: Boolean = true
        private set
    private var attempted = false

    fun attach(channel: FileChannel) {
        confirmed = false
        this.channel = channel
    }

    fun close() {
        if (attempted) return
        attempted = true
        channel?.close()
        confirmed = channel?.isOpen != true
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
            if (fatal !== failure) fatal.addSuppressed(failure)
            fatal
        }
    } catch (fatal: Throwable) {
        fatal
    }
