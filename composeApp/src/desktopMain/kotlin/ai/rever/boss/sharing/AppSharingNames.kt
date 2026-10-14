package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.WindowProjectStateRegistry
import java.awt.Frame
import java.io.File
import java.util.concurrent.TimeUnit

/** Resolve once on the publication IO thread, without relying on potentially blocking DNS. */
private val sharingDeviceName by lazy {
    val command =
        if (SystemUtils.isMacOS) {
            listOf("/usr/sbin/scutil", "--get", "ComputerName")
        } else {
            listOf("hostname")
        }
    val detected =
        runCatching {
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                if (process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    process.inputStream.use { it.readNBytes(512).decodeToString() }
                } else {
                    null
                }
            } finally {
                process.destroyForcibly()
            }
        }.getOrNull()
    listOf(System.getenv("COMPUTERNAME"), detected, System.getenv("HOSTNAME"), System.getProperty("user.name"))
        .firstOrNull { !it.isNullOrBlank() }
        ?.let(::sharingLabel)
        ?.removeSuffix(".local")
        ?.take(48)
        ?: "This device"
}

/** Read the exact window's project on the EDT; never label it using the global active project. */
internal fun appSharingWindowTitle(target: AppCaptureTarget): String {
    val project = WindowProjectStateRegistry.get(target.windowId)?.currentProject()?.takeIf { it.path.isNotBlank() }
    val title =
        project?.name?.takeIf { it.isNotBlank() }
            ?: project?.path?.let { File(it).name }?.takeIf { it.isNotBlank() }
            ?: (target.awtWindow as? Frame)?.title?.takeIf { it.isNotBlank() }
            ?: "BossConsole"
    return sharingLabel(title).ifBlank { "BossConsole" }.take(120)
}

internal fun appSharingSessionName(): String = "$sharingDeviceName · BOSS"

private fun sharingLabel(value: String): String = value.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
