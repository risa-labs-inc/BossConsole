package ai.rever.boss.components.plugin

import ai.rever.boss.cli.plugin.ValidatorTestFixturePlugin
import ai.rever.boss.components.plugin.remote.PluginProcessMonitor
import ai.rever.boss.components.plugin.remote.PluginProcessMonitorBackend
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.services.auth.AuthStateManager
import ai.rever.boss.services.supabase.models.RoleClaims
import ai.rever.boss.services.supabase.models.UserInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OopPluginDisableLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `disabling an OOP plugin disarms and terminates its child`() =
        runBlocking {
            val pluginId = "com.example.disable-oop"
            val spawner = RecordingSpawner()
            val sandbox = PluginSandboxManagerImpl()
            sandbox.createSandbox(pluginId)
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                )

            try {
                val jar = createPluginJar(pluginId)
                assertTrue(manager.installPlugin(jar.absolutePath).isSuccess)
                withTimeout(5_000) { spawner.firstSpawn.await() }

                assertTrue(manager.disablePlugin(pluginId).isSuccess)
                assertEquals(
                    1,
                    spawner.terminatedIds.count { it == pluginId },
                    "Disable must stop the OOP child and its restart monitor",
                )

                assertTrue(manager.enablePlugin(pluginId).isSuccess)
                val restarted =
                    withTimeoutOrNull(2_000) {
                        while (spawner.spawnCount.get() < 2) delay(10)
                        true
                    }
                assertTrue(restarted == true, "Re-enable must start a new OOP child")
                assertEquals(2, spawner.spawnCount.get(), "Re-enable must start exactly one new child")

                assertTrue(manager.enablePlugin(pluginId).isSuccess)
                assertEquals(2, spawner.spawnCount.get(), "A redundant Enable must not spawn another child")

                assertTrue(manager.disablePlugin(pluginId).isSuccess)
                spawner.failNextSpawn = true

                assertTrue(manager.enablePlugin(pluginId).isFailure)
                val failedEnable = manager.getPluginInfo(pluginId)
                assertEquals(PluginState.DISABLED, failedEnable?.state)
                assertEquals(false, failedEnable?.enabled)
                assertTrue(sandbox.isPluginDisabled(pluginId), "Failed OOP re-enable must leave the sandbox disabled")
            } finally {
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `disable waits for a starting OOP child before returning`() =
        runBlocking {
            val pluginId = "com.example.disable-during-start"
            val spawner = RecordingSpawner(holdInitialSpawn = true)
            val sandbox = PluginSandboxManagerImpl()
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                )

            try {
                assertTrue(manager.installPlugin(createPluginJar(pluginId).absolutePath).isSuccess)
                withTimeout(5_000) { spawner.firstSpawn.await() }

                val disable =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        manager.disablePlugin(pluginId)
                    }

                assertFalse(
                    disable.isCompleted,
                    "Disable must wait for the starting child before it returns",
                )

                spawner.releaseInitialSpawn.complete(Unit)
                assertTrue(withTimeout(5_000) { disable.await() }.isSuccess)
                assertFalse(spawner.childAlive, "No child may survive Disable")
            } finally {
                spawner.releaseInitialSpawn.complete(Unit)
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `losing admin access stops OOP child and restoring access starts one child`() =
        runBlocking {
            val pluginId = "com.example.admin-oop"
            val admin =
                UserInfo(
                    id = "oop-access-test-user",
                    email = "oop-access@example.test",
                    createdAt = "2026-01-01",
                    roleClaims = RoleClaims("admin", listOf("admin"), isAdmin = true),
                )
            val regularUser = admin.copy(roleClaims = RoleClaims("user", listOf("user"), isAdmin = false))
            val accessUsers = MutableStateFlow<UserInfo?>(admin)
            val spawner = RecordingSpawner()
            val sandbox = PluginSandboxManagerImpl()
            val manager = createAccessManager(sandbox, spawner, accessUsers)

            try {
                val jar = createPluginJar(pluginId, requiresAdmin = true)
                val manifest = PluginManifestReader.readFromJar(jar.absolutePath)
                withTimeout(5_000) {
                    while (!manager.canAccess(manifest)) delay(10)
                }
                assertTrue(manager.installPlugin(jar.absolutePath).isSuccess)

                val started =
                    withTimeoutOrNull(5_000) {
                        while (manager.getPluginInfo(pluginId)?.manifest?.let(manager::canAccess) != true) delay(10)
                        spawner.firstSpawn.await()
                        true
                    }
                assertTrue(
                    started == true,
                    "Initial child did not start: accessAllowed=${manager.canAccess(manifest)}, " +
                        "state=${manager.getPluginInfo(pluginId)?.state}, " +
                        "enabled=${manager.getPluginInfo(pluginId)?.enabled}, " +
                        "spawnCount=${spawner.spawnCount.get()}",
                )

                accessUsers.value = regularUser
                val hidden =
                    withTimeoutOrNull(5_000) {
                        while (manager.getPluginInfo(pluginId)?.state != PluginState.DISABLED) delay(10)
                        true
                    }
                assertTrue(
                    hidden == true,
                    "Revocation was not reconciled: accessAllowed=${manager.canAccess(manifest)}, " +
                        "state=${manager.getPluginInfo(pluginId)?.state}, " +
                        "spawnCount=${spawner.spawnCount.get()}, terminations=${spawner.terminatedIds}",
                )
                assertFalse(spawner.childAlive, "Losing access must stop the OOP child")
                assertEquals(1, spawner.terminatedIds.count { it == pluginId })

                accessUsers.value = admin
                withTimeout(5_000) {
                    while (manager.getPluginInfo(pluginId)?.state != PluginState.LOADED) delay(10)
                    while (spawner.spawnCount.get() < 2) delay(10)
                }
                assertEquals(2, spawner.spawnCount.get(), "Restoring access must start exactly one new child")
            } finally {
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `admin-only OOP plugin stays stopped until access is granted`() =
        runBlocking {
            val pluginId = "com.example.admin-only-oop"
            val regularUser =
                UserInfo(
                    id = "oop-install-test-user",
                    email = "oop-install@example.test",
                    createdAt = "2026-01-01",
                    roleClaims = RoleClaims("user", listOf("user"), isAdmin = false),
                )
            val accessUsers = MutableStateFlow<UserInfo?>(regularUser)
            val spawner = RecordingSpawner()
            val sandbox = PluginSandboxManagerImpl()
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }

            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                    accessUsers = accessUsers,
                )

            try {
                val jar = createPluginJar(pluginId, requiresAdmin = true)
                assertTrue(manager.installPlugin(jar.absolutePath).isSuccess)

                assertEquals(
                    PluginState.DISABLED,
                    manager.getPluginInfo(pluginId)?.state,
                    "An inaccessible OOP plugin must start hidden",
                )
                assertEquals(0, spawner.spawnCount.get(), "An inaccessible OOP plugin must not start a child")

                accessUsers.value =
                    regularUser.copy(
                        roleClaims = RoleClaims("admin", listOf("admin"), isAdmin = true),
                    )
                withTimeout(5_000) {
                    while (manager.getPluginInfo(pluginId)?.state != PluginState.LOADED) delay(10)
                    while (spawner.spawnCount.get() != 1) delay(10)
                }
                assertTrue(spawner.childAlive)
            } finally {
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `starting one OOP child does not block installing another plugin`() =
        runBlocking {
            val spawner = RecordingSpawner(holdInitialSpawn = true)
            val sandbox = PluginSandboxManagerImpl()
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                )

            try {
                val secondJar = tempDir.resolve("unrelated-oop.jar")
                Files.copy(createPluginJar("com.example.unrelated-oop").toPath(), secondJar)
                val firstJar = createPluginJar("com.example.starting-oop")

                assertTrue(manager.installPlugin(firstJar.absolutePath).isSuccess)
                withTimeout(5_000) { spawner.firstSpawn.await() }

                assertTrue(
                    withTimeout(5_000) { manager.installPlugin(secondJar.toString()) }.isSuccess,
                    "A starting child must not block installation of an unrelated plugin",
                )
            } finally {
                spawner.releaseInitialSpawn.complete(Unit)
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `non-admin cannot enable an admin-only OOP plugin`() =
        runBlocking {
            val pluginId = "com.example.denied-enable-oop"
            val regularUser =
                UserInfo(
                    id = "oop-denied-enable-test-user",
                    email = "oop-denied@example.test",
                    createdAt = "2026-01-01",
                    roleClaims = RoleClaims("user", listOf("user"), isAdmin = false),
                )
            val accessUsers = MutableStateFlow<UserInfo?>(regularUser)
            val spawner = RecordingSpawner()
            val sandbox = PluginSandboxManagerImpl()
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }

            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                    accessUsers = accessUsers,
                )

            try {
                val jar = createPluginJar(pluginId, requiresAdmin = true)
                assertTrue(manager.installPlugin(jar.absolutePath, enabled = false).isSuccess)

                assertTrue(manager.enablePlugin(pluginId).isFailure)
                assertEquals(0, spawner.spawnCount.get())
                assertEquals(PluginState.DISABLED, manager.getPluginInfo(pluginId)?.state)
            } finally {
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `failed OOP re-registration disarms child before disabling plugin`() =
        runBlocking {
            val pluginId = "com.example.oop-reregister-failure"
            val spawner = RecordingSpawner()
            val sandbox = PluginSandboxManagerImpl()
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                )

            try {
                val jar = createPluginJar(pluginId, pluginClass = FailSecondRegistrationPlugin::class.java)
                assertTrue(manager.installPlugin(jar.absolutePath).isSuccess)
                withTimeout(5_000) {
                    while (!spawner.childAlive) delay(10)
                }

                assertTrue(manager.reregisterAfterRestart(pluginId).isFailure)
                val info = manager.getPluginInfo(pluginId)
                assertEquals(PluginState.DISABLED, info?.state)
                assertEquals(false, info?.enabled)
                assertFalse(spawner.childAlive, "Failed re-registration must stop the OOP child")
                assertEquals(1, spawner.terminatedIds.count { it == pluginId })
                assertEquals(1, spawner.spawnCount.get(), "Failure must not spawn a replacement")
            } finally {
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `disable stops OOP supervision and re-enable restores it`() =
        runBlocking {
            val pluginId = "com.example.oop-supervision"
            val spawner = RecordingSpawner()
            val sandbox = PluginSandboxManagerImpl()
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                )

            try {
                assertTrue(manager.installPlugin(createPluginJar(pluginId).absolutePath).isSuccess)
                withTimeout(5_000) {
                    while (!spawner.childAlive || !spawner.isMonitored(pluginId)) delay(10)
                }

                assertTrue(manager.disablePlugin(pluginId).isSuccess)
                assertFalse(spawner.isMonitored(pluginId))
                spawner.checkHealthNow()
                assertEquals(1, spawner.spawnCount.get(), "A health check after Disable must not respawn the child")
                assertFalse(spawner.childAlive)

                assertTrue(manager.enablePlugin(pluginId).isSuccess)
                assertEquals(2, spawner.spawnCount.get(), "Re-enable must start exactly one child")
                assertTrue(spawner.isMonitored(pluginId), "Re-enable must restore monitoring")

                spawner.childAlive = false
                spawner.checkHealthNow()
                assertEquals(3, spawner.spawnCount.get(), "The restored monitor must restart a crashed child")
                assertTrue(spawner.childAlive)
            } finally {
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `disable during parked OOP restart leaves no child`() =
        runBlocking {
            val pluginId = "com.example.oop-parked-restart"
            val spawner = RecordingSpawner(holdRestart = true)
            val sandbox = PluginSandboxManagerImpl()
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                )

            try {
                assertTrue(manager.installPlugin(createPluginJar(pluginId).absolutePath).isSuccess)
                withTimeout(5_000) {
                    while (!spawner.childAlive || !spawner.isMonitored(pluginId)) delay(10)
                }

                spawner.childAlive = false // Simulate a crash.
                val healthCheck = async { spawner.checkHealthNow() }
                withTimeout(5_000) { spawner.restartEntered.await() }

                assertTrue(manager.disablePlugin(pluginId).isSuccess)
                assertFalse(spawner.isMonitored(pluginId), "Disable must disarm the admitted restart")

                spawner.releaseRestart.complete(Unit)
                withTimeout(5_000) { healthCheck.await() }

                assertEquals(1, spawner.spawnCount.get(), "The parked restart must not start a replacement")
                assertFalse(spawner.childAlive)
            } finally {
                spawner.releaseRestart.complete(Unit)
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    @Test
    fun `a failed disable after the OOP child stopped records the plugin disabled`() =
        runBlocking {
            val pluginId = "com.example.oop-failed-disable"
            val spawner = RecordingSpawner()
            val sandbox = PluginSandboxManagerImpl()
            sandbox.createSandbox(pluginId)
            val context =
                object : PluginContext {
                    override val panelRegistry = PanelRegistry()
                    override val tabRegistry = TabRegistry()
                    override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
            val manager =
                DynamicPluginManager(
                    context.panelRegistry,
                    context.tabRegistry,
                    sandbox,
                    createSandboxedContext = { _, _ -> context },
                    outOfProcessSpawner = spawner,
                )

            try {
                assertTrue(manager.installPlugin(createPluginJar(pluginId).absolutePath).isSuccess)
                withTimeout(5_000) {
                    while (!spawner.childAlive) delay(10)
                }

                spawner.failNextTerminate = true
                assertTrue(manager.disablePlugin(pluginId).isFailure)

                // The child is stopped and unmonitored, so the recorded state must not
                // still claim the plugin is running: nothing re-arms supervision for a
                // plugin the manager believes is LOADED.
                val info = manager.getPluginInfo(pluginId)
                assertEquals(PluginState.DISABLED, info?.state)
                assertEquals(false, info?.enabled)
                assertFalse(spawner.childAlive)
                assertFalse(spawner.isMonitored(pluginId), "A failed disable must not leave supervision half-armed")

                assertTrue(manager.disablePlugin(pluginId).isSuccess)
                assertTrue(sandbox.isPluginDisabled(pluginId), "A retry must complete the disabled sandbox")
            } finally {
                manager.disposeWindow()
                sandbox.dispose()
            }
        }

    private fun createAccessManager(
        sandbox: PluginSandboxManagerImpl,
        spawner: RecordingSpawner,
        accessUsers: MutableStateFlow<UserInfo?>,
    ): DynamicPluginManager {
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
            outOfProcessSpawner = spawner,
            accessUsers = accessUsers,
        )
    }

    private class RecordingSpawner(
        private val holdInitialSpawn: Boolean = false,
        private val holdRestart: Boolean = false,
    ) : OutOfProcessPluginSpawner,
        PluginProcessMonitorBackend {
        private val monitor = PluginProcessMonitor(this)

        val restartEntered = CompletableDeferred<Unit>()
        val releaseRestart = CompletableDeferred<Unit>()
        val firstSpawn = CompletableDeferred<Unit>()
        val releaseInitialSpawn = CompletableDeferred<Unit>()

        // Written from the manager's coroutines and read from the test thread:
        // CopyOnWriteArrayList / @Volatile for the same cross-thread reason spawnCount
        // and childAlive are already guarded.
        val terminatedIds = CopyOnWriteArrayList<String>()
        val spawnCount = AtomicInteger()

        @Volatile
        var failNextSpawn = false

        @Volatile
        var failNextTerminate = false

        @Volatile
        var childAlive = false

        override suspend fun spawn(
            manifest: PluginManifest,
            jarPath: String,
        ): Result<Unit> {
            val attempt = spawnCount.incrementAndGet()
            firstSpawn.complete(Unit)

            if (attempt == 1 && holdInitialSpawn) {
                releaseInitialSpawn.await()
            }
            if (failNextSpawn) {
                failNextSpawn = false
                return Result.failure(IllegalStateException("Simulated child startup failure"))
            }

            childAlive = true
            monitor.monitor(
                pluginId = manifest.pluginId,
                displayName = manifest.displayName,
                restartAction = {
                    if (holdRestart) {
                        restartEntered.complete(Unit)
                        releaseRestart.await()
                    }
                    if (monitor.isMonitored(manifest.pluginId)) {
                        spawn(manifest, jarPath)
                    } else {
                        Result.failure(IllegalStateException("Restart was disarmed"))
                    }
                },
            )
            return Result.success(Unit)
        }

        override suspend fun terminate(pluginId: String): Result<Unit> {
            // The real spawner unmonitors and kills the child at entry, so even a
            // teardown that later reports failure has already stopped supervision -
            // the failed-disable test relies on that shape.
            monitor.unmonitor(pluginId)
            terminatedIds += pluginId
            childAlive = false
            if (failNextTerminate) {
                failNextTerminate = false
                return Result.failure(IllegalStateException("Simulated teardown failure"))
            }
            return Result.success(Unit)
        }

        override fun getManagedProcess(pluginId: String): ManagedProcess? = null

        override fun isAlive(pluginId: String): Boolean = childAlive

        override fun isConnected(pluginId: String): Boolean = childAlive

        override fun isRestartAllowed(): Boolean = true

        suspend fun checkHealthNow() = monitor.checkHealthNow()

        fun isMonitored(pluginId: String): Boolean = monitor.isMonitored(pluginId)

        override fun dispose() = monitor.dispose()
    }

    private fun createPluginJar(
        pluginId: String,
        requiresAdmin: Boolean = false,
        pluginClass: Class<out Plugin> = ValidatorTestFixturePlugin::class.java,
    ): File {
        val classEntry = pluginClass.name.replace('.', '/') + ".class"
        val classBytes = pluginClass.classLoader.getResourceAsStream(classEntry)!!.readBytes()
        val manifest =
            """
            {
              "manifestVersion": 1,
              "pluginId": "$pluginId",
              "displayName": "OOP Disable Fixture",
              "version": "1.0.0",
              "apiVersion": "1.0.0",
              "mainClass": "${pluginClass.name}",
              "isolationMode": "out-of-process",
              "requiresAdmin": $requiresAdmin
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)

        val jar = tempDir.resolve("disable-oop.jar").toFile()
        JarOutputStream(FileOutputStream(jar)).use { output ->
            for (
            (name, bytes) in
            listOf(
                "META-INF/boss-plugin/plugin.json" to manifest,
                classEntry to classBytes,
            )
            ) {
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return jar
    }
}

class FailSecondRegistrationPlugin : Plugin {
    override val pluginId = "com.example.oop-reregister-failure"
    override val displayName = "OOP re-registration failure fixture"
    private var registrationCount = 0

    override fun register(context: PluginContext) {
        registrationCount++
        if (registrationCount > 1) error("Simulated re-registration failure")
    }
}
