package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Downloads are invisible to startup until the final preference/view checks admit installation. */
internal class PluginUpdateArtifact(
    requestedTarget: File,
) {
    val target =
        if (requestedTarget.exists()) {
            File(requestedTarget.parentFile, "${requestedTarget.nameWithoutExtension}-${UUID.randomUUID()}.jar")
        } else {
            requestedTarget
        }
    private var committed = false

    fun commit() {
        committed = true
    }

    val download = File(target.parentFile, "${target.name}.part")

    fun promote(): Result<Unit> =
        runCatching {
            Files.move(download.toPath(), target.toPath())
            Files.deleteIfExists(File("${target.absolutePath}.rejected-update").toPath())
            val signature = File(PluginSignatureSidecar.pathFor(download.absolutePath))
            if (signature.exists()) {
                Files.move(signature.toPath(), File(PluginSignatureSidecar.pathFor(target.absolutePath)).toPath())
            }
            Unit
        }

    fun discardRejected(): Result<Unit> =
        runCatching {
            if (committed) return@runCatching
            discard(download)
            if (target.exists()) {
                // A failed rollback can retain a live loader, especially on Windows. Fence
                // startup even when that loader prevents removing its artifact immediately.
                File("${target.absolutePath}.rejected-update").writeText("Rejected update")
                if (!PluginClassLoader.isPathOpenByLiveLoader(target.absolutePath)) discard(target)
            }
        }

    private fun discard(file: File) {
        if (file.exists()) {
            // Rename first: even when deletion fails this cannot win the next startup's JAR scan.
            val quarantine = File(file.parentFile, "${file.name}.rejected")
            Files.move(file.toPath(), quarantine.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            quarantine.delete()
        }
        PluginSignatureSidecar.delete(file.absolutePath)
    }
}
