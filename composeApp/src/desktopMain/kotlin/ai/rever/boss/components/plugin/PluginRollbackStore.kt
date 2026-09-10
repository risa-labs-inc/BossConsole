package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Keeps the jar an update replaced, so there is a way back from a version that will not load.
 *
 * **Why this is needed at all.** An update ends with `PluginJarReconciler.reconcilePluginDir`,
 * which deletes every jar for a plugin except the highest version - so the moment an update
 * finishes, the version that was working no longer exists anywhere. That is fine while the new
 * version loads. It is not fine when the new version declares a floor this build cannot meet: the
 * loader refuses it, the plugin is gone, and nothing on disk can bring it back.
 *
 * That is not hypothetical. fluck-browser 1.2.22 shipped requiring BOSS 9.4.23 against a current
 * release of 9.4.22, so hosts that took the update lost their browser tab, and the recovery was to
 * know the jar had been replaced, find the previous release on GitHub, and put it back by hand. One
 * kept file makes that a button.
 *
 * **Keyed by plugin id, not by jar path.** The first version of this keyed off the jar's own path
 * (`<name>.jar.rollback`) and could not work: the host's update path downloads to a NEW filename
 * (`<pluginId>-<newVersion>.jar`) and reconcile then deletes the old one, so a snapshot taken at
 * the new path finds nothing to copy, and one taken at the old path is addressed by a name nobody
 * holds afterwards. A plugin id is the one identifier that survives a version change.
 *
 * **Deliberately one generation deep.** A history would be a directory of stale jars nobody prunes,
 * each a full copy of a plugin (fluck-browser's is ~5 MB). One is what recovery needs: the state
 * immediately before the change that broke it.
 *
 * The copies live in a `.rollback` subdirectory of the plugin directory. A dot-prefixed directory
 * rather than a suffix in place, so the plugin directory scan cannot mistake a kept jar for an
 * installed one and load two copies of the same plugin.
 */
internal object PluginRollbackStore {
    private val logger = BossLogger.forComponent("PluginRollbackStore")

    private const val DIR_NAME = ".rollback"

    /**
     * Plugin ids are dotted reverse-domain strings and reach here from a manifest, so they are
     * sanitized before becoming a filename for the same reason `PluginUpdateBridge` sanitizes its
     * download name: a `/` or a `..` in an id would address a file outside this directory.
     */
    private fun safeName(pluginId: String) = pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun dir(pluginDir: File) = File(pluginDir, DIR_NAME)

    private fun jarFor(
        pluginDir: File,
        pluginId: String,
    ) = File(dir(pluginDir), safeName(pluginId) + ".jar")

    /**
     * Where the kept version number is written, beside the copy.
     *
     * Recorded at snapshot time rather than read back out of the copy on demand. Reading it back
     * would mean opening a zip on every dialog, and the version is what labels the button, so it
     * has to be knowable without touching the jar at all.
     */
    private fun versionFor(
        pluginDir: File,
        pluginId: String,
    ) = File(dir(pluginDir), safeName(pluginId) + ".version")

    /**
     * Keep a copy of [sourceJarPath] as the way back for [pluginId].
     *
     * Copy rather than move: the caller may still be running from this jar, and an update that
     * removed the live plugin to make a backup would be absurd. A failure is logged and swallowed -
     * losing the ability to roll back is bad, but failing an update because its backup failed is
     * worse.
     *
     * The version is read from the source jar, which is still named `.jar` at this point.
     * `PluginManifestReader.readFromJar` refuses any path that is not (it reports "File is not a
     * JAR"), so this cannot be deferred until after the copy is made.
     */
    fun snapshot(
        pluginDir: File,
        pluginId: String,
        sourceJarPath: String,
    ) {
        val source = File(sourceJarPath)
        if (!source.isFile) return
        val version = runCatching { PluginManifestReader.readFromJar(sourceJarPath).version }.getOrNull()
        if (version == null) {
            // Without a version the button cannot say what it will restore, and an unlabelled
            // "revert" is not something to offer. Any EARLIER copy is left alone: it is correctly
            // labelled with its own version, so restoring it lands on something older than ideal
            // but working - which beats no way back at all.
            logger.warn(
                LogCategory.SYSTEM,
                "Could not read a version from the jar being replaced; keeping any earlier rollback",
                mapOf("pluginId" to pluginId, "jarPath" to sourceJarPath),
            )
            return
        }
        runCatching {
            dir(pluginDir).mkdirs()
            source.copyTo(jarFor(pluginDir, pluginId), overwrite = true)
            versionFor(pluginDir, pluginId).writeText(version)
            // The sidecar travels with it or the restore is unverifiable: a jar whose signature
            // file belongs to different bytes hard-fails the load, which is worse than unsigned.
            val target = jarFor(pluginDir, pluginId).absolutePath
            PluginSignatureSidecar.read(sourceJarPath)?.let { PluginSignatureSidecar.persist(target, it) }
                ?: PluginSignatureSidecar.delete(target)
            // An unsigned bundled copy needs its established provenance on an in-session rollback.
            PluginBundledTrust.copyTrust(sourceJarPath, target)
        }.onFailure { e ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not keep a rollback copy; this update will not be reversible",
                mapOf("pluginId" to pluginId),
                error = e,
            )
        }
    }

    /** The version held for [pluginId], or null when there is none or it cannot be named. */
    fun availableVersion(
        pluginDir: File,
        pluginId: String,
    ): String? {
        // BOTH files, because either alone is not a usable offer: bytes with no recorded version
        // cannot label a button, and a version with no bytes cannot be restored.
        val versionFile = versionFor(pluginDir, pluginId)
        return if (!jarFor(pluginDir, pluginId).isFile || !versionFile.isFile) {
            null
        } else {
            runCatching { versionFile.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
        }
    }

    /**
     * Put the kept jar back into [pluginDir], returning the file now in place.
     *
     * Restores to a version-named file rather than over whatever is there, because what is there is
     * the version being rolled back FROM and it has to be removed, not overwritten: leaving it
     * would give the directory scan two jars for one plugin id.
     *
     * The kept copy is not consumed, so a restore interrupted halfway has not destroyed the only
     * good jar and a second attempt is possible.
     */
    fun restore(
        pluginDir: File,
        pluginId: String,
        currentJarPath: String?,
    ): File? {
        val kept = jarFor(pluginDir, pluginId)
        // availableVersion already requires the jar to exist, so this is the single guard both
        // conditions need rather than two returns saying the same thing.
        val version = availableVersion(pluginDir, pluginId)?.takeIf { kept.isFile } ?: return null
        return runCatching {
            val destination = File(pluginDir, "${safeName(pluginId)}-$version.jar")
            kept.copyTo(destination, overwrite = true)
            PluginSignatureSidecar
                .read(kept.absolutePath)
                ?.let { PluginSignatureSidecar.persist(destination.absolutePath, it) }
                // No signature for the restored bytes beats the WRONG one: a sidecar left from
                // another version fails the load outright.
                ?: PluginSignatureSidecar.delete(destination.absolutePath)
            PluginBundledTrust.copyTrust(kept.absolutePath, destination.absolutePath)
            // Only now remove the version that would not load, and only if it is a different file -
            // a restore onto its own path would otherwise delete what it just wrote.
            currentJarPath
                ?.let(::File)
                ?.takeIf { it.isFile && it.canonicalPath != destination.canonicalPath }
                ?.let { broken ->
                    PluginSignatureSidecar.delete(broken.absolutePath)
                    if (broken.delete()) PluginBundledTrust.delete(broken.absolutePath)
                }
            destination
        }.onFailure { e ->
            logger.error(
                LogCategory.SYSTEM,
                "Could not restore the rollback copy",
                mapOf("pluginId" to pluginId),
                error = e,
            )
        }.getOrNull()
    }

    /** Drop the copy. For an uninstall, where the plugin itself is going away. */
    fun discard(
        pluginDir: File,
        pluginId: String,
    ) {
        runCatching { jarFor(pluginDir, pluginId).delete() }
        runCatching { versionFor(pluginDir, pluginId).delete() }
        runCatching { PluginSignatureSidecar.delete(jarFor(pluginDir, pluginId).absolutePath) }
        runCatching { PluginBundledTrust.delete(jarFor(pluginDir, pluginId).absolutePath) }
    }
}
