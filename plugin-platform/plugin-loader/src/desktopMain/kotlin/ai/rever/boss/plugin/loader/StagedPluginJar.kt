package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * A private temp copy of a plugin JAR, taken once at the top of
 * [DynamicPluginLoaderImpl.loadPlugin] and then used for EVERY later read of
 * that load — manifest, signature digest anchor, binary validation and the
 * classloader's own jar handle.
 *
 * This exists because verification and execution used to open the plugin path
 * three separate times (manifest read, sha256 anchor, classloader open). A
 * local writer able to swap bytes at that path between opens could hand each
 * step a different file — identity of A, digest of B, execution of C. Staging
 * collapses the original path to a single read (the copy itself): the digest
 * is computed over the same stream that writes the copy, so it always anchors
 * exactly the bytes that will later execute. A swap racing the copy produces
 * torn staged bytes, which fail signature verification rather than executing.
 *
 * The copy lands in a per-process private directory with an unguessable name
 * and owner-only permissions — "a verified temp path the writer cannot reach"
 * — and stays there for the plugin's lifetime, since [java.net.URLClassLoader]
 * opens its jars lazily and keeps the handle. Ownership passes to the
 * [PluginClassLoader] on a successful load (it deletes the copy in [close]
 * after releasing the jar handle, which Windows requires); a load that never
 * reaches classloader creation deletes it in place, and [File.deleteOnExit]
 * is the backstop for paths that skip unload entirely.
 */
class StagedPluginJar private constructor(
    /** The staged copy. Everything after staging reads this file, never the original path. */
    val file: File,
    /**
     * SHA-256 of [file]'s bytes, computed over the same stream that wrote
     * them — so the value is correct even if the original path was swapped
     * mid-copy.
     */
    val sha256: String,
) {
    /**
     * Delete the staged copy. Best-effort and idempotent; the normal owner
     * after a successful load is the plugin classloader.
     */
    fun delete() {
        if (file.exists() && !file.delete()) {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not delete staged plugin jar copy",
                mapOf("file" to file.name),
            )
        }
    }

    companion object {
        private val logger = BossLogger.forComponent("StagedPluginJar")

        /**
         * One private directory per process. `createTempDirectory` gives an
         * unguessable name under the user-private temp root; on POSIX the
         * explicit attribute keeps it 0700 regardless of umask so a co-tenant
         * local writer cannot list (and so cannot name) the staged copies.
         */
        private val stagingDir: Path by lazy { createStagingDir() }

        private val posixSupported: Boolean
            get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

        private fun posixAttr(perms: String): FileAttribute<*> =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms))

        private fun createStagingDir(): Path =
            if (posixSupported) {
                Files.createTempDirectory("boss-plugin-staging-", posixAttr("rwx------"))
            } else {
                Files.createTempDirectory("boss-plugin-staging-")
            }

        private fun createStagedFile(): File =
            if (posixSupported) {
                Files.createTempFile(stagingDir, "staged-", ".jar", posixAttr("rw-------")).toFile()
            } else {
                Files.createTempFile(stagingDir, "staged-", ".jar").toFile()
            }

        /**
         * Copy [source] to a fresh staged file, digesting the copied bytes in
         * the same pass — the original path is read exactly once. Mirrors the
         * not-found/not-a-jar failures [PluginManifestReader.readFromJar]
         * produced when it was the first read, so callers keep their error
         * contract.
         */
        @Suppress("TooGenericExceptionCaught")
        fun stage(source: File): StagedPluginJar {
            requirePluginJar(source)
            val target = createStagedFile()
            try {
                val sha256 = copyWithDigest(source, target)
                // Backstop for paths that never reach classloader close()
                // (in-process plugins held until JVM shutdown, a crash).
                target.deleteOnExit()
                return StagedPluginJar(target, sha256)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }

        private fun requirePluginJar(source: File) {
            if (!source.isFile) {
                throw PluginManifestException("Plugin JAR not found: ${source.path}")
            }
            if (!source.name.endsWith(".jar")) {
                throw PluginManifestException("File is not a JAR: ${source.path}")
            }
        }

        /** Stream [source] into [target] while hashing; returns the copy's SHA-256. */
        private fun copyWithDigest(
            source: File,
            target: File,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read = input.read(buffer)
                    while (read != -1) {
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }
            return FileHashing.hexOf(digest.digest())
        }
    }
}
