package ai.rever.boss.plugin.launchpad

import ai.rever.boss.cli.plugin.ValidatorTestFixturePlugin
import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginUnloadAware
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.sandbox.SandboxConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class DevPluginRollbackTest {
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
        DevPluginReloader.clearSessionPreservedPathsForTest()
    }

    private class TestPluginContext(
        override val panelRegistry: PanelRegistry = PanelRegistry(),
        override val tabRegistry: TabRegistry = TabRegistry(),
        override val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : PluginContext

    private fun createManager(
        onSandboxedContext: ((pluginId: String, config: SandboxConfig) -> PluginContext)? = null,
    ): DynamicPluginManager {
        val sandboxManager = PluginSandboxManagerImpl()
        val dummyContext = TestPluginContext()
        val manager =
            DynamicPluginManager(
                dummyContext.panelRegistry,
                dummyContext.tabRegistry,
                sandboxManager,
                createSandboxedContext = { pluginId, config ->
                    onSandboxedContext?.invoke(pluginId, config) ?: dummyContext
                },
            )
        activeManagersToClean.add(manager)
        return manager
    }

    private fun createJar(
        file: File,
        entries: Map<String, ByteArray>,
    ) {
        file.parentFile?.mkdirs()
        JarOutputStream(FileOutputStream(file)).use { jos ->
            entries.forEach { (name, bytes) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(bytes)
            }
        }
    }

    private fun createDevTestJar(
        stagingRoot: File,
        pluginId: String,
        versionDir: String,
        version: String,
        mainClass: String = ValidatorTestFixturePlugin::class.java.name,
        includeMainClassBytecode: Boolean = true,
    ): File {
        val dir = File(stagingRoot, "$pluginId/$versionDir").apply { mkdirs() }
        val jar = File(dir, "$pluginId.jar")
        val manifest =
            """
            {
              "manifestVersion": 1,
              "pluginId": "$pluginId",
              "displayName": "Hot Reload Tool $version",
              "version": "$version",
              "apiVersion": "1.0.0",
              "mainClass": "$mainClass"
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        val entries =
            mutableMapOf<String, ByteArray>(
                "META-INF/boss-plugin/plugin.json" to manifest,
            )
        if (includeMainClassBytecode) {
            val classEntryPath = mainClass.replace('.', '/') + ".class"
            val classBytes =
                ValidatorTestFixturePlugin::class.java.classLoader
                    .getResourceAsStream(classEntryPath)!!
                    .readBytes()
            entries[classEntryPath] = classBytes
        }
        createJar(jar, entries)
        return jar
    }

    private fun createStoreTestJar(
        storeJar: File,
        pluginId: String,
        version: String = "1.0.0",
    ) {
        val classEntryPath = ValidatorTestFixturePlugin::class.java.name.replace('.', '/') + ".class"
        val classBytes =
            ValidatorTestFixturePlugin::class.java.classLoader
                .getResourceAsStream(classEntryPath)!!
                .readBytes()
        val storeManifest =
            """
            {
              "manifestVersion": 1,
              "pluginId": "$pluginId",
              "displayName": "Store Build $version",
              "version": "$version",
              "apiVersion": "1.0.0",
              "mainClass": "${ValidatorTestFixturePlugin::class.java.name}"
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        createJar(
            storeJar,
            mapOf(
                "META-INF/boss-plugin/plugin.json" to storeManifest,
                classEntryPath to classBytes,
            ),
        )
    }

    private fun assertRestoredToV1(
        manager: DynamicPluginManager,
        pluginId: String,
        expectedJar: File,
    ) {
        val restored = manager.getPluginInfo(pluginId)
        assertNotNull(restored, "Manager must have a restored plugin after rollback")
        assertEquals(
            expectedJar.canonicalPath,
            File(restored.jarPath).canonicalPath,
            "Manager must have restored to v1.jar",
        )
        assertEquals(PluginState.LOADED, restored.state, "Restored plugin must be LOADED")
        assertTrue(restored.enabled, "Restored plugin must be enabled")
        assertEquals("1.0.0", restored.manifest.version, "Must be on v1 (1.0.0)")
    }

    private fun assertRestoredToV1Disabled(
        manager: DynamicPluginManager,
        pluginId: String,
        expectedJar: File,
    ) {
        val restored = manager.getPluginInfo(pluginId)
        assertNotNull(restored, "Manager must have a restored plugin after rollback")
        assertEquals(
            expectedJar.canonicalPath,
            File(restored.jarPath).canonicalPath,
            "Manager must have restored to v1.jar",
        )
        assertEquals(PluginState.DISABLED, restored.state, "Restored plugin must be DISABLED")
        assertFalse(restored.enabled, "Restored plugin must be disabled")
        assertEquals("1.0.0", restored.manifest.version, "Must be on v1 (1.0.0)")
    }

    @Test
    fun `reload rejects a staged jar declaring another plugin identity`() =
        runBlocking {
            createManager()
            val root = DevPluginArtifacts.stagingRoot()
            val jar = createDevTestJar(root, "com.example.other", "v1000", "1.0.0")
            val target = File(root, "com.example.expected/v1000/tool.jar")
            target.parentFile.mkdirs()
            jar.copyTo(target)
            assertTrue(DevPluginReloader.reload("com.example.expected", root).isFailure)
        }

    @Test
    fun `first link failure uninstalls partially installed plugin across managers to restore clean initial state`() =
        runBlocking {
            val pluginId = "com.example.first.link"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var manager2FailLink = true
            val manager2 =
                createManager { _, _ ->
                    if (manager2FailLink) {
                        manager2FailLink = false
                        error("Simulated link failure in manager 2")
                    }
                    dummyContext
                }

            assertNull(manager1.getPluginInfo(pluginId))
            assertNull(manager2.getPluginInfo(pluginId))

            // Drive through public DevPluginReloader.reload
            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when second manager fails install")

            assertNull(
                manager1.getPluginInfo(pluginId),
                "Manager 1 must have uninstalled the newly linked plugin after rollback",
            )
            assertNull(
                manager2.getPluginInfo(pluginId),
                "Manager 2 must remain in clean state without plugin",
            )
        }

    @Test
    fun `hot reload failure in second manager restores prior jar in first manager`() =
        runBlocking {
            val pluginId = "com.example.hotreload"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var manager2FailV2Install = false
            val manager2 =
                createManager { _, _ ->
                    if (manager2FailV2Install) {
                        manager2FailV2Install = false
                        error("Simulated v2 install failure on manager 2")
                    }
                    dummyContext
                }

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must load v1.jar successfully")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res2.isSuccess, "Manager 2 must load v1.jar successfully")

            assertEquals(
                v1Jar.canonicalPath,
                File(manager1.getPluginInfo(pluginId)!!.jarPath).canonicalPath,
            )
            assertEquals(
                v1Jar.canonicalPath,
                File(manager2.getPluginInfo(pluginId)!!.jarPath).canonicalPath,
            )

            // Stage v2.jar and arm manager 2 to fail v2 install
            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            manager2FailV2Install = true

            // Drive through public DevPluginReloader.reload
            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 2 fails v2 install")

            assertRestoredToV1(manager1, pluginId, v1Jar)
            assertRestoredToV1(manager2, pluginId, v1Jar)
        }

    @Test
    fun `hot reload failure in second manager restores disabled prior jar in first manager with wasEnabled false`() =
        runBlocking {
            val pluginId = "com.example.disabled.rollback"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var manager2FailV2Install = false
            val manager2 =
                createManager { _, _ ->
                    if (manager2FailV2Install) {
                        manager2FailV2Install = false
                        error("Simulated v2 install failure on manager 2")
                    }
                    dummyContext
                }

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res1.isSuccess, "Manager 1 must load v1.jar successfully")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res2.isSuccess, "Manager 2 must load v1.jar successfully")

            assertEquals(PluginState.DISABLED, manager1.getPluginInfo(pluginId)!!.state)
            assertFalse(manager1.getPluginInfo(pluginId)!!.enabled)

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            manager2FailV2Install = true

            // Drive through public DevPluginReloader.reload
            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 2 fails v2 install")

            assertRestoredToV1Disabled(manager1, pluginId, v1Jar)
            assertRestoredToV1Disabled(manager2, pluginId, v1Jar)
        }

    @Test
    fun `reload with late refusal leaves refusing manager untouched`() =
        runBlocking {
            val pluginId = "com.example.laterefusal"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val manager1 = createManager()
            val manager2 = createManager()

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res2.isSuccess, "Manager 2 must install v1.jar")

            val initialManager2Info = manager2.getPluginInfo(pluginId)
            assertNotNull(initialManager2Info)

            var manager1Unloaded = false
            val unloadAware1 =
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult = CanUnloadResult.Ok

                    override fun prepareForUnload(pluginId: String) {
                        manager1Unloaded = true
                    }
                }
            val unloadAware2 =
                object : PluginUnloadAware {
                    override fun checkCanUnload(pluginId: String): CanUnloadResult =
                        if (!manager1Unloaded) {
                            CanUnloadResult.Ok
                        } else {
                            CanUnloadResult.NotAllowed(listOf("Late refusal from manager 2"))
                        }

                    override fun prepareForUnload(pluginId: String) {
                        // No preparation needed; manager doesn't hold unloadable resources
                    }
                }
            manager1.registerUnloadAware(unloadAware1)
            manager2.registerUnloadAware(unloadAware2)

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")

            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 2 refuses unload")
            assertNotNull(unloadAware1)
            assertNotNull(unloadAware2)
            assertTrue(manager1Unloaded, "Manager 1 must have prepared for unload before Manager 2 refused")

            // Manager 1 had unloaded, so rollback cleanly restores its prior v1.jar
            assertRestoredToV1(manager1, pluginId, v1Jar)

            // Manager 2 refused unload, so it was never modified; rollback leaves it completely untouched
            assertRestoredToV1(manager2, pluginId, v1Jar)
            val currentManager2Info = manager2.getPluginInfo(pluginId)
            assertNotNull(currentManager2Info)
            assertEquals(
                initialManager2Info.loadedAt,
                currentManager2Info.loadedAt,
                "Manager 2 was untouched and must retain its exact original loaded instance",
            )
        }

    @Test
    fun `startup fallback loads store jar when staged dev jar fails`() =
        runBlocking {
            val pluginId = "com.example.startup.fallback"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val storeDir = tempDir.resolve("store-builds").toFile().apply { mkdirs() }
            val storeJar = File(storeDir, "$pluginId.jar")
            createStoreTestJar(storeJar, pluginId, "1.0.0")

            // Create a dev jar that passes archive and manifest validation, but points to a non-existent mainClass
            createDevTestJar(
                stagingRoot = stagingRoot,
                pluginId = pluginId,
                versionDir = "v2000",
                version = "2.0.0",
                mainClass = "non.existent.BrokenPluginMain",
                includeMainClassBytecode = false,
            )

            val manager = createManager()
            val storeEntry =
                PluginPersistence.InstalledPluginEntry(
                    pluginId = pluginId,
                    jarPath = storeJar.absolutePath,
                    enabled = true,
                )

            val results =
                PluginStoreSetup.loadPersistedPluginEntries(
                    dynamicPluginManager = manager,
                    persistedPlugins = listOf(storeEntry),
                    devRoot = stagingRoot,
                )

            val pluginResult = results[pluginId]
            assertNotNull(pluginResult, "Must have a result for plugin")
            assertTrue(
                pluginResult.isSuccess,
                "Plugin must successfully fall back to store build: ${pluginResult.exceptionOrNull()}",
            )

            val loadedInfo = manager.getPluginInfo(pluginId)
            assertNotNull(loadedInfo, "Plugin must be loaded in manager")
            assertEquals(storeJar.canonicalPath, File(loadedInfo.jarPath).canonicalPath, "Must load store jar path")
            assertEquals(PluginState.LOADED, loadedInfo.state, "Fallback plugin must be LOADED")
            assertEquals("1.0.0", loadedInfo.manifest.version, "Must be on store version 1.0.0")
        }

    @Test
    fun `reload of disabled plugin preserves disabled state across managers without throwing`() =
        runBlocking {
            val pluginId = "com.example.disabled.reload"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val manager1 = createManager()
            val manager2 = createManager()

            // 1. Both managers start with v1.jar installed and DISABLED
            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = false)
            assertTrue(res2.isSuccess, "Manager 2 must install v1.jar")

            assertEquals(PluginState.DISABLED, manager1.getPluginInfo(pluginId)?.state)
            assertFalse(manager1.getPluginInfo(pluginId)!!.enabled)

            // 2. Stage v2.jar
            val v2Jar = createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")

            // 3. Perform full DevPluginReloader.reload
            val reloadResult = DevPluginReloader.reload(pluginId, stagingRoot)
            val errorMsg = reloadResult.exceptionOrNull()?.message
            assertTrue(reloadResult.isSuccess, "Reload of disabled plugin must succeed: $errorMsg")

            // 4. Verify both managers updated to v2.jar and remain DISABLED
            val info1 = manager1.getPluginInfo(pluginId)
            assertNotNull(info1)
            assertEquals(File(v2Jar.absolutePath).canonicalPath, File(info1.jarPath).canonicalPath)
            assertEquals(PluginState.DISABLED, info1.state)
            assertFalse(info1.enabled, "Manager 1 must preserve disabled state")
            assertEquals("2.0.0", info1.manifest.version)

            val info2 = manager2.getPluginInfo(pluginId)
            assertNotNull(info2)
            assertEquals(File(v2Jar.absolutePath).canonicalPath, File(info2.jarPath).canonicalPath)
            assertEquals(PluginState.DISABLED, info2.state)
            assertFalse(info2.enabled, "Manager 2 must preserve disabled state")
            assertEquals("2.0.0", info2.manifest.version)
        }

    @Test
    fun `isValidDevJar validates readable zip and manifest content`() {
        val validJar = tempDir.resolve("valid.jar").toFile()
        val manifestJson = """{"id": "test-plugin", "version": "1.0.0"}""".toByteArray()
        createJar(validJar, mapOf("META-INF/boss-plugin/plugin.json" to manifestJson))

        assertTrue(DevPluginArtifacts.isValidDevJar(validJar, "test-plugin"))
        assertTrue(DevPluginArtifacts.isValidDevJar(validJar, null))
        assertFalse(DevPluginArtifacts.isValidDevJar(validJar, "mismatched-id"))

        // Corrupt zip (random bytes)
        val corruptJar = tempDir.resolve("corrupt.jar").toFile()
        corruptJar.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        assertFalse(DevPluginArtifacts.isValidDevJar(corruptJar))

        // Empty file
        val emptyJar = tempDir.resolve("empty.jar").toFile()
        emptyJar.writeBytes(byteArrayOf())
        assertFalse(DevPluginArtifacts.isValidDevJar(emptyJar))

        // Missing manifest entry
        val noManifestJar = tempDir.resolve("no-manifest.jar").toFile()
        createJar(noManifestJar, mapOf("dummy.txt" to "hello".toByteArray()))
        assertFalse(DevPluginArtifacts.isValidDevJar(noManifestJar))

        // Part file
        val partJar = tempDir.resolve("plugin.jar.part").toFile()
        createJar(partJar, mapOf("META-INF/boss-plugin/plugin.json" to manifestJson))
        assertFalse(DevPluginArtifacts.isValidDevJar(partJar))
    }

    @Test
    fun `findAllActiveDevJars with deepValidate filters out invalid dev jars`() {
        val devRoot = tempDir.resolve("staging-test").toFile().apply { mkdirs() }

        // Corrupt dev jar under com.example.corrupt
        val corruptDir = File(devRoot, "com.example.corrupt/v1000").apply { mkdirs() }
        File(corruptDir, "com.example.corrupt.jar").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        // Valid dev jar under com.example.valid
        val validDir = File(devRoot, "com.example.valid/v2000").apply { mkdirs() }
        val manifestBytes = """{"id": "com.example.valid", "version": "1.0.0"}""".toByteArray()
        val validJar = File(validDir, "com.example.valid.jar")
        createJar(validJar, mapOf("META-INF/boss-plugin/plugin.json" to manifestBytes))

        val allDiscovered = DevPluginArtifacts.findAllActiveDevJars(devRoot, deepValidate = true)
        assertEquals(1, allDiscovered.size)
        assertEquals(validJar.absolutePath, allDiscovered.first().absolutePath)
    }

    @Test
    fun `partial install failure on window 1 leaves window 2 untouched`() =
        runBlocking {
            val pluginId = "com.example.partial.install"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            var manager1FailInstall = false
            val manager1 =
                createManager { _, _ ->
                    if (manager1FailInstall) {
                        manager1FailInstall = false
                        error("Simulated v2 install failure on manager 1")
                    }
                    dummyContext
                }
            val manager2 = createManager()

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            assertNull(manager2.getPluginInfo(pluginId), "Manager 2 must have no plugin")

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            manager1FailInstall = true

            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload must fail when manager 1 fails install")

            assertRestoredToV1(manager1, pluginId, v1Jar)
            assertNull(manager2.getPluginInfo(pluginId), "Manager 2 was untouched and remains null")
        }

    @Test
    fun `startup fallback recovers store jar on binary incompatibility`() =
        runBlocking {
            val pluginId = "com.example.binary.compat"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val storeDir = tempDir.resolve("store-compat").toFile().apply { mkdirs() }
            val storeJar = File(storeDir, "$pluginId.jar")
            createStoreTestJar(storeJar, pluginId, "1.0.0")

            createDevTestJar(
                stagingRoot = stagingRoot,
                pluginId = pluginId,
                versionDir = "v2000",
                version = "2.0.0",
                mainClass = IncompatibleBinaryPlugin::class.java.name,
                includeMainClassBytecode = true,
            )

            val manager = createManager()
            val storeEntry =
                PluginPersistence.InstalledPluginEntry(
                    pluginId = pluginId,
                    jarPath = storeJar.absolutePath,
                    enabled = true,
                )

            val results =
                PluginStoreSetup.loadPersistedPluginEntries(
                    dynamicPluginManager = manager,
                    persistedPlugins = listOf(storeEntry),
                    devRoot = stagingRoot,
                )

            val pluginResult = results[pluginId]
            assertNotNull(pluginResult, "Must have a result for plugin")
            assertTrue(
                pluginResult.isSuccess,
                "Plugin must fall back to store build on binary incompatibility: ${pluginResult.exceptionOrNull()}",
            )

            val loadedInfo = manager.getPluginInfo(pluginId)
            assertNotNull(loadedInfo, "Plugin must be loaded in manager")
            assertEquals(storeJar.canonicalPath, File(loadedInfo.jarPath).canonicalPath)
            assertEquals(PluginState.LOADED, loadedInfo.state)
            assertEquals("1.0.0", loadedInfo.manifest.version)
        }

    @Test
    fun `persisted startup keeps the installed build for a protected prefix id`() {
        val pluginId = "ai.rever.boss.system.fixture"
        val stagingRoot = DevPluginArtifacts.stagingRoot()
        createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
        val storeJar = tempDir.resolve("protected-store.jar").toFile()
        createStoreTestJar(storeJar, pluginId, "1.0.0")
        val entry = PluginPersistence.InstalledPluginEntry(pluginId, storeJar.absolutePath, enabled = true)

        assertEquals(storeJar.absolutePath, PluginStoreSetup.resolvePersistedEntryPath(entry, stagingRoot))
    }

    @Test
    fun `external scan falls back to standard jar on dev failure`() =
        runBlocking {
            val pluginId = "com.example.ext.fallback"
            val pluginDir = tempDir.resolve("ext-plugins").toFile().apply { mkdirs() }
            val standardJar = File(pluginDir, "$pluginId.jar")
            createStoreTestJar(standardJar, pluginId, "1.0.0")

            val devRoot = File(pluginDir, "dev").apply { mkdirs() }
            DevPluginArtifacts.stagingRootOverride = devRoot
            try {
                val devJar =
                    createDevTestJar(
                        stagingRoot = devRoot,
                        pluginId = pluginId,
                        versionDir = "v2000",
                        version = "2.0.0",
                        mainClass = "non.existent.BrokenClass",
                        includeMainClassBytecode = false,
                    )

                val manager = createManager()
                val defaultPlugin =
                    DefaultPlugin(
                        panelRegistry = PanelRegistry(),
                        tabRegistry = TabRegistry(),
                        windowProjectState = null,
                    )

                defaultPlugin.installSingleExternalPlugin(
                    manager = manager,
                    jarFile = devJar,
                    trackedJarPaths = emptySet(),
                    fallbackStandardJar = standardJar,
                )

                val loaded = manager.getPluginInfo(pluginId)
                assertNotNull(loaded, "Manager must load fallback standard plugin")
                assertEquals(standardJar.canonicalPath, File(loaded.jarPath).canonicalPath)
                assertEquals(PluginState.LOADED, loaded.state)
                assertEquals("1.0.0", loaded.manifest.version)
            } finally {
                DevPluginArtifacts.stagingRootOverride = null
            }
        }

    @Test
    fun `failed restoration in rollback attaches suppressed exception`() =
        runBlocking {
            val pluginId = "com.example.failed.restore"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar =
                createDevTestJar(
                    stagingRoot = stagingRoot,
                    pluginId = pluginId,
                    versionDir = "v1000",
                    version = "1.0.0",
                    mainClass = ControllableFailingPlugin::class.java.name,
                    includeMainClassBytecode = true,
                )

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var failManager2Install = false
            val manager2 =
                createManager { _, _ ->
                    if (failManager2Install) {
                        failManager2Install = false
                        error("Simulated v2 install failure on manager 2")
                    }
                    dummyContext
                }

            System.clearProperty("boss.test.fail_rollback_register")
            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res2.isSuccess, "Manager 2 must install v1.jar")

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            failManager2Install = true
            System.setProperty("boss.test.fail_rollback_register", "true")

            try {
                val result = DevPluginReloader.reload(pluginId, stagingRoot)
                assertTrue(result.isFailure, "Reload must fail")
                val root = result.exceptionOrNull()
                assertNotNull(root, "Root exception must be present")
                assertTrue(
                    root.suppressedExceptions.isNotEmpty(),
                    "Expected recovery failure to be attached as suppressed exception",
                )
                assertTrue(
                    root.suppressedExceptions.any {
                        it.message?.contains("Failed to reinstall prior JAR") == true
                    },
                    "Suppressed exceptions must contain rollback reinstall failure",
                )
            } finally {
                System.clearProperty("boss.test.fail_rollback_register")
            }
        }

    @Test
    fun `binary incompatible restoration is reported as rollback failure`() =
        runBlocking {
            val pluginId = "com.example.failed.restore"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar =
                createDevTestJar(
                    stagingRoot = stagingRoot,
                    pluginId = pluginId,
                    versionDir = "v1000",
                    version = "1.0.0",
                    mainClass = ControllableFailingPlugin::class.java.name,
                    includeMainClassBytecode = true,
                )

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var failManager2Install = false
            val manager2 =
                createManager { _, _ ->
                    if (failManager2Install) {
                        failManager2Install = false
                        error("Simulated v2 install failure on manager 2")
                    }
                    dummyContext
                }

            System.clearProperty("boss.test.fail_rollback_binary")
            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must install v1.jar")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res2.isSuccess, "Manager 2 must install v1.jar")

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            failManager2Install = true
            System.setProperty("boss.test.fail_rollback_binary", "true")

            try {
                val result = DevPluginReloader.reload(pluginId, stagingRoot)
                assertTrue(result.isFailure, "Reload must fail")
                val root = result.exceptionOrNull()
                assertNotNull(root, "Root exception must be present")
                assertTrue(
                    root.suppressedExceptions.isNotEmpty(),
                    "Expected recovery failure to be attached as suppressed exception",
                )
                assertTrue(
                    root.suppressedExceptions.any {
                        it.message?.contains("Failed to reinstall prior JAR") == true
                    },
                    "Suppressed exceptions must contain rollback reinstall failure",
                )
            } finally {
                System.clearProperty("boss.test.fail_rollback_binary")
            }
        }

    @Test
    fun `protected dev candidate is dropped before deduplication`() {
        val pluginId = ai.rever.boss.components.plugin.MicrokernelRuntime.PLUGIN_ID
        val stagingRoot = DevPluginArtifacts.stagingRoot()
        val devJar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

        val deduplicated =
            DefaultPlugin.deduplicateJars(listOf(devJar)) { id ->
                DefaultPlugin.isAuthoritativeSystemPlugin(id)
            }

        assertTrue(
            deduplicated.isEmpty(),
            "Lone dev JAR claiming authoritative protected ID must be dropped before deduplication",
        )
    }

    @Test
    fun `repeated reloads preserve session retained jar paths`() =
        runBlocking {
            val pluginId = "com.example.repeated.retention"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            createManager()

            val jars =
                (1..5).map { v ->
                    val jar = createDevTestJar(stagingRoot, pluginId, "v${v}000", "$v.0.0")
                    val result = DevPluginReloader.reload(pluginId, stagingRoot)
                    assertTrue(result.isSuccess, "Reload v$v must succeed")
                    jar
                }

            jars.forEach { jar ->
                assertTrue(
                    jar.exists(),
                    "Session retained JAR ${jar.name} must not be deleted by repeated reloads",
                )
            }
        }

    @Test
    fun `overlapping reloads on same plugin are serialized by mutex`() =
        runBlocking {
            val pluginId = "com.example.mutex.reload"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val manager = createManager()
            val res1 =
                manager.installPlugin(
                    File(stagingRoot, "$pluginId/v1000/$pluginId.jar").absolutePath,
                    enabled = true,
                )
            assertTrue(res1.isSuccess)

            createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")

            val job1 =
                async(Dispatchers.Default) {
                    DevPluginReloader.reload(pluginId, stagingRoot)
                }
            val job2 =
                async(Dispatchers.Default) {
                    DevPluginReloader.reload(pluginId, stagingRoot)
                }

            val result1 = job1.await()
            val result2 = job2.await()

            assertTrue(result1.isSuccess, "First concurrent reload must succeed")
            assertTrue(result2.isSuccess, "Second concurrent reload must succeed")
            assertEquals("2.0.0", manager.getPluginInfo(pluginId)?.manifest?.version)
        }

    @Test
    fun `failed reload jar is retained across subsequent successful reloads`() =
        runBlocking {
            DevPluginReloader.clearSessionPreservedPathsForTest()
            val pluginId = "com.example.failed.retention"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            val v1Jar = createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val dummyContext = TestPluginContext()
            val manager1 = createManager()
            var manager2FailV2Install = false
            val manager2 =
                createManager { _, _ ->
                    if (manager2FailV2Install) {
                        manager2FailV2Install = false
                        error("Simulated v2 install failure on manager 2")
                    }
                    dummyContext
                }

            val res1 = manager1.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res1.isSuccess, "Manager 1 must load v1.jar successfully")
            val res2 = manager2.installPlugin(v1Jar.absolutePath, enabled = true)
            assertTrue(res2.isSuccess, "Manager 2 must load v1.jar successfully")

            val v2Jar = createDevTestJar(stagingRoot, pluginId, "v2000", "2.0.0")
            manager2FailV2Install = true

            val reloadV2Result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(reloadV2Result.isFailure, "Reload with failing manager 2 must fail")

            assertRestoredToV1(manager1, pluginId, v1Jar)
            assertRestoredToV1(manager2, pluginId, v1Jar)

            val v3Jar = createDevTestJar(stagingRoot, pluginId, "v3000", "3.0.0")
            val resV3 = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(resV3.isSuccess, "Reload v3 must succeed")

            val v4Jar = createDevTestJar(stagingRoot, pluginId, "v4000", "4.0.0")
            val resV4 = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(resV4.isSuccess, "Reload v4 must succeed")

            val v5Jar = createDevTestJar(stagingRoot, pluginId, "v5000", "5.0.0")
            val resV5 = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(resV5.isSuccess, "Reload v5 must succeed")

            assertTrue(v3Jar.exists(), "v3 JAR must exist")
            assertTrue(v4Jar.exists(), "v4 JAR must exist")
            assertTrue(v5Jar.exists(), "v5 JAR must exist")
            assertTrue(
                v2Jar.exists(),
                "Candidate v2 JAR from failed reload must be retained across subsequent successful reloads",
            )
        }

    @Test
    fun `preflight rejects reload of unloaded system plugin`() =
        runBlocking {
            val pluginId = "ai.rever.boss.system.core"
            val stagingRoot = DevPluginArtifacts.stagingRoot()
            createDevTestJar(stagingRoot, pluginId, "v1000", "1.0.0")

            val manager = createManager()
            assertNull(
                manager.getPluginInfo(pluginId),
                "System plugin must not be loaded prior to reload",
            )

            val result = DevPluginReloader.reload(pluginId, stagingRoot)
            assertTrue(result.isFailure, "Reload of unloaded protected system plugin must fail")
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(
                message.contains("protected system plugin"),
                "Exception message must indicate protected system plugin: $message",
            )
        }
}

class IncompatibleBinaryPlugin : ai.rever.boss.plugin.api.Plugin {
    override val pluginId = "com.example.binary.compat"
    override val displayName = "Incompatible Binary Plugin"

    override fun register(context: PluginContext) = throw NoSuchMethodError("simulated binary incompatibility")
}

class ControllableFailingPlugin : ai.rever.boss.plugin.api.Plugin {
    override val pluginId = "com.example.failed.restore"
    override val displayName = "Controllable Failing Plugin"

    override fun register(context: PluginContext) {
        if (System.getProperty("boss.test.fail_rollback_binary") == "true") {
            System.clearProperty("boss.test.fail_rollback_binary")
            throw NoSuchMethodError("Simulated binary incompatible rollback")
        }
        if (System.getProperty("boss.test.fail_rollback_register") == "true") {
            System.clearProperty("boss.test.fail_rollback_register")
            error("Simulated rollback reinstall failure in register()")
        }
    }
}
