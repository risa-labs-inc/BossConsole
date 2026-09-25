package ai.rever.boss.plugin

import ai.rever.boss.cli.plugin.ValidatorTestFixturePlugin
import ai.rever.boss.components.plugin.DependentRestartEventBus
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import ai.rever.boss.plugin.api.PluginUnloadAware
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRemovalAcrossWindowsTest {
    @Test
    fun `removing a plugin unloads it from every window`() =
        runBlocking {
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = createManager(firstSandbox)
            val second = createManager(secondSandbox)
            val pluginId = "com.example.remove-across-windows"

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId)

                assertTrue(first.hasEntry(pluginId))
                assertTrue(second.hasEntry(pluginId))

                val result = PluginRemoval.remove(pluginId, "", first)

                assertTrue(result.isSuccess, "Removal failed: ${result.exceptionOrNull()}")
                assertFalse(first.hasEntry(pluginId))
                assertFalse(
                    second.hasEntry(pluginId),
                    "Removing a plugin in one window must unload it from the other window",
                )
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
            }
        }

    @Test
    fun `protected plugin in another window blocks removal before any unload`() =
        runBlocking {
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = createManager(firstSandbox)
            val second = createManager(secondSandbox)
            val pluginId = "com.example.protected-across-windows"
            val jar = Files.createTempFile("protected-plugin-", ".jar").toFile()

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId, canUnload = false)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isFailure)
                assertTrue(first.hasEntry(pluginId), "The first window must remain unchanged")
                assertTrue(second.hasEntry(pluginId), "The protected window must remain unchanged")
                assertTrue(jar.exists(), "A refused removal must not delete the JAR")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `failed unload in another window preserves the plugin JAR`() =
        runBlocking {
            val pluginId = "com.example.failed-removal-across-windows"
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val failingSandbox =
                object : PluginSandboxManager by secondSandbox {
                    override suspend fun removeSandbox(pluginId: String) {
                        if (pluginId == "com.example.failed-removal-across-windows") {
                            error("Second window teardown failed")
                        }
                        secondSandbox.removeSandbox(pluginId)
                    }
                }
            val first = createManager(firstSandbox)
            val second = createManager(failingSandbox)
            val jar = Files.createTempFile("failed-plugin-removal-", ".jar").toFile()

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isFailure, "Removal must report the second window's failure")
                assertTrue(second.hasEntry(pluginId), "The failed window still owns the plugin")
                assertTrue(jar.exists(), "The JAR must remain while any window owns the plugin")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `removal unloads two real plugin instances before deleting the JAR`() =
        runBlocking {
            val pluginId = "com.example.real-removal-across-windows"
            val jar = createRealPluginJar(pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)

            try {
                assertTrue(first.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(first.hasEntry(pluginId))
                assertTrue(second.hasEntry(pluginId))

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isSuccess, "Removal failed: ${result.exceptionOrNull()}")
                assertFalse(first.hasEntry(pluginId))
                assertFalse(second.hasEntry(pluginId))
                assertFalse(jar.exists(), "The JAR should be deleted after both unloads")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `removal deletes both JAR paths used by open windows`() =
        runBlocking {
            val pluginId = "com.example.removal-with-two-jar-paths"
            val firstJar = createRealPluginJar(pluginId)
            val secondJar = createRealPluginJar(pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)

            try {
                assertTrue(first.installPlugin(firstJar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(secondJar.absolutePath).isSuccess)
                assertTrue(firstJar.exists())
                assertTrue(secondJar.exists())

                val result = PluginRemoval.remove(pluginId, firstJar.absolutePath, first)

                assertTrue(result.isSuccess, "Removal failed: ${result.exceptionOrNull()}")
                assertFalse(first.hasEntry(pluginId))
                assertFalse(second.hasEntry(pluginId))
                assertFalse(firstJar.exists(), "The initiating window's JAR must be deleted")
                assertFalse(secondJar.exists(), "The other window's JAR must also be deleted")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                firstJar.delete()
                secondJar.delete()
            }
        }

    @Test
    fun `another window's unload veto leaves both windows unchanged`() =
        runBlocking {
            val pluginId = "com.example.veto-across-windows"
            val jar = createRealPluginJar(pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)
            val veto =
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult =
                        CanUnloadResult.NotAllowed(listOf("Second window still needs the plugin"))

                    override fun prepareForUnload(pluginId: String) = Unit
                }

            try {
                assertTrue(first.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(jar.absolutePath).isSuccess)
                second.registerUnloadAware(veto)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isFailure, "The second window's veto must stop removal")
                assertTrue(first.hasEntry(pluginId), "Check all windows before unloading the first")
                assertTrue(second.hasEntry(pluginId))
                assertTrue(jar.exists())
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `a loaded dependent does not bypass another window's unload veto`() =
        runBlocking {
            val pluginId = "com.example.target-with-dependent"
            val dependentId = "com.example.depends-on-target"
            val jar = createRealPluginJar(pluginId)
            val dependentJar = createRealPluginJar(dependentId, dependsOn = pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)
            val veto =
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult =
                        CanUnloadResult.NotAllowed(listOf("Second window cannot release this plugin"))

                    override fun prepareForUnload(pluginId: String) = Unit
                }

            try {
                assertTrue(first.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(dependentJar.absolutePath).isSuccess)
                assertTrue(
                    second.dependentsOf(pluginId).any { it.pluginId == dependentId },
                    "The second window must have a loaded dependent for this test",
                )
                second.registerUnloadAware(veto)

                val result = withTimeout(5_000) { PluginRemoval.remove(pluginId, jar.absolutePath, first) }

                assertTrue(result.isFailure)
                assertTrue(first.hasEntry(pluginId))
                assertTrue(second.hasEntry(pluginId))
                assertTrue(jar.exists())
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
                dependentJar.delete()
            }
        }

    @Test
    fun `one failed window does not skip unloading another window`() =
        runBlocking {
            val pluginId = "com.example.continue-removal-after-failure"
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val failingSandbox =
                object : PluginSandboxManager by firstSandbox {
                    override suspend fun removeSandbox(pluginId: String) {
                        if (pluginId == "com.example.continue-removal-after-failure") {
                            error("First window teardown failed")
                        }
                        firstSandbox.removeSandbox(pluginId)
                    }
                }
            val first = createManager(failingSandbox)
            val second = createManager(secondSandbox)
            val jar = Files.createTempFile("continued-plugin-removal-", ".jar").toFile()

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(result.isFailure, "The first window's failure must be reported")
                assertTrue(first.hasEntry(pluginId), "The failed window still owns the plugin")
                assertFalse(second.hasEntry(pluginId), "Removal must still attempt the second window")
                assertTrue(jar.exists(), "The JAR must remain while the first window owns the plugin")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `a target already unloaded by another window does not block cleanup`() =
        runBlocking {
            val pluginId = "com.example.disappearing-removal-target"
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            lateinit var second: DynamicPluginManager
            var removedDuringFirstUnload = false

            val interceptingSandbox =
                object : PluginSandboxManager by firstSandbox {
                    override suspend fun removeSandbox(pluginId: String) {
                        if (pluginId == "com.example.disappearing-removal-target" && !removedDuringFirstUnload) {
                            removedDuringFirstUnload = second.uninstallPlugin(pluginId).isSuccess
                        }
                        firstSandbox.removeSandbox(pluginId)
                    }
                }

            val first = createManager(interceptingSandbox)
            second = createManager(secondSandbox)
            val jar = Files.createTempFile("disappearing-plugin-removal-", ".jar").toFile()

            try {
                addPluginState(first, pluginId)
                addPluginState(second, pluginId)

                val result = PluginRemoval.remove(pluginId, jar.absolutePath, first)

                assertTrue(removedDuringFirstUnload, "The second window must unload during the first")
                assertTrue(result.isSuccess, "An already-unloaded target must not fail removal")
                assertFalse(first.hasEntry(pluginId))
                assertFalse(second.hasEntry(pluginId))
                assertFalse(jar.exists(), "Cleanup should run once no window owns the plugin")
            } finally {
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
            }
        }

    @Test
    fun `confirmed dependent allows removal across both windows`() =
        runBlocking {
            val pluginId = "com.example.confirmed-removal-target"
            val dependentId = "com.example.confirmed-removal-dependent"
            val jar = createRealPluginJar(pluginId)
            val dependentJar = createRealPluginJar(dependentId, dependsOn = pluginId)
            val firstSandbox = PluginSandboxManagerImpl()
            val secondSandbox = PluginSandboxManagerImpl()
            val first = loadedManager(firstSandbox)
            val second = loadedManager(secondSandbox)
            val firstRestarted = CompletableDeferred<String>()
            val secondRestarted = CompletableDeferred<String>()
            first.restartDependentPlugin = { id ->
                firstRestarted.complete(id)
            }
            second.restartDependentPlugin = { id ->
                secondRestarted.complete(id)
            }
            var promptedFor: String? = null
            var promptedDependents: List<String> = emptyList()

            // Start collecting before removal sends the prompt.
            val responder =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val prompt = DependentRestartEventBus.restartPrompts.first()
                    promptedFor = prompt.targetPluginId
                    promptedDependents = prompt.dependents.map { it.pluginId }
                    prompt.answer.complete(true)
                }

            try {
                assertTrue(first.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(first.installPlugin(dependentJar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(jar.absolutePath).isSuccess)
                assertTrue(second.installPlugin(dependentJar.absolutePath).isSuccess)
                assertTrue(second.dependentsOf(pluginId).any { it.pluginId == dependentId })

                val result =
                    withTimeout(15_000) {
                        PluginRemoval.remove(pluginId, jar.absolutePath, first)
                    }
                responder.join()

                assertEquals(dependentId, withTimeout(5_000) { firstRestarted.await() })
                assertEquals(dependentId, withTimeout(5_000) { secondRestarted.await() })
                assertEquals(pluginId, promptedFor)
                assertEquals(listOf(dependentId), promptedDependents)
                assertTrue(result.isSuccess, "Confirmed removal failed: ${result.exceptionOrNull()}")
                assertFalse(first.hasEntry(pluginId))
                assertFalse(second.hasEntry(pluginId))
                assertFalse(jar.exists(), "The JAR should be removed after both windows unload")
            } finally {
                responder.cancelAndJoin()
                first.disposeWindow()
                second.disposeWindow()
                firstSandbox.dispose()
                secondSandbox.dispose()
                jar.delete()
                dependentJar.delete()
            }
        }

    private fun createRealPluginJar(
        pluginId: String,
        dependsOn: String? = null,
    ): java.io.File {
        val jar = Files.createTempFile("real-plugin-removal-", ".jar").toFile()
        val pluginClass = ValidatorTestFixturePlugin::class.java
        val classEntry = pluginClass.name.replace('.', '/') + ".class"
        val dependencies =
            if (dependsOn == null) {
                "[]"
            } else {
                """[{"pluginId":"$dependsOn","version":"1.0.0","optional":false}]"""
            }

        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            output.write(
                """
                {
                "manifestVersion": 1,
                "pluginId": "$pluginId",
                "displayName": "Real removal test",
                "version": "1.0.0",
                "apiVersion": "1.0.0",
                "mainClass": "${pluginClass.name}",
                "dependencies": $dependencies
                }
                """.trimIndent().toByteArray(),
            )
            output.closeEntry()

            output.putNextEntry(JarEntry(classEntry))
            output.write(pluginClass.classLoader.getResourceAsStream(classEntry)!!.use { it.readBytes() })
            output.closeEntry()
        }
        return jar
    }

    private fun loadedManager(sandbox: PluginSandboxManagerImpl): DynamicPluginManager {
        val context =
            object : PluginContext {
                override val panelRegistry = PanelRegistry()
                override val tabRegistry = TabRegistry()
                override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            }
        return DynamicPluginManager(
            context.panelRegistry,
            context.tabRegistry,
            sandbox,
            createSandboxedContext = { _, _ -> context },
        )
    }

    private fun createManager(sandbox: PluginSandboxManager) =
        DynamicPluginManager(
            PanelRegistry(),
            TabRegistry(),
            sandbox,
            createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
        )

    private fun addPluginState(
        manager: DynamicPluginManager,
        pluginId: String,
        canUnload: Boolean = true,
    ) {
        val info =
            DynamicPluginInfo(
                manifest =
                    PluginManifest(
                        pluginId = pluginId,
                        displayName = "Cross-window removal test",
                        version = "1.0.0",
                        apiVersion = "1.0",
                        mainClass = "example.Plugin",
                        type = PluginType.PANEL,
                        canUnload = canUnload,
                    ),
                jarPath = "",
                state = PluginState.ERROR,
                loadedAt = 0L,
                enabled = false,
            )

        manager.javaClass
            .getDeclaredMethod("updatePluginState", String::class.java, DynamicPluginInfo::class.java)
            .apply { isAccessible = true }
            .invoke(manager, pluginId, info)
    }
}
