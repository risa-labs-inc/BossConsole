package ai.rever.boss.plugin.repository.remote

import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginDownloadCacheTest {
    @TempDir
    lateinit var temporary: File

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun rejectsPathSyntaxWithoutTouchingOutsideFiles() {
        val root = File(temporary, "cache")
        val cache = PluginDownloadCache(root)
        val source = File(temporary, "sentinel.jar").also { it.writeText("sentinel") }
        val unsafeValues =
            listOf(
                "",
                ".",
                "..",
                "../sentinel",
                "a/b",
                "a\\b",
                "/absolute",
                "C:drive",
                "nul\u0000",
                "trailing.",
            )
        for (value in unsafeValues) {
            assertFailsWith<IllegalArgumentException> { cache.cacheJar(value, "1.0.0", source) }
            assertFailsWith<IllegalArgumentException> { cache.cacheJar("normal", value, source) }
            assertFailsWith<IllegalArgumentException> { cache.getCachedJar("normal", value, hash(source)) }
            assertFailsWith<IllegalArgumentException> { cache.removeCachedJar("normal", value) }
            assertFailsWith<IllegalArgumentException> { cache.removeAllVersions(value) }
        }
        assertEquals("sentinel", source.readText())
        assertEquals(0, cache.getCachedFileCount())
    }

    @Test
    fun preservesIdsVersionsAndCaseWithoutCacheCollisions() {
        val cache = PluginDownloadCache(File(temporary, "cache"))
        val source = File(temporary, "source.jar").also { it.writeText("jar") }
        val versions = listOf("1.2.3", "1.2.3-rc.1+build_5")
        for (id in listOf("ai.rever.Test-plugin", "ai.rever.test-plugin", "custom_plugin")) {
            for (version in versions) {
                cache.cacheJar(id, version, source)
                assertNotNull(cache.getCachedJar(id, version, hash(source)))
            }
            assertEquals(versions.toSet(), cache.listCachedPlugins()[id]?.toSet())
        }
        assertEquals(3, cache.getCachedPluginCount())
        assertEquals(6, cache.getCachedFileCount())
        assertTrue(cache.getCacheSize() >= 18L)
        assertEquals(2, cache.removeAllVersions("ai.rever.Test-plugin"))
        assertNotNull(cache.getCachedJar("ai.rever.test-plugin", versions[0], hash(source)))
        assertNull(cache.getCachedJar("ai.rever.test-plugin", versions[0], "invalid"))
        assertEquals(3, cache.clearCache())
        assertEquals(0, cache.getCachedFileCount())
    }

    @Test
    fun lookupRequiresMatchingRegularIdentityMetadata() {
        val cache = PluginDownloadCache(File(temporary, "cache"))
        val source = File(temporary, "source.jar").also { it.writeText("jar") }
        val cached = cache.cacheJar("plugin", "1.0.0", source)
        val metadata = File(cached.parentFile, cached.nameWithoutExtension + ".json")
        val original = metadata.readText()
        metadata.writeText("""{"pluginId":"other-plugin","version":"1.0.0"}""")
        assertNull(cache.getCachedJar("plugin", "1.0.0", hash(source)))
        metadata.writeText("""{"pluginId":"plugin","version":"2.0.0"}""")
        assertNull(cache.getCachedJar("plugin", "1.0.0", hash(source)))
        metadata.writeText("invalid JSON")
        assertNull(cache.getCachedJar("plugin", "1.0.0", hash(source)))
        metadata.delete()
        assertNull(cache.getCachedJar("plugin", "1.0.0", hash(source)))
        val outside = File(temporary, "outside.json").also { it.writeText(original) }
        Files.createSymbolicLink(metadata.toPath(), outside.toPath())
        assertNull(cache.getCachedJar("plugin", "1.0.0", hash(source)))
        Files.delete(metadata.toPath())
        metadata.writeText(original)
        assertNotNull(cache.getCachedJar("plugin", "1.0.0", hash(source)))
        assertEquals(original, outside.readText())
    }

    @Test
    fun acceptsLongIdsThatFitTheOriginalStoreContract() {
        val cache = PluginDownloadCache(File(temporary, "cache"))
        val source = File(temporary, "source.jar").also { it.writeText("jar") }
        val id = "com.example." + "a".repeat(200)
        val version = "1.0.0+" + "build".repeat(40)
        cache.cacheJar(id, version, source)
        assertNotNull(cache.getCachedJar(id, version, hash(source)))
        assertEquals(listOf(version), cache.listCachedPlugins()[id])
        assertEquals(1, cache.removeAllVersions(id))
    }

    @Test
    fun cleanupDoesNotTraverseLinkedDirectories() {
        val root = File(temporary, "cache")
        val cache = PluginDownloadCache(root)
        val outside = File(temporary, "outside").also { it.mkdir() }
        val sentinel = File(outside, "sentinel.jar").also { it.writeText("sentinel") }
        Files.setLastModifiedTime(sentinel.toPath(), FileTime.fromMillis(0))
        Files.createSymbolicLink(File(root, "legacy-link").toPath(), outside.toPath())
        assertEquals(0L, cache.getCacheSize())
        assertEquals(0, cache.cleanOldEntries(0))
        cache.clearCache()
        assertEquals("sentinel", sentinel.readText())
    }

    @Test
    fun expiresStaleEntriesAtConstruction() {
        val root = File(temporary, "cache")
        val source = File(temporary, "source.jar").also { it.writeText("jar") }
        val seeded = PluginDownloadCache(root)
        val stale = seeded.cacheJar("plugin", "1.0.0", source)
        val fresh = seeded.cacheJar("plugin", "2.0.0", source)
        val staleMetadata = stale.toPath().resolveSibling(stale.name.removeSuffix(".jar") + ".json")
        val fortyDaysAgo = FileTime.fromMillis(System.currentTimeMillis() - 40L * 86_400_000L)
        Files.setLastModifiedTime(stale.toPath(), fortyDaysAgo)
        Files.setLastModifiedTime(staleMetadata, fortyDaysAgo)

        val cache = PluginDownloadCache(root, sweepDispatcher = Dispatchers.Unconfined)

        assertFalse(stale.exists(), "version past the window should be swept at construction")
        assertFalse(Files.exists(staleMetadata))
        assertTrue(fresh.exists(), "version inside the window should be kept")
        assertEquals(listOf("2.0.0"), cache.listCachedPlugins()["plugin"])
    }

    @Test
    fun constructionSurvivesAnUnusableRoot() {
        val notADirectory = File(temporary, "cache").also { it.writeText("in the way") }
        // Must not throw: the startup sweep fails the same way any later use will, and
        // construction is required to survive that.
        val cache = PluginDownloadCache(notADirectory, sweepDispatcher = Dispatchers.Unconfined)
        assertFailsWith<Exception> { cache.getCachedFileCount() }
        assertEquals("in the way", notADirectory.readText())
    }

    @Test
    fun refusesSymlinkedEntryAndPluginDirectory() {
        val cache = PluginDownloadCache(File(temporary, "cache"))
        val source = File(temporary, "source.jar").also { it.writeText("sentinel") }
        val cached = cache.cacheJar("plugin", "1.0.0", source)
        Files.delete(cached.toPath())
        Files.createSymbolicLink(cached.toPath(), source.toPath())
        assertFailsWith<IllegalStateException> { cache.getCachedJar("plugin", "1.0.0", hash(source)) }
        assertFailsWith<IllegalStateException> { cache.cacheJar("plugin", "1.0.0", source) }
        cache.removeAllVersions("plugin")
        Files.createSymbolicLink(cached.parentFile.toPath(), temporary.toPath())
        assertFailsWith<IllegalStateException> { cache.removeAllVersions("plugin") }
        assertEquals("sentinel", source.readText())
        assertTrue(source.exists())
    }
}
