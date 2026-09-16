package ai.rever.boss.plugin.launchpad

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ai.rever.boss.plugin.api.PluginManifest as ApiPluginManifest

class DevPluginReloaderTest {
    @TempDir
    lateinit var tempDir: Path

    private val activeManagersToClean = mutableListOf<DynamicPluginManager>()

    @BeforeTest
    fun setUp() {
        DevPluginArtifacts.stagingRootOverride = tempDir.resolve("dev-root").toFile().apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            activeManagersToClean.forEach {
                runCatching { it.disposeWindow() }
            }
            activeManagersToClean.clear()
        }
        DevPluginArtifacts.stagingRootOverride = null
    }

    private fun createManager(): DynamicPluginManager {
        val sandboxManager = PluginSandboxManagerImpl()
        val manager =
            DynamicPluginManager(
                PanelRegistry(),
                TabRegistry(),
                sandboxManager,
                createSandboxedContext = { _, _ -> error("Test fixture context") },
            )
        activeManagersToClean.add(manager)
        return manager
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPluginInfo(
        manager: DynamicPluginManager,
        pluginId: String,
        info: DynamicPluginInfo,
    ) {
        val field = DynamicPluginManager::class.java.getDeclaredField("_pluginStates").apply { isAccessible = true }
        val flow = field.get(manager) as MutableStateFlow<Map<String, DynamicPluginInfo>>
        flow.value = flow.value + (pluginId to info)
    }

    @Test
    fun `four successive stages across two managers preserves open older jar`() {
        val pluginId = "multi-window-plugin"
        val stagingRoot = DevPluginArtifacts.stagingRoot()
        val pluginDevDir = File(stagingRoot, pluginId).apply { mkdirs() }

        // 1. Window 1 and Window 2 both load v1 (staged at v1000)
        // 2. Perform stage + reload to v2 (both managers update to v2000)
        // 3. Window 2 holds a lock/handle on v2, while Window 1 performs successive links to v3, v4, and v5
        val timestamps = listOf(1000L, 2000L, 3000L, 4000L, 5000L)
        val jarFiles = mutableMapOf<Long, File>()
        for (ts in timestamps) {
            val vDir = File(pluginDevDir, "v$ts").apply { mkdirs() }
            val jar = File(vDir, "$pluginId.jar")
            jar.writeText("content-$ts")
            jarFiles[ts] = jar
        }

        val v2Jar = jarFiles[2000L]!!
        val activeJarPaths = setOf(v2Jar.absolutePath)

        // 4. Assert that DevPluginArtifacts.pruneStagingHistory prunes unreferenced versions,
        // but strictly retains v2 because Window 2's manager still lists v2 in activeJarPaths.
        DevPluginArtifacts.pruneStagingHistory(
            pluginDevDir = pluginDevDir,
            maxVersionsToKeep = 3,
            activeJarPaths = activeJarPaths,
        )

        val remaining =
            pluginDevDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("v") }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()

        // v1000 is pruned because it is not active and older than top-3 newest (v3000, v4000, v5000).
        // v2000 MUST be retained because Window 2 still holds an active handle on it!
        // v3000, v4000, v5000 are the top 3 newest versions.
        assertEquals(
            listOf("v2000", "v3000", "v4000", "v5000"),
            remaining,
            "v2000 must be retained because it is actively referenced by Window 2",
        )
    }

    @Test
    fun `two managers dry-run pre-flight check aborts if one manager has protected plugin`() =
        runBlocking {
            val pluginId = "protected-test-plugin"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val vDir = File(stagingRoot, "$pluginId/v1000").apply { mkdirs() }
            java.util.jar.JarOutputStream(File(vDir, "$pluginId.jar").outputStream()).use { jar ->
                jar.putNextEntry(java.util.jar.JarEntry("META-INF/boss-plugin/plugin.json"))
                jar.write("""{"pluginId":"$pluginId"}""".toByteArray())
                jar.closeEntry()
            }

            val manager1 = createManager()
            val manager2 = createManager()

            // Manager 1 can unload
            val info1 =
                DynamicPluginInfo(
                    manifest =
                        ApiPluginManifest(
                            pluginId = pluginId,
                            displayName = "Test Plugin",
                            version = "1.0.0",
                            apiVersion = "1.0.0",
                            mainClass = "test.Main",
                            canUnload = true,
                        ),
                    jarPath = File(vDir, "$pluginId.jar").absolutePath,
                    state = PluginState.LOADED,
                    loadedAt = System.currentTimeMillis(),
                    enabled = true,
                )
            setPluginInfo(manager1, pluginId, info1)

            // Manager 2 cannot unload (system protected / canUnload=false)
            val info2 =
                DynamicPluginInfo(
                    manifest =
                        ApiPluginManifest(
                            pluginId = pluginId,
                            displayName = "Test Plugin",
                            version = "1.0.0",
                            apiVersion = "1.0.0",
                            mainClass = "test.Main",
                            canUnload = false,
                        ),
                    jarPath = File(vDir, "$pluginId.jar").absolutePath,
                    state = PluginState.LOADED,
                    loadedAt = System.currentTimeMillis(),
                    enabled = true,
                )
            setPluginInfo(manager2, pluginId, info2)

            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Pre-flight check must abort reload across all managers")
            val exception = result.exceptionOrNull()
            assertTrue(
                exception?.message?.contains("protected system plugin") == true,
                "Expected protected plugin error message, got: ${exception?.message}",
            )

            // Manager 1's plugin state must remain untouched (never unloaded)
            assertEquals(PluginState.LOADED, manager1.getPluginInfo(pluginId)?.state)
            assertEquals(PluginState.LOADED, manager2.getPluginInfo(pluginId)?.state)
        }

    @Test
    fun `reload fails when no host manager is initialized`() =
        runBlocking {
            val result = DevPluginReloader.reload("non-existent-plugin")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("not yet initialized") == true)
        }

    @Test
    fun `reload fails when staged dev JAR is missing`() =
        runBlocking {
            createManager()
            val result = DevPluginReloader.reload("missing-jar-plugin")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("No staged dev JAR found") == true)
        }
}
