package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginDependency
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginDependencyVersionsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `required old dependency refuses both native and split brain activation before register or spawn`(): Unit =
        runBlocking {
            for (isolation in listOf("in-process", "out-of-process")) {
                val fixture = Fixture()
                try {
                    fixture.install("com.example.version.engine", "1.0.172").getOrThrow()
                    val result =
                        fixture.install(
                            "com.example.version.consumer",
                            "1.0.0",
                            requirement = ">=1.1.1",
                            isolation = isolation,
                        )
                    assertTrue(result.isFailure)
                    val message = result.exceptionOrNull()?.message.orEmpty()
                    assertTrue(message.contains("engine >=1.1.1") && message.contains("1.0.172"), message)
                    assertFalse("com.example.version.consumer" in fixture.registered)
                    assertFalse("com.example.version.consumer" in fixture.spawned)
                    assertEquals(
                        "1.0.172",
                        fixture.manager
                            .getPluginInfo("com.example.version.engine")
                            ?.manifest
                            ?.version,
                    )
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun `floor and newer dependencies permit activation`(): Unit =
        runBlocking {
            for (version in listOf("1.1.1", "1.1.2", "2.0.0")) {
                val fixture = Fixture()
                try {
                    fixture.install("com.example.version.engine", version).getOrThrow()
                    fixture.install("com.example.version.consumer", "1.0.0", requirement = ">=1.1.1").getOrThrow()
                    assertEquals(1, fixture.registered.count { it == "com.example.version.consumer" })
                    assertEquals(
                        PluginState.LOADED,
                        fixture.manager.getPluginInfo("com.example.version.consumer")?.state,
                    )
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun `disabled install remains possible but enable rechecks the dependency and recovers after upgrade`(): Unit =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.install("com.example.version.engine", "1.0.172").getOrThrow()
                fixture
                    .install(
                        "com.example.version.consumer",
                        "1.0.0",
                        requirement = ">=1.1.1",
                        enabled = false,
                    ).getOrThrow()
                assertTrue(fixture.manager.enablePlugin("com.example.version.consumer").isFailure)
                assertFalse("com.example.version.consumer" in fixture.registered)
                assertEquals(PluginState.DISABLED, fixture.manager.getPluginInfo("com.example.version.consumer")?.state)
                fixture.manager.uninstallPlugin("com.example.version.engine", force = true).getOrThrow()
                fixture.install("com.example.version.engine", "1.1.1").getOrThrow()
                fixture.manager.enablePlugin("com.example.version.consumer").getOrThrow()
                assertEquals(1, fixture.registered.count { it == "com.example.version.consumer" })
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `installing a dependency later cannot violate a running consumer range`(): Unit =
        runBlocking {
            val fixture = Fixture()
            try {
                // Missing-plugin reporting remains advisory, so exercise the reverse load order too.
                fixture.install("com.example.version.consumer", "1.0.0", requirement = ">=1.1.1 <2.0.0").getOrThrow()
                assertTrue(fixture.install("com.example.version.engine", "1.0.172").isFailure)
                assertTrue(fixture.install("com.example.version.engine", "2.0.0").isFailure)
                assertFalse("com.example.version.engine" in fixture.registered)
                fixture.install("com.example.version.engine", "1.1.1").getOrThrow()
                assertEquals(1, fixture.registered.count { it == "com.example.version.engine" })
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `running consumer prevents incompatible replacement while disabled consumer does not`(): Unit =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.install("com.example.version.engine", "1.1.1").getOrThrow()
                fixture.install("com.example.version.consumer", "1.0.0", requirement = ">=1.1.1").getOrThrow()
                fixture.manager.uninstallPlugin("com.example.version.engine", force = true).getOrThrow()
                assertTrue(fixture.install("com.example.version.engine", "1.0.172").isFailure)
                fixture.manager.disablePlugin("com.example.version.consumer").getOrThrow()
                fixture.install("com.example.version.engine", "1.0.172").getOrThrow()
                assertTrue(fixture.manager.enablePlugin("com.example.version.consumer").isFailure)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `optional dependency does not block activation or replacement`(): Unit =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.install("com.example.version.engine", "1.0.172").getOrThrow()
                fixture
                    .install(
                        "com.example.version.consumer",
                        "1.0.0",
                        requirement = ">=1.1.1",
                        optional = true,
                    ).getOrThrow()
                fixture.manager.uninstallPlugin("com.example.version.engine", force = true).getOrThrow()
                fixture.install("com.example.version.engine", "1.0.0").getOrThrow()
                assertEquals(1, fixture.registered.count { it == "com.example.version.consumer" })
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `invalid required ranges cannot silently become unrestricted`(): Unit =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.install("com.example.version.engine", "1.1.1").getOrThrow()
                val result = fixture.install("com.example.version.consumer", "1.0.0", requirement = ">=typo")
                assertTrue(result.isFailure)
                assertTrue(
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("unsupported dependency version range"),
                )
                assertFalse("com.example.version.consumer" in fixture.registered)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `unknown installed versions cannot silently satisfy a minimum`() {
        val consumer =
            PluginManifest(
                pluginId = "com.example.version.consumer",
                displayName = "Consumer",
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "unused",
                dependencies = listOf(PluginDependency("com.example.version.engine", ">=1.1.1")),
            )
        assertFailsWith<IllegalStateException> {
            PluginDependencyVersions.requireCompatible(consumer, mapOf("com.example.version.engine" to "unknown"))
        }
    }

    private inner class Fixture {
        val registered = CopyOnWriteArrayList<String>()
        val spawned = CopyOnWriteArrayList<String>()
        private val sandbox = PluginSandboxManagerImpl()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val panels = PanelRegistry()
        private val tabs = TabRegistry()
        val manager =
            DynamicPluginManager(
                panels,
                tabs,
                sandbox,
                createSandboxedContext = { id, _ ->
                    object : PluginContext {
                        override val panelRegistry = panels
                        override val tabRegistry = tabs
                        override val pluginScope = scope

                        override fun <T : Any> getPluginAPI(apiClass: Class<T>): T? =
                            if (apiClass == Runnable::class.java) {
                                apiClass.cast(Runnable { registered += id })
                            } else {
                                null
                            }
                    }
                },
                outOfProcessSpawner =
                    object : OutOfProcessPluginSpawner {
                        override suspend fun spawn(
                            manifest: PluginManifest,
                            jarPath: String,
                        ): Result<Unit> {
                            spawned += manifest.pluginId
                            return Result.success(Unit)
                        }

                        override suspend fun terminate(pluginId: String): Result<Unit> = Result.success(Unit)
                    },
            )

        suspend fun install(
            id: String,
            version: String,
            requirement: String? = null,
            optional: Boolean = false,
            enabled: Boolean = true,
            isolation: String = "in-process",
        ): Result<DynamicPluginInfo> {
            val dependency =
                requirement?.let {
                    """[{"pluginId":"com.example.version.engine","version":"$it","optional":$optional}]"""
                } ?: "[]"
            val pluginClass = DependencyVersionFixturePlugin::class.java
            val entry = pluginClass.name.replace('.', '/') + ".class"
            val jar = Files.createTempFile(tempDir, "$id-", ".jar")
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
                output.write(
                    """
                    {
                      "manifestVersion":1, "pluginId":"$id", "displayName":"$id", "version":"$version",
                      "apiVersion":"1.0.0", "mainClass":"${pluginClass.name}",
                      "isolationMode":"$isolation", "dependencies":$dependency
                    }
                    """.trimIndent()
                        .toByteArray(),
                )
                output.closeEntry()
                output.putNextEntry(JarEntry(entry))
                output.write(pluginClass.classLoader.getResourceAsStream(entry)!!.use { it.readBytes() })
                output.closeEntry()
            }
            return manager.installPlugin(jar.toString(), enabled = enabled)
        }

        suspend fun close() {
            manager.disposeWindow()
            sandbox.dispose()
            scope.cancel()
        }
    }
}

class DependencyVersionFixturePlugin : Plugin {
    override val pluginId = "dependency.version.fixture"
    override val displayName = "Dependency version fixture"

    override fun register(context: PluginContext) {
        checkNotNull(context.getPluginAPI(Runnable::class.java)).run()
    }
}
