package ai.rever.boss.daemon

import ai.rever.boss.plugin.loader.ApiClassLoader
import java.io.File

/** Immutable worker/API artifacts and owner-private restart registrations. */
internal class DaemonServiceStorage(
    private val directory: File,
) {
    fun snapshotArtifact(
        source: File,
        expectedHash: String,
    ): File {
        require(source.isFile && sha256(source) == expectedHash) { "Plugin artifact changed" }
        val artifacts = ownerDirectory(File(directory, "artifacts"))
        val snapshot = File(artifacts, "$expectedHash.jar")
        if (!snapshot.exists()) {
            val temp = File.createTempFile("snapshot-", ".jar", artifacts)
            try {
                ownerFile(temp)
                source.copyTo(temp, overwrite = true)
                require(sha256(temp) == expectedHash) { "Plugin artifact changed during copy" }
                moveAtomically(temp, snapshot)
            } finally {
                temp.delete()
            }
        }
        require(sha256(snapshot) == expectedHash) { "Invalid cached plugin artifact" }
        return snapshot
    }

    fun snapshotApiLoader(parent: ClassLoader): ApiClassLoader {
        val discovered = ApiClassLoader.fromPluginDir(File(directory.parentFile, "plugins"), parent)
        val source = discovered.apiJarPath?.let(::File) ?: return discovered
        return try {
            val snapshot = snapshotArtifact(source, sha256(source))
            ApiClassLoader(snapshot.toURI().toURL(), parent)
        } finally {
            discovered.close()
        }
    }

    fun descriptorFile(key: Pair<String, String>): File =
        File(ownerDirectory(File(directory, "registrations")), "${sha256(key.toString().toByteArray())}.json")

    fun saveRegistration(
        key: Pair<String, String>,
        request: DaemonRequest,
    ) {
        val descriptor = descriptorFile(key)
        val temp = File.createTempFile("registration-", ".tmp", descriptor.parentFile)
        try {
            ownerFile(temp)
            temp.writeText(daemonJson.encodeToString(DaemonRequest.serializer(), request))
            moveAtomically(temp, descriptor)
        } finally {
            temp.delete()
        }
    }

    fun registrationFiles(): List<File> =
        File(directory, "registrations")
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
}

private fun moveAtomically(
    from: File,
    to: File,
) {
    try {
        java.nio.file.Files.move(
            from.toPath(),
            to.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (
        _: java.nio.file.AtomicMoveNotSupportedException,
    ) {
        java.nio.file.Files
            .move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
