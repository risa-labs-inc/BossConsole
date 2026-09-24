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
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ai.rever.boss.plugin.api.PluginManifest as ApiPluginManifest

/**
 * Regression tests for #1217: the session-preserved staging path set must stay bounded -
 * oldest paths evicted once the per-plugin cap is reached - while the cross-attempt
 * retention DevPluginRollbackTest pins (every reload's JAR outliving later reloads)
 * keeps working below the cap.
 *
 * Both tests drive reload through the pre-flight abort path on purpose: the recording
 * that builds the preserved set happens before the pre-flight check, so the abort
 * exercises exactly the code under test without installing anything.
 */
class DevPluginReloaderSessionBoundsTest {
    @TempDir
    lateinit var tempDir: Path

    private val activeManagersToClean = mutableListOf<DynamicPluginManager>()

    @BeforeTest
    fun setUp() {
        DevPluginArtifacts.stagingRootOverride = tempDir.resolve("dev-root").toFile().apply { mkdirs() }
        DevPluginReloader.clearSessionPreservedPathsForTest()
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

    @Test
    fun `session preserved set evicts oldest paths once the cap is reached`() =
        runBlocking {
            val pluginId = "com.rever.sessionbounds"
            val manager = createManager()
            val protectedManager = createManager()

            val recordedPerAttempt = mutableListOf<Pair<String, String>>()
            repeat(12) { attempt ->
                val staged = stageDevJar(pluginId, version = 1000L + attempt)
                val currentPrior = File(tempDir.toFile(), "prior-attempt-$attempt.jar").absolutePath
                setPluginInfo(manager, pluginId, pluginInfo(pluginId, currentPrior))
                setPluginInfo(
                    protectedManager,
                    pluginId,
                    pluginInfo(pluginId, currentPrior, canUnload = false),
                )
                recordedPerAttempt.add(staged.absolutePath to currentPrior)

                val result = DevPluginReloader.reload(pluginId)
                assertTrue(result.isFailure, "Reload must abort: a manager protects the plugin")
            }

            val preserved = preservedPathsFor(pluginId)
            assertEquals(10, preserved.size, "12 reloads x 2 paths must cap at 10, not accumulate to 24")
            assertTrue(
                recordedPerAttempt.first().first !in preserved,
                "the oldest attempt's staged JAR must have been evicted",
            )
            val expectedNewestTen =
                recordedPerAttempt
                    .takeLast(5)
                    .flatMap { (staged, prior) -> listOf(staged, prior) }
                    .toSet()
            assertEquals(
                expectedNewestTen,
                preserved,
                "exactly the 10 most recently recorded paths must remain preserved",
            )
        }

    @Test
    fun `session preserved set keeps every path from recent reloads below the cap`() =
        runBlocking {
            val pluginId = "com.rever.sessionretention"
            val manager = createManager()
            val protectedManager = createManager()

            val recorded = mutableListOf<String>()
            repeat(3) { attempt ->
                val staged = stageDevJar(pluginId, version = 1000L + attempt)
                val currentPrior = File(tempDir.toFile(), "prior-attempt-$attempt.jar").absolutePath
                setPluginInfo(manager, pluginId, pluginInfo(pluginId, currentPrior))
                setPluginInfo(
                    protectedManager,
                    pluginId,
                    pluginInfo(pluginId, currentPrior, canUnload = false),
                )
                recorded.add(staged.absolutePath)
                recorded.add(currentPrior)

                val result = DevPluginReloader.reload(pluginId)
                assertTrue(result.isFailure, "Reload must abort: a manager protects the plugin")
            }

            assertEquals(
                recorded.toSet(),
                preservedPathsFor(pluginId),
                "3 reloads x 2 paths are below the cap: every path must still be retained",
            )
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

    private fun pluginInfo(
        pluginId: String,
        jarPath: String,
        canUnload: Boolean = true,
    ): DynamicPluginInfo =
        DynamicPluginInfo(
            manifest =
                ApiPluginManifest(
                    pluginId = pluginId,
                    displayName = "Test Plugin",
                    version = "1.0.0",
                    apiVersion = "1.0.0",
                    mainClass = "test.Main",
                    canUnload = canUnload,
                ),
            jarPath = jarPath,
            state = PluginState.LOADED,
            loadedAt = System.currentTimeMillis(),
            enabled = true,
        )

    private fun stageDevJar(
        pluginId: String,
        version: Long,
    ): File {
        val devDir = DevPluginArtifacts.pluginDevDir(pluginId, DevPluginArtifacts.stagingRoot())
        val vDir = File(devDir, "v$version").apply { mkdirs() }
        val jar = File(vDir, "$pluginId.jar")
        JarOutputStream(jar.outputStream()).use { stream ->
            stream.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            stream.write("{\"pluginId\":\"$pluginId\"}".toByteArray())
            stream.closeEntry()
        }
        return jar
    }

    /** Reads the reloader's private session map via reflection: no production test hooks. */
    @Suppress("UNCHECKED_CAST")
    private fun preservedPathsFor(pluginId: String): Set<String> {
        val clazz = DevPluginReloader::class.java
        val instance = clazz.getDeclaredField("INSTANCE").get(null)
        val field = clazz.getDeclaredField("sessionPreservedPaths").apply { isAccessible = true }
        val map = field.get(instance) as Map<String, Set<String>>
        return map[pluginId].orEmpty().toSet()
    }
}
