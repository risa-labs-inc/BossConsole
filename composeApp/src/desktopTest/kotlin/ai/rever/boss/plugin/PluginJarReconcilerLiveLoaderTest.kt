package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins BossConsole#72: a superseded plugin jar must not be deleted while a live
 * [PluginClassLoader] still has it open, even though deleting it would not disturb the
 * classloader itself.
 *
 * The concrete failure this prevents: pty4j resolves its native helper by reopening its own
 * jar by filename the first time a PTY is created, not through the classloader. When the
 * updater deletes the old jar mid-session, any terminal-open that was still in flight against
 * the old plugin failed with `NoSuchFileException` on a path that no longer existed - a real
 * production trace, not a hypothetical.
 */
class PluginJarReconcilerLiveLoaderTest {
    private val temps = mutableListOf<File>()
    private val openLoaders = mutableListOf<PluginClassLoader>()

    @AfterTest
    fun cleanup() {
        openLoaders.forEach { runCatching { it.close() } }
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempPluginDir(): File =
        File.createTempFile("reconcile-live-loader", "").let {
            it.delete()
            it.mkdirs()
            temps.add(it)
            it
        }

    private fun manifestJar(
        dir: File,
        fileName: String,
        pluginId: String,
        version: String,
    ): File {
        val jar = File(dir, fileName)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "Reconciler Live Loader Test",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Missing"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return jar
    }

    private fun openLoaderOver(vararg jars: File): PluginClassLoader {
        val loader =
            PluginClassLoader(
                pluginId = "loader-under-test",
                urls = jars.map { it.toURI().toURL() }.toTypedArray(),
                parent = PluginJarReconcilerLiveLoaderTest::class.java.classLoader,
            )
        openLoaders.add(loader)
        return loader
    }

    @Test
    fun `a superseded jar still open by a live loader is deferred, not deleted`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.reconcile.live"
        val older = manifestJar(dir, "live-plugin-1.0.0.jar", pluginId, "1.0.0")
        manifestJar(dir, "live-plugin-2.0.0.jar", pluginId, "2.0.0")
        // Simulate the still-running classloader an in-flight terminal session would be
        // reading from - deliberately never closed within this test.
        openLoaderOver(older)

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertTrue(result.deferred.contains(older.name), "the open jar must be recorded as deferred: $result")
        assertFalse(result.deleted.contains(older.name), "the open jar must not be recorded as deleted: $result")
        assertTrue(older.exists(), "deferring must mean the file is actually left in place")
    }

    @Test
    fun `once the loader closes, a later reconcile removes the jar it was deferred for`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.reconcile.live2"
        val older = manifestJar(dir, "live2-plugin-1.0.0.jar", pluginId, "1.0.0")
        manifestJar(dir, "live2-plugin-2.0.0.jar", pluginId, "2.0.0")
        val loader = openLoaderOver(older)

        val deferredResult = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)
        assertTrue(deferredResult.deferred.contains(older.name), "sanity: must be deferred while open")

        loader.close()
        val afterCloseResult = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertTrue(
            afterCloseResult.deleted.contains(older.name),
            "once closed, the same superseded jar must be swept: $afterCloseResult",
        )
        assertFalse(older.exists(), "the file must actually be gone this time")
    }

    @Test
    fun `unloading retains the jar and signature until the last loader closes`() {
        val dir = tempPluginDir()
        val id = "ai.rever.boss.plugin.test.reconcile.multiple"
        val older = manifestJar(dir, "multiple-1.0.0.jar", id, "1.0.0")
        manifestJar(dir, "multiple-2.0.0.jar", id, "2.0.0")
        val signature = File(PluginSignatureSidecar.pathFor(older.absolutePath)).apply { writeText("test-sidecar") }
        PluginBundledTrust.bindToBundle(older.absolutePath, older)
        val first = openLoaderOver(older)
        val second = openLoaderOver(older)
        first.close()
        second.markUnloading()
        val retained = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = setOf(id))
        assertTrue(older.name in retained.deferred)
        assertTrue(older.exists())
        assertTrue(signature.exists())
        assertTrue(PluginBundledTrust.isTrusted(older.absolutePath))
        second.close()
        val removed = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = setOf(id))
        assertTrue(older.name in removed.deleted)
        assertFalse(older.exists())
        assertFalse(signature.exists())
        assertFalse(File(PluginBundledTrust.pathFor(older.absolutePath)).exists())
    }

    @Test
    fun `a superseded dependency URL is retained even when it is not the entry jar`() {
        val dir = tempPluginDir()
        val entry = manifestJar(dir, "entry.jar", "com.example.entry", "1.0.0")
        // The manifest makes this URL a reconciler candidate; ordinary library dependencies need no manifest.
        val older = manifestJar(dir, "dependency-1.jar", "com.example.dependency", "1.0.0")
        manifestJar(dir, "dependency-2.jar", "com.example.dependency", "2.0.0")
        val loader = openLoaderOver(entry, older)
        val retained = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = setOf("com.example.dependency"))
        assertTrue(older.name in retained.deferred)
        assertTrue(older.exists())
        loader.close()
        val removed = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = setOf("com.example.dependency"))
        assertTrue(older.name in removed.deleted)
        assertFalse(older.exists())
    }

    @Test
    fun `an unrelated plugin's jar is unaffected by another plugin's live loader`() {
        val dir = tempPluginDir()
        val watchedId = "ai.rever.boss.plugin.test.reconcile.live3"
        val otherId = "ai.rever.boss.plugin.test.reconcile.other"
        val olderWatched = manifestJar(dir, "live3-plugin-1.0.0.jar", watchedId, "1.0.0")
        manifestJar(dir, "live3-plugin-2.0.0.jar", watchedId, "2.0.0")
        val olderOther = manifestJar(dir, "other-plugin-1.0.0.jar", otherId, "1.0.0")
        manifestJar(dir, "other-plugin-2.0.0.jar", otherId, "2.0.0")
        // Only the "watched" plugin has a live loader; "other" has none open at all.
        openLoaderOver(olderWatched)

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertTrue(result.deferred.contains(olderWatched.name))
        assertTrue(
            result.deleted.contains(olderOther.name),
            "a plugin with no live loader must reconcile exactly as before: $result",
        )
    }
}
