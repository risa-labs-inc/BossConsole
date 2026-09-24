package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

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
    private const val GENERATIONS_SUFFIX = ".snapshots"
    private const val POINTER_NAME = "current"
    private const val GENERATION_SUFFIX = ".snapshot"
    private const val STAGING_SUFFIX = ".staging"
    private const val SNAPSHOT_JAR_NAME = "plugin.jar"
    private const val SNAPSHOT_VERSION_NAME = "version"

    /**
     * Plugin ids are dotted reverse-domain strings and reach here from a manifest, so they are
     * sanitized before becoming a filename for the same reason `PluginUpdateBridge` sanitizes its
     * download name: a `/` or a `..` in an id would address a file outside this directory.
     */
    private fun safeName(pluginId: String) = pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private data class SnapshotFiles(
        val jar: File,
        val version: File,
    )

    private fun SnapshotFiles.isComplete(): Boolean = jar.isFile && version.isFile

    private fun SnapshotFiles.readVersion(): String? =
        runCatching {
            version.readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** Owns the on-disk layout so publication mechanics stay separate from update coordination. */
    private class RollbackLayout(
        pluginDir: File,
        pluginId: String,
    ) {
        val generations = File(File(pluginDir, DIR_NAME), safeName(pluginId) + GENERATIONS_SUFFIX)
        private val pointer = File(generations, POINTER_NAME)
        private val legacyJar = File(File(pluginDir, DIR_NAME), safeName(pluginId) + ".jar")
        private val legacyVersion = File(File(pluginDir, DIR_NAME), safeName(pluginId) + ".version")

        /**
         * Resolve the published generation, falling back to the layout used before generations.
         * A present but invalid pointer fails closed rather than exposing stale legacy bytes.
         */
        fun activeSnapshot(): SnapshotFiles? =
            if (pointer.exists()) {
                publishedSnapshot()
            } else {
                SnapshotFiles(legacyJar, legacyVersion).takeIf { it.isComplete() }
            }

        private fun publishedSnapshot(): SnapshotFiles? {
            val generationName =
                runCatching { pointer.readText().trim() }
                    .getOrNull()
                    ?.takeIf { it.endsWith(GENERATION_SUFFIX) && File(it).name == it }
                    ?: return null
            val generation = File(generations, generationName)
            val confined =
                runCatching {
                    generation.canonicalFile.parentFile == generations.canonicalFile
                }.getOrDefault(false)
            return if (confined) {
                SnapshotFiles(
                    jar = File(generation, SNAPSHOT_JAR_NAME),
                    version = File(generation, SNAPSHOT_VERSION_NAME),
                ).takeIf { it.isComplete() }
            } else {
                null
            }
        }

        fun publish(generation: File) {
            pointer.atomicWriteText(generation.name)
        }

        fun cleanupSuperseded(keep: File) {
            generations.listFiles().orEmpty().forEach { candidate ->
                val isGeneration =
                    candidate.name.endsWith(GENERATION_SUFFIX) ||
                        candidate.name.endsWith(STAGING_SUFFIX)
                if (candidate != keep && candidate != pointer && isGeneration) {
                    runCatching { candidate.deleteRecursively() }
                }
            }
        }

        fun discardLegacy() {
            runCatching { legacyJar.delete() }
            runCatching { legacyVersion.delete() }
            runCatching { PluginSignatureSidecar.delete(legacyJar.absolutePath) }
            runCatching { PluginBundledTrust.delete(legacyJar.absolutePath) }
        }

        fun discardAll() {
            runCatching { generations.deleteRecursively() }
            discardLegacy()
        }
    }

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
    @Synchronized
    fun snapshot(
        pluginDir: File,
        pluginId: String,
        sourceJarPath: String,
        beforeCommit: () -> Unit = {},
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
        val layout = RollbackLayout(pluginDir, pluginId)
        val generations = layout.generations
        val generationId = UUID.randomUUID().toString()
        val staging = File(generations, generationId + STAGING_SUFFIX)
        val published = File(generations, generationId + GENERATION_SUFFIX)
        var pointerCommitted = false

        runCatching {
            check(generations.mkdirs() || generations.isDirectory) { "Could not create rollback generation directory" }
            check(staging.mkdir()) { "Could not create rollback staging directory" }

            val stagedJar = File(staging, SNAPSHOT_JAR_NAME)
            source.copyTo(stagedJar)
            File(staging, SNAPSHOT_VERSION_NAME).writeText(version)

            // Stage every piece of provenance beside the staged bytes. A signature belongs to
            // one exact JAR, and bundled trust is content-addressed, so neither may lag behind the
            // bytes exposed by the commit pointer.
            PluginSignatureSidecar.persist(stagedJar.absolutePath, PluginSignatureSidecar.read(sourceJarPath))
            if (PluginBundledTrust.isTrusted(sourceJarPath) &&
                !PluginBundledTrust.copyTrust(sourceJarPath, stagedJar.absolutePath)
            ) {
                throw IOException("Could not preserve bundled trust for rollback snapshot")
            }

            moveGeneration(staging, published)
            beforeCommit()
            layout.publish(published)
            pointerCommitted = true
        }.onFailure { e ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not keep a rollback copy; this update will not be reversible",
                mapOf("pluginId" to pluginId),
                error = e,
            )
        }

        if (pointerCommitted) {
            layout.cleanupSuperseded(published)
            layout.discardLegacy()
        } else {
            staging.deleteRecursively()
            published.deleteRecursively()
        }
    }

    private fun moveGeneration(
        staging: File,
        published: File,
    ) {
        try {
            Files.move(staging.toPath(), published.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), published.toPath())
        }
    }

    /** The version held for [pluginId], or null when there is none or it cannot be named. */
    @Synchronized
    fun availableVersion(
        pluginDir: File,
        pluginId: String,
    ): String? {
        // BOTH files, because either alone is not a usable offer: bytes with no recorded version
        // cannot label a button, and a version with no bytes cannot be restored.
        return RollbackLayout(pluginDir, pluginId).activeSnapshot()?.readVersion()
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
    @Synchronized
    fun restore(
        pluginDir: File,
        pluginId: String,
        currentJarPath: String?,
    ): File? {
        val restorable =
            RollbackLayout(pluginDir, pluginId).activeSnapshot()?.let { snapshot ->
                snapshot.readVersion()?.let { version -> snapshot to version }
            } ?: return null
        val kept = restorable.first.jar
        val version = restorable.second
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
    @Synchronized
    fun discard(
        pluginDir: File,
        pluginId: String,
    ) {
        RollbackLayout(pluginDir, pluginId).discardAll()
    }
}
