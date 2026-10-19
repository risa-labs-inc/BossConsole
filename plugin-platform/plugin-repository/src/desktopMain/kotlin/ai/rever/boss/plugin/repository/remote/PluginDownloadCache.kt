package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
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
    private val logger = BossLogger.forComponent("PluginDownloadCache")

    // Resolve the host-supplied root once, including intentional host directory links.
    // Retain initialization failure so it cannot prevent repository construction.
    private val location =
        runCatching {
            val path = Files.createDirectories(cacheDir.toPath()).toRealPath()
            path to Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        }
    private val root: Path get() = location.getOrThrow().first
    private val rootIdentity: Any? get() = location.getOrThrow().second

    @Serializable
    private data class CacheMetadata(
        val pluginId: String,
        val version: String,
    )

    /** What a query needs from one cached jar, held in memory so queries never stat the tree. */
    private class IndexEntry(
        val pluginId: String,
        val version: String,
        val sizeBytes: Long,
    )

    /**
     * In-memory picture of the cache directory: built once here at construction and kept by
     * every mutation below, so the query methods never walk the tree. The scan runs before the
     * instance can be shared, so there is nothing to race; and like [location], a build failure
     * is retained and rethrown by queries rather than preventing construction.
     */
    private val index = runCatching { scanIndex() }

    private fun scanIndex(): MutableMap<Path, IndexEntry> {
        checkRoot()
        val scanned = mutableMapOf<Path, IndexEntry>()
        // Files.walk does not follow links, so a linked directory inside the cache is
        // listed but never traversed - and isJar refuses it on top of that.
        Files.walk(root).use { paths ->
            paths.filter { it != root && isJar(it) }.forEach { path ->
                readMetadata(path)?.let { metadata ->
                    scanned[path] = IndexEntry(metadata.pluginId, metadata.version, Files.size(path))
                }
            }
        }
        return scanned
    }

    @Synchronized
    fun getCachedJar(
        pluginId: String,
        version: String,
        expectedSha256: String,
    ): File? {
        val path = cacheFile(pluginId, version)
        val metadata = if (Files.isRegularFile(path, NOFOLLOW_LINKS)) readMetadata(path) else null
        if (metadata == null) {
            // A miss here also means the index entry, if any, points at a jar that is gone or
            // no longer identifiable - dropping it keeps listCachedPlugins honest about what
            // is actually usable.
            index.getOrNull()?.remove(path)
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                logger.warn(LogCategory.SYSTEM, "Ignoring plugin cache entry with invalid identity metadata")
            }
            return null
        }
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
            // Re-add on a verified hit: a jar whose metadata was repaired after a miss must
            // come back into listings, not stay invisible until the next construction.
            index.getOrNull()?.set(path, IndexEntry(metadata.pluginId, metadata.version, Files.size(path)))
            path.toFile()
        } else {
            logger.warn(LogCategory.SYSTEM, "Ignoring plugin cache entry with mismatching SHA-256")
            Files.deleteIfExists(path)
            Files.deleteIfExists(metadataPath(path))
            index.getOrNull()?.remove(path)
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
            index.getOrNull()?.set(target, IndexEntry(pluginId, version, Files.size(target)))
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
        index.getOrNull()?.remove(path)
        return removed
    }

    fun removeAllVersions(pluginId: String): Int {
        val directory = pluginDirectory(pluginId)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return 0
        // The walk that enumerates victims runs outside the monitor - collecting paths is
        // the expensive part, and holding the lock across it stalled every other cache op.
        // The delete pass itself is unlink-only and bounded by what was collected.
        val victims = collectForDeletion(directory, includeRoot = true)
        return synchronized(this) { deleteCollected(victims) }
    }

    fun clearCache(): Int {
        checkRoot()
        val victims = collectForDeletion(root, includeRoot = false)
        return synchronized(this) { deleteCollected(victims) }
    }

    fun getCacheSize(): Long = synchronized(this) { index.getOrThrow().values.sumOf { it.sizeBytes } }

    fun getCachedPluginCount(): Int =
        synchronized(this) {
            index
                .getOrThrow()
                .values
                .mapTo(mutableSetOf()) { it.pluginId }
                .size
        }

    fun getCachedFileCount(): Int = synchronized(this) { index.getOrThrow().size }

    private fun isJar(path: Path): Boolean = Files.isRegularFile(path, NOFOLLOW_LINKS) && path.name.endsWith(".jar")

    fun listCachedPlugins(): Map<String, List<String>> =
        synchronized(this) { index.getOrThrow().values.groupBy({ it.pluginId }, { it.version }) }

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

    fun cleanOldEntries(maxAgeDays: Int = 30): Int {
        require(maxAgeDays >= 0) { "Cache age must not be negative" }
        val cutoff = System.currentTimeMillis() - maxAgeDays.toLong() * 86_400_000L
        // Expired candidates are collected outside the monitor; the locked delete pass is
        // unlink-only and bounded by what actually expired, not by the whole tree.
        val expired =
            entries().filter { path ->
                Files.isRegularFile(path, NOFOLLOW_LINKS) &&
                    Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis() < cutoff
            }
        if (expired.isEmpty()) return 0
        return synchronized(this) {
            var removed = 0
            for (path in expired) {
                if (Files.deleteIfExists(path)) removed++
            }
            index.getOrNull()?.keys?.removeAll(expired.toSet())
            removed
        }
    }

    private fun checkRoot() {
        checkDirectory(root)
        // Some providers (including Windows) return null: only path checks apply there.
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

    /**
     * Collect every path under [directory] for deletion, deepest first.
     *
     * Runs unlocked on purpose: callers hand the list to [deleteCollected] under the monitor.
     * The directory vanishing between the caller's existence check and the walk - a racing
     * [clearCache], or external removal - yields no victims rather than a failure.
     */
    private fun collectForDeletion(
        directory: Path,
        includeRoot: Boolean,
    ): List<Path> =
        try {
            Files.walk(directory).use { paths ->
                paths.filter { it != directory || includeRoot }.sorted(Comparator.reverseOrder()).toList()
            }
        } catch (e: NoSuchFileException) {
            logger.debug(
                LogCategory.SYSTEM,
                "Plugin cache directory vanished during collection",
                mapOf("path" to e.file),
            )
            emptyList()
        }

    /** Delete [victims] and drop them from the index. Caller must hold the monitor. */
    private fun deleteCollected(victims: List<Path>): Int {
        var removed = 0
        for (path in victims) {
            val isFile = isJar(path)
            if (Files.deleteIfExists(path) && isFile) removed++
        }
        index.getOrNull()?.keys?.removeAll(victims.toSet())
        return removed
    }
}
