package ai.rever.boss.components.plugin

import ai.rever.boss.cli.plugin.ValidatorTestFixturePlugin
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the containment applied to persisted jar paths: installed.json is plain
 * JSON on disk, so a hand-edited `jarPath` (or `../` escape, or a symlink that
 * leaves the managed dir) used to make startup load any jar on the filesystem.
 * [DynamicPluginManager.loadPersistedPlugins] now canonicalizes each entry and
 * refuses anything outside the managed roots - fail closed, row left in place.
 */
class PersistedJarPathContainmentTest {
    private val temps = mutableListOf<File>()
    private val managers = mutableListOf<DynamicPluginManager>()

    @AfterTest
    fun cleanup() {
        runBlocking { managers.forEach { runCatching { it.disposeWindow() } } }
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempDir(name: String): File =
        File.createTempFile("persisted-containment-$name", "").let {
            it.delete()
            it.mkdirs()
            temps.add(it)
            it
        }

    private fun newManager(): DynamicPluginManager {
        val context =
            object : PluginContext {
                override val panelRegistry = PanelRegistry()
                override val tabRegistry = TabRegistry()
                override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            }
        return DynamicPluginManager(
            context.panelRegistry,
            context.tabRegistry,
            PluginSandboxManagerImpl(),
            createSandboxedContext = { _, _ -> context },
        ).also { managers.add(it) }
    }

    /** A jar that actually loads: real manifest plus the fixture class bytes. */
    private fun loadableJar(
        dir: File,
        fileName: String,
        pluginId: String,
    ): File {
        val jar = File(dir, fileName)
        val classEntry = ValidatorTestFixturePlugin::class.java.name.replace('.', '/') + ".class"
        val classBytes =
            ValidatorTestFixturePlugin::class.java.classLoader
                .getResourceAsStream(classEntry)!!
                .readBytes()
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "Containment Test",
                  "version": "1.0.0",
                  "apiVersion": "1.0.0",
                  "mainClass": "${ValidatorTestFixturePlugin::class.java.name}"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
            out.putNextEntry(JarEntry(classEntry))
            out.write(classBytes)
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `a persisted jarPath outside the managed roots is refused, not loaded`() =
        runBlocking {
            val pluginDir = tempDir("plugins")
            val outside = tempDir("outside")
            val evil = loadableJar(outside, "evil.jar", "com.example.containment.evil")
            val manager = newManager()

            val results =
                manager.loadPersistedPlugins(
                    listOf(PersistedPluginEntry("com.example.containment.evil", evil.absolutePath, enabled = true)),
                    allowedRoots = listOf(pluginDir),
                )

            val result = results["com.example.containment.evil"]
            assertNotNull(result)
            assertTrue(result.isFailure, "an out-of-root jarPath must be refused: $result")
            assertNull(manager.getPluginInfo("com.example.containment.evil"), "the plugin must not be registered")
            assertTrue(evil.exists(), "the refused jar is left alone - the row stays for inspection")
        }

    @Test
    fun `a dot-dot escape that resolves outside the root is refused`() =
        runBlocking {
            val pluginDir = tempDir("plugins")
            val outside = tempDir("outside")
            loadableJar(outside, "escape.jar", "com.example.containment.escape")
            val manager = newManager()
            // Both temp dirs share a parent, so this spelling canonicalizes to
            // the real outside jar - exactly what a hand-edited row would hold.
            val escapePath = "${pluginDir.absolutePath}/../${outside.name}/escape.jar"

            val results =
                manager.loadPersistedPlugins(
                    listOf(PersistedPluginEntry("com.example.containment.escape", escapePath, enabled = true)),
                    allowedRoots = listOf(pluginDir),
                )

            assertTrue(
                results["com.example.containment.escape"]?.isFailure == true,
                "the canonical path resolves outside the root and must be refused",
            )
            assertNull(manager.getPluginInfo("com.example.containment.escape"))
        }

    @Test
    fun `an in-root persisted jar still loads`() =
        runBlocking {
            val pluginDir = tempDir("plugins")
            val jar = loadableJar(pluginDir, "good.jar", "com.example.containment.good")
            val manager = newManager()

            val results =
                manager.loadPersistedPlugins(
                    listOf(PersistedPluginEntry("com.example.containment.good", jar.absolutePath, enabled = true)),
                    allowedRoots = listOf(pluginDir),
                )

            val result = results["com.example.containment.good"]
            assertTrue(
                result?.isSuccess == true,
                "an in-root jar must load: ${result?.exceptionOrNull()}",
            )
            assertEquals(PluginState.LOADED, manager.getPluginInfo("com.example.containment.good")?.state)
        }

    @Test
    fun `a stale in-root path still relocates to a same-id jar inside the root`() =
        runBlocking {
            val pluginDir = tempDir("plugins")
            val relocated = loadableJar(pluginDir, "plugin-2.0.0.jar", "com.example.containment.moved")
            val stalePath = File(pluginDir, "plugin-1.0.0.jar").absolutePath // never written
            val manager = newManager()

            val results =
                manager.loadPersistedPlugins(
                    listOf(PersistedPluginEntry("com.example.containment.moved", stalePath, enabled = true)),
                    allowedRoots = listOf(pluginDir),
                )

            assertTrue(
                results["com.example.containment.moved"]?.isSuccess == true,
                "the stale-path fallback still works inside the root: ${results["com.example.containment.moved"]}",
            )
            assertEquals(
                relocated.canonicalPath,
                File(manager.getPluginInfo("com.example.containment.moved")!!.jarPath).canonicalPath,
            )
        }

    @Test
    fun `isContainedPath refuses escapes and accepts in-root paths`() {
        val root = tempDir("root")
        val inside = File(root, "a/b/c.jar")
        inside.parentFile.mkdirs()
        inside.writeText("x")

        assertTrue(isContainedPath(inside.absolutePath, listOf(root)))
        assertFalse(isContainedPath("${root.absolutePath}/../elsewhere.jar", listOf(root)))
        // "/tmp/plugins-evil" must not match root "/tmp/plugins" - Path.startsWith
        // is element-wise, so a shared string prefix is not containment.
        val siblingPrefix = File("${root.absolutePath}-evil/a.jar")
        assertFalse(isContainedPath(siblingPrefix.absolutePath, listOf(root)))
        // Containment does not require existence: stale in-root paths must reach
        // the relocation fallback.
        val staleInside = File(root, "gone.jar")
        assertTrue(isContainedPath(staleInside.absolutePath, listOf(root)))
        assertFalse(isContainedPath(inside.absolutePath, emptyList()), "no roots means nothing is allowed")
    }
}
