package ai.rever.boss.updater

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Remembers the version actually launched; downloading or scheduling an update never counts. */
internal class UpdateLaunchTracker(
    private val file: File,
) {
    @Serializable
    private data class Launch(
        val version: String,
    )

    /** Returns an upgraded running version once, even if several windows/processes start together. */
    fun recordLaunch(current: Version): Version? =
        synchronized(processLock) {
            Files.createDirectories(file.parentFile.toPath())
            val lockPath = File(file.parentFile, "${file.name}.lock").toPath()
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val previous =
                        if (file.exists()) {
                            runCatching {
                                Version.parse(Json.decodeFromString<Launch>(file.readText()).version)
                            }.getOrNull()
                        } else {
                            null
                        }
                    val temporary = File.createTempFile("launch-version-", ".json", file.parentFile)
                    try {
                        temporary.writeText(Json.encodeToString(Launch.serializer(), Launch(current.toString())))
                        try {
                            Files.move(
                                temporary.toPath(),
                                file.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        temporary.delete()
                    }
                    current.takeIf { previous != null && it.isNewerThan(previous) }
                }
            }
        }

    companion object {
        private val processLock = Any()

        fun forCurrentInstallation(): UpdateLaunchTracker {
            // A packaged runtime lives inside its installation. This path survives an update,
            // including test bundles whose replacement release has a different settings profile.
            val installation = File(System.getProperty("java.home")).canonicalPath
            val id =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(installation.toByteArray(Charsets.UTF_8))
                    .take(12)
                    .joinToString("") { "%02x".format(it) }
            return UpdateLaunchTracker(BossDirectories.resolve("updater/launch-versions/$id.json"))
        }
    }
}

private val completedUpdate by lazy {
    MutableStateFlow(
        runCatching { UpdateLaunchTracker.forCurrentInstallation().recordLaunch(AppVersion.CURRENT) }
            .onFailure {
                BossLogger.forComponent("UpdateLaunchTracker").warn(
                    LogCategory.SYSTEM,
                    "Could not record update launch version",
                    error = it,
                )
            }.getOrNull(),
    )
}

internal actual suspend fun takeCompletedUpdateNotification(): Version? =
    withContext(Dispatchers.IO) {
        completedUpdate.getAndUpdate { null }
    }
