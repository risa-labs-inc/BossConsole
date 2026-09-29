package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.pathutils.DownloadsDirectory
import ai.rever.boss.plugin.pathutils.WindowsDownloadsFolder
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.util.concurrent.atomic.AtomicBoolean

private val logger = BossLogger.forComponent("DownloadSettings")

/** Once per JVM, like the registry read it reports on. */
private val reportedUnreadKnownFolder = AtomicBoolean(false)

/**
 * Desktop implementation for getting the default downloads directory.
 *
 * The platform rules and their tests live in [DownloadsDirectory], which is what the
 * in-process and out-of-process plugin providers call too. The three used to answer this
 * question differently, so a plugin could be told a folder other than the one the browser
 * saves into - and a different one again depending on which process it was loaded in.
 */
actual fun getDefaultDownloadsDirectory(): String =
    DownloadsDirectory.current { userHome ->
        // plugin-path-utils has no logger, so a registry read that failed would otherwise look
        // exactly like a Downloads folder that was never moved. Only ever called on Windows.
        WindowsDownloadsFolder.current(userHome).also { folder ->
            if (folder == null && reportedUnreadKnownFolder.compareAndSet(false, true)) {
                logger.info(
                    LogCategory.FILE,
                    "Windows Downloads known folder not read (registry value missing or unreadable, " +
                        "or user.home is not the profile); using the profile Downloads folder",
                    mapOf("userHome" to userHome),
                )
            }
        }
    }
