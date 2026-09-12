package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.name

/** Local, hash-verified cache. Exact IDs and versions are addressed by SHA-256 keys. */
class PluginDownloadCache(
    cacheDir: File = BossDirectories.resolve("plugin-cache"),
) {
    private val root = Files.createDirectories(cacheDir.toPath()).toRealPath()

    private val rootIdentity = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    @Serializable
    private data class CacheMetadata(
        val pluginId: String,
        val version: String,
    )

    @Synchronized
    fun getCachedJar(
        pluginId: String,
        version: String,
        expectedSha256: String,
    ): File? {
        val path = cacheFile(pluginId, version)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(8192)
                var size = input.read(buffer)
                while (size != -1) {
                    digest.update(buffer, 0, size)
                    size = input.read(buffer)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expectedSha256, ignoreCase = true)) {
            path.toFile()
        } else {
            Files.deleteIfExists(path)
            null
        }
    }

    @Synchronized
    fun cacheJar(
        pluginId: String,
        version: String,
        sourceFile: File,
    ): File {
        val target = cacheFile(pluginId, version)
        Files.createDirectories(target.parent)
        checkDirectory(target.parent)
        val temporary = Files.createTempFile(target.parent, ".download-", ".part")
        val metadataPart = Files.createTempFile(target.parent, ".metadata-", ".part")
        try {
            Files.copy(sourceFile.toPath(), temporary, REPLACE_EXISTING)
            Files.writeString(metadataPart, Json.encodeToString(CacheMetadata(pluginId, version)))
            // Metadata names the same ID/version on every write; it does not vouch for bytes.
            // Publish it first so a failed metadata write cannot replace an existing artifact.
            Files.move(metadataPart, metadataPath(target), ATOMIC_MOVE, REPLACE_EXISTING)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(metadataPart)
        }
        return target.toFile()
    }

    @Synchronized
    fun removeCachedJar(
        pluginId: String,
        version: String,
    ): Boolean {
        val path = cacheFile(pluginId, version)
        val removed = Files.deleteIfExists(path)
        Files.deleteIfExists(metadataPath(path))
        return removed
    }

    @Synchronized
    fun removeAllVersions(pluginId: String): Int {
        val directory = pluginDirectory(pluginId)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return 0
        checkDirectory(directory)
        return deleteEntries(directory, includeRoot = true)
    }

    @Synchronized
    fun clearCache(): Int {
        checkRoot()
        return deleteEntries(root, includeRoot = false)
    }

    @Synchronized
    fun getCacheSize(): Long = entries().filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sumOf { Files.size(it) }

    @Synchronized
    fun getCachedPluginCount(): Int = listCachedPlugins().size

    @Synchronized
    fun getCachedFileCount(): Int = entries().count(::isJar)

    private fun isJar(path: Path): Boolean = Files.isRegularFile(path, NOFOLLOW_LINKS) && path.name.endsWith(".jar")

    @Synchronized
    fun listCachedPlugins(): Map<String, List<String>> {
        checkRoot()
        val result = mutableMapOf<String, MutableList<String>>()
        for (path in entries().filter(::isJar)) {
            val metadata = readMetadata(path) ?: continue
            result.getOrPut(metadata.pluginId) { mutableListOf() }.add(metadata.version)
        }
        return result
    }

    private fun metadataPath(jar: Path): Path = jar.resolveSibling(jar.name.removeSuffix(".jar") + ".json")

    private fun readMetadata(jar: Path): CacheMetadata? =
        runCatching {
            val path = metadataPath(jar)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@runCatching null
            val text =
                Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                    Channels.newInputStream(channel).use { input -> input.readNBytes(16_385).toString(Charsets.UTF_8) }
                }
            if (text.length > 16_384) return@runCatching null
            val metadata = Json.decodeFromString<CacheMetadata>(text)
            metadata.takeIf { cacheFile(it.pluginId, it.version) == jar }
        }.getOrNull()

    @Synchronized
    fun cleanOldEntries(maxAgeDays: Int = 30): Int {
        require(maxAgeDays >= 0) { "Cache age must not be negative" }
        val cutoff = System.currentTimeMillis() - maxAgeDays.toLong() * 86_400_000L
        var removed = 0
        for (path in entries()) {
            val expired = Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis() < cutoff
            if (Files.isRegularFile(path, NOFOLLOW_LINKS) && expired) {
                if (Files.deleteIfExists(path)) removed++
            }
        }
        return removed
    }

    private fun checkRoot() {
        checkDirectory(root)
        check(Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == rootIdentity) {
            "Plugin cache directory was replaced"
        }
    }

    private fun checkDirectory(path: Path) {
        check(Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path) {
            "Plugin cache directory must not be a symbolic link"
        }
    }

    private fun pluginDirectory(pluginId: String): Path {
        val component = cacheKey(pluginId)
        checkRoot()
        return root.resolve("p-$component").also {
            if (Files.exists(it, NOFOLLOW_LINKS)) checkDirectory(it)
        }
    }

    private fun cacheFile(
        pluginId: String,
        version: String,
    ): Path {
        val component = cacheKey(version)
        val directory = pluginDirectory(pluginId)
        return directory.resolve("v-$component.jar").also {
            check(!Files.isSymbolicLink(it)) { "Plugin cache entry must not be a symbolic link" }
        }
    }

    private fun cacheKey(value: String): String {
        // Hash the exact value so long IDs, case variants and punctuation never alias.
        // The bound exceeds portable legacy filename limits while bounding metadata reads.
        require(value.length in 1..4096 && value.matches(Regex("[A-Za-z0-9._+-]+")) && !value.endsWith('.')) {
            "Invalid plugin cache ID or version"
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun entries(): List<Path> {
        checkRoot()
        // Files.walk does not follow links. Legacy entries are never used for lookups,
        // but remain removable by clear/expiry without traversing their link targets.
        return Files.walk(root).use { paths -> paths.filter { it != root }.toList() }
    }

    private fun deleteEntries(
        directory: Path,
        includeRoot: Boolean,
    ): Int {
        var count = 0
        Files.walk(directory).use { paths ->
            paths.filter { it != directory || includeRoot }.sorted(Comparator.reverseOrder()).forEach { path ->
                val isFile = isJar(path)
                if (Files.deleteIfExists(path) && isFile) count++
            }
        }
        return count
    }
}
