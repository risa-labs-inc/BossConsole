package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the invariant that a `.sig` never outlives the JAR it describes.
 *
 * An orphaned sidecar is not merely untidy: plugin JAR names repeat across
 * install/uninstall cycles of the same version, so a leftover signature can end
 * up beside different bytes — which the loader treats as present-but-invalid and
 * hard-fails, unlike the benign missing-sidecar case.
 */
class PluginJarReconcilerSidecarTest {
    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempPluginDir(): File =
        File.createTempFile("reconcile-sidecar", "").let {
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
                  "displayName": "Reconciler Sidecar Test",
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

    @Test
    fun `a persisted plugin update keeps its current jar and sidecar`() = runBlocking {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.background"
        val oldJar = manifestJar(dir, "test-plugin-1.0.0.jar", pluginId, "1.0.0")
        val newJar = manifestJar(dir, "test-plugin-2.0.0.jar", pluginId, "2.0.0")
        PluginSignatureSidecar.write(oldJar.absolutePath, "b2xkLXNpZw==")
        var persistedJar: File? = null

        finishBackgroundSystemPluginUpdate(
            plugin = SystemPluginInfo(pluginId, "owner/repo", "test-plugin", 100),
            promotedJar = newJar,
            pluginDir = dir,
            persistLoadablePlugin = { persistedJar = it },
            persistSignature = { PluginSignatureSidecar.write(it.absolutePath, "bmV3LXNpZw==") },
            manifestIdOf = { file -> if (file == oldJar) pluginId else null },
            onSupersededArtifactProcessed = { _, _ -> error("loadable plugin must not be cleaned up") },
        )

        assertEquals(newJar, persistedJar, "the promoted JAR must be selected for next launch")
        assertTrue(newJar.exists(), "the promoted JAR must remain")
        assertTrue(File(PluginSignatureSidecar.pathFor(newJar.absolutePath)).exists())
        assertTrue(oldJar.exists(), "the current-session JAR must remain")
        assertTrue(
            File(PluginSignatureSidecar.pathFor(oldJar.absolutePath)).exists(),
            "the current-session JAR's sidecar must remain",
        )
    }

    @Test
    fun `a download-only runtime update removes superseded artifacts`() = runBlocking {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.runtime"
        val oldJar = manifestJar(dir, "test-runtime-1.0.0.jar", pluginId, "1.0.0")
        val newJar = manifestJar(dir, "test-runtime-2.0.0.jar", pluginId, "2.0.0")
        PluginSignatureSidecar.write(oldJar.absolutePath, "b2xkLXNpZw==")

        finishBackgroundSystemPluginUpdate(
            plugin = SystemPluginInfo(pluginId, "owner/repo", "test-runtime", 100, downloadOnly = true),
            promotedJar = newJar,
            pluginDir = dir,
            persistLoadablePlugin = { error("download-only artifacts must not be persisted") },
            persistSignature = { PluginSignatureSidecar.write(it.absolutePath, "bmV3LXNpZw==") },
            manifestIdOf = { file -> if (file == oldJar) pluginId else null },
            onSupersededArtifactProcessed = { _, _ -> },
        )

        assertTrue(newJar.exists(), "the promoted runtime artifact must remain")
        assertTrue(File(PluginSignatureSidecar.pathFor(newJar.absolutePath)).exists())
        assertFalse(oldJar.exists(), "the superseded runtime artifact must be cleaned up")
        assertFalse(File(PluginSignatureSidecar.pathFor(oldJar.absolutePath)).exists())
    }

    @Test
    fun `the losing duplicate's sidecar is removed with its jar`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.reconcile"
        val older = manifestJar(dir, "test-plugin-1.0.0.jar", pluginId, "1.0.0")
        val newer = manifestJar(dir, "test-plugin-2.0.0.jar", pluginId, "2.0.0")
        PluginSignatureSidecar.write(older.absolutePath, "b2xkLXNpZw==")
        PluginSignatureSidecar.write(newer.absolutePath, "bmV3LXNpZw==")

        val result = PluginJarReconciler.reconcilePluginDir(dir)

        assertTrue(result.deleted.contains(older.name), "expected the older JAR to be reconciled away")
        assertFalse(older.exists(), "older JAR should be gone")
        assertFalse(
            File(PluginSignatureSidecar.pathFor(older.absolutePath)).exists(),
            "the losing JAR's sidecar must not survive it",
        )
        assertTrue(newer.exists(), "winner JAR should survive")
        assertTrue(
            File(PluginSignatureSidecar.pathFor(newer.absolutePath)).exists(),
            "the winner's sidecar must be left alone",
        )
    }

    @Test
    fun `a lone plugin keeps its sidecar`() {
        val dir = tempPluginDir()
        val jar = manifestJar(dir, "solo-plugin-1.0.0.jar", "ai.rever.boss.plugin.test.solo", "1.0.0")
        PluginSignatureSidecar.write(jar.absolutePath, "c29sby1zaWc=")

        PluginJarReconciler.reconcilePluginDir(dir)

        assertTrue(jar.exists())
        assertTrue(
            File(PluginSignatureSidecar.pathFor(jar.absolutePath)).exists(),
            "nothing was deleted, so nothing should have been unsigned",
        )
    }
}
