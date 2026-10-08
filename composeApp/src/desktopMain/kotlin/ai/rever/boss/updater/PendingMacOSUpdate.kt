package ai.rever.boss.updater

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException

/** The exact artifact and live helper owned by a deferred macOS installation. */
internal class PendingMacOSUpdate(
    private val downloadPath: String,
    private val relaunchRequest: File,
    private val helper: Process,
    private val writeRequest: (File) -> Unit = { it.writeText("--no-window\n") },
) {
    private val logger = BossLogger.forComponent("PendingMacOSUpdate")

    fun armWindowlessRelaunch(requestedPath: String): Boolean =
        when {
            downloadPath != requestedPath -> {
                logger.warn(LogCategory.SYSTEM, "Idle update refused - the staged artifact changed")
                false
            }

            !helper.isAlive -> {
                logger.warn(LogCategory.SYSTEM, "Idle update refused - the installer helper exited")
                false
            }

            else -> {
                writeRelaunchRequest()
            }
        }

    private fun writeRelaunchRequest(): Boolean =
        try {
            writeRequest(relaunchRequest)
            // The helper may have exited and removed its marker during the write.
            // Do not quit or leave a newly recreated marker for a dead installer.
            if (helper.isAlive) {
                true
            } else {
                relaunchRequest.delete()
                logger.warn(LogCategory.SYSTEM, "Idle update refused - the helper exited while arming relaunch")
                false
            }
        } catch (e: IOException) {
            // The helper checks existence, so even a partial write must not authorize
            // a windowless relaunch when the user later explicitly quits.
            relaunchRequest.delete()
            logger.warn(LogCategory.SYSTEM, "Could not prepare windowless update relaunch", error = e)
            false
        }
}
