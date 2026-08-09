package ai.rever.boss.plugin

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of installing a missing dependency, without a store or a plugin loader.
 *
 * The three that matter are all about not leaving debris: a download or load that fails must
 * take its jar *and* its signature sidecar with it, because a truncated jar left at the final
 * filename is found and retried by every subsequent launch, and a surviving `.sig` meets fresh
 * bytes on reinstall and hard-fails the load - worse than being unsigned.
 */
class StoreMissingDependencyInstallerTest {
    private val temp = File(System.getProperty("java.io.tmpdir"), "boss-dep-install-${hashCode()}")

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private fun info(
        id: String = PLUGIN_ID,
        version: String = "1.2.3",
    ) = PluginInfo(
        pluginId = id,
        displayName = "AI Gateway",
        version = version,
    )

    /**
     * A store that hands back [plugin] and writes whatever [downloadBytes] says.
     *
     * `downloadBytes = null` models a mid-stream failure: `RemotePluginRepository` writes
     * straight into the target path and only deletes on a hash or signature rejection, so the
     * partial file is genuinely left behind - the fake reproduces that rather than assuming a
     * clean failure.
     */
    private class FakeStore(
        private val plugin: PluginInfo?,
        private val downloadBytes: ByteArray? = ByteArray(8),
        private val writeSidecar: Boolean = true,
    ) : PluginRepository {
        override val id = "store"
        override val name = "Store"
        override val isLocal = false
        override val isAvailable = true

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(listOfNotNull(plugin))

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
            Result.failure(UnsupportedOperationException("unused"))

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = Result.success(plugin)

        override suspend fun getPluginVersions(pluginId: String) = listPlugins()

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
        ): Result<String> {
            val target = File(targetPath)
            target.parentFile?.mkdirs()
            // Always writes something first, like the real one.
            target.writeBytes(downloadBytes ?: ByteArray(3))
            if (writeSidecar) PluginSignatureSidecar.write(targetPath, "signature")
            return if (downloadBytes == null) {
                Result.failure(IllegalStateException("connection reset"))
            } else {
                Result.success(targetPath)
            }
        }

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }

    /**
     * @param installedAfterLoad what `installedNow` reports once a load has succeeded, which is
     *   how the real predicate behaves: the manager registers the plugin the *jar* declares, so
     *   a mismatched store row leaves the requested id still absent.
     */
    private fun installer(
        store: PluginRepository?,
        installed: Set<String> = emptySet(),
        load: suspend (String) -> Result<*> = { Result.success(Unit) },
        onPersist: (String) -> Unit = {},
        installedAfterLoad: Boolean = true,
        declaredId: String? = PLUGIN_ID,
        onPersistVersion: (String) -> Unit = {},
    ): StoreMissingDependencyInstaller {
        temp.mkdirs()
        var loaded = false
        return StoreMissingDependencyInstaller(
            repository = { store },
            pluginDir = { temp },
            installedNow = { id -> id in installed || (loaded && installedAfterLoad) },
            load = { jarPath -> load(jarPath).also { if (it.isSuccess) loaded = true } },
            readManifest = { _ -> declaredId?.let { jarManifest(it) } },
            persist = { pluginId, _, version, _ ->
                onPersist(pluginId)
                onPersistVersion(version)
            },
        )
    }

    /** What the downloaded jar actually declares, which is not always what the row said. */
    private fun jarManifest(pluginId: String) =
        PluginManifest(
            pluginId = pluginId,
            displayName = "AI Gateway",
            version = JAR_VERSION,
            apiVersion = "1.0.0",
            mainClass = "com.example.Main",
        )

    @Test
    fun `a successful install records the plugin`() =
        runTest {
            val recorded = mutableListOf<String>()
            val result = installer(FakeStore(info()), onPersist = { recorded += it }).install(PLUGIN_ID)

            assertTrue(result.isSuccess, "expected success, got ${result.exceptionOrNull()}")
            // Without this entry `setPluginEnabled` silently no-ops, so the dependency cannot
            // be disabled persistently and returns enabled on the next launch.
            assertEquals(listOf(PLUGIN_ID), recorded)
            assertTrue(jar().exists())
        }

    @Test
    fun `a download that fails mid-stream leaves no jar and no sidecar`() =
        runTest {
            val result = installer(FakeStore(info(), downloadBytes = null)).install(PLUGIN_ID)

            assertTrue(result.isFailure)
            assertFalse(jar().exists(), "a truncated jar would be found and retried every launch")
            assertFalse(sidecar().exists(), "an orphaned .sig hard-fails the load on reinstall")
        }

    @Test
    fun `a jar that fails to load is deleted along with its sidecar`() =
        runTest {
            val result =
                installer(
                    FakeStore(info()),
                    load = { Result.failure<Unit>(IllegalStateException("binary incompatible")) },
                ).install(PLUGIN_ID)

            assertTrue(result.isFailure)
            assertFalse(jar().exists())
            assertFalse(sidecar().exists())
        }

    @Test
    fun `a failed load is not recorded as installed`() =
        runTest {
            val recorded = mutableListOf<String>()
            installer(
                FakeStore(info()),
                load = { Result.failure<Unit>(IllegalStateException("nope")) },
                onPersist = { recorded += it },
            ).install(PLUGIN_ID)

            assertTrue(recorded.isEmpty(), "recorded a plugin that never loaded: $recorded")
        }

    @Test
    fun `an already-installed dependency is not downloaded again`() =
        runTest {
            val result = installer(FakeStore(info()), installed = setOf(PLUGIN_ID)).install(PLUGIN_ID)

            assertTrue(result.isSuccess)
            // A prompt can outlive the install that satisfied it; re-downloading over a jar the
            // running manager holds open fails outright on Windows.
            assertFalse(jar().exists(), "downloaded a dependency that was already installed")
        }

    @Test
    fun `no store yields a message about the store, not a transport error`() =
        runTest {
            val result = installer(store = null).install(PLUGIN_ID)

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("plugin store is not available"), "unhelpful message: $message")
        }

    @Test
    fun `a plugin the store does not have names the plugin it could not find`() =
        runTest {
            val result = installer(FakeStore(plugin = null)).install(PLUGIN_ID)

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains(PLUGIN_ID), "unhelpful message: $message")
        }

    @Test
    fun `the failure message names the plugin, never the transport`() =
        runTest {
            val result =
                installer(
                    FakeStore(info()),
                    load = { Result.failure<Unit>(IllegalStateException("classloader constraint violation")) },
                ).install(PLUGIN_ID)

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.startsWith("Downloaded AI Gateway"), "unhelpful message: $message")
        }

    @Test
    fun `an entry whose jar is gone is not treated as installed`() =
        runTest {
            // A binary-incompatible load registers a DISABLED entry and this installer deletes
            // the jar it rejected. If "installed" meant only "has an entry", Retry would close
            // the dialog reporting success with nothing installed - so the delegate's check is
            // an entry whose jar still exists, and this pins the installer half: given a store
            // and a working load, a retry really does install.
            val installer = installer(FakeStore(info()))

            assertTrue(installer.install(PLUGIN_ID).isSuccess)
            assertTrue(jar().exists())
        }

    @Test
    fun `a retry after a failed load installs rather than reporting success`() =
        runTest {
            var attempt = 0
            val result =
                installer(
                    FakeStore(info()),
                    load = {
                        attempt++
                        if (attempt == 1) {
                            Result.failure<Unit>(IllegalStateException("incompatible"))
                        } else {
                            Result.success(Unit)
                        }
                    },
                ).let { installer ->
                    assertTrue(installer.install(PLUGIN_ID).isFailure)
                    installer.install(PLUGIN_ID)
                }

            assertTrue(result.isSuccess, "retry did not install: ${result.exceptionOrNull()}")
            assertEquals(2, attempt, "the retry short-circuited instead of loading")
            assertTrue(jar().exists())
        }

    @Test
    fun `displayNameFor returns the store name, and null when there is none`() =
        runTest {
            assertEquals("AI Gateway", installer(FakeStore(info())).displayNameFor(PLUGIN_ID))
            assertNull(installer(FakeStore(plugin = null)).displayNameFor(PLUGIN_ID))
            assertNull(installer(store = null).displayNameFor(PLUGIN_ID))
        }

    @Test
    fun `displayNameFor treats a blank name as no name`() =
        runTest {
            // The dialog falls back to the plugin id, which is poor but readable; a blank name
            // would render the sentence as "Flow needs , which is not installed".
            val blank = installer(FakeStore(info().copy(displayName = "   ")))

            assertNull(blank.displayNameFor(PLUGIN_ID))
        }

    @Test
    fun `displayNameFor survives a store that throws`() =
        runTest {
            // Read from `produceState` during composition, so a throw here would take the
            // dialog down instead of leaving the id on screen.
            assertNull(installer(ThrowingStore()).displayNameFor(PLUGIN_ID))
        }

    @Test
    fun `a version that would escape the plugin directory is sanitised`() =
        runTest {
            installer(FakeStore(info(version = "../../evil"))).install(PLUGIN_ID)

            val escaped = File(temp.parentFile, "evil.jar")
            assertFalse(escaped.exists(), "wrote outside the plugins directory")
            assertEquals(1, temp.listFiles().orEmpty().count { it.name.endsWith(".jar") })
        }

    @Test
    fun `a jar that loads as a different plugin is rejected, not reported as installed`() =
        runTest {
            // A store row for X can point at a jar whose manifest declares Y. `load` returning
            // success is not evidence that X arrived, and reporting success here would close the
            // dialog with the dependency still absent.
            val recorded = mutableListOf<String>()
            val result =
                installer(
                    FakeStore(info()),
                    installed = emptySet(),
                    load = { Result.success(Unit) },
                    onPersist = { recorded += it },
                    // Still absent after a "successful" load: the jar was some other plugin.
                    installedAfterLoad = false,
                ).install(PLUGIN_ID)

            assertTrue(result.isFailure, "reported success for a plugin that never arrived")
            assertTrue(recorded.isEmpty(), "recorded a plugin that never arrived: $recorded")
            assertFalse(jar().exists(), "left a jar that is not the plugin it claims to be")
        }

    @Test
    fun `a jar declaring a different plugin is refused before it is loaded`() =
        runTest {
            var loads = 0
            val result =
                installer(
                    FakeStore(info()),
                    load = {
                        loads++
                        Result.success(Unit)
                    },
                    declaredId = "com.example.something.else",
                ).install(PLUGIN_ID)

            assertTrue(result.isFailure)
            // Before, not after: `installPlugin` inspects the incoming manifest, so loading
            // first means a jar that is really a newer api plugin has already started a full
            // api hot swap, and a jar declaring another installed plugin has already been
            // registered against a path about to be deleted.
            assertEquals(0, loads, "loaded a jar that declares the wrong plugin")
            assertFalse(jar().exists())
        }

    @Test
    fun `a jar that declares a system component is refused even if the row asked for it`() =
        runTest {
            var loads = 0
            val result =
                installer(
                    FakeStore(info()),
                    load = {
                        loads++
                        Result.success(Unit)
                    },
                    declaredId = "ai.rever.boss.plugin.api",
                ).install(PLUGIN_ID)

            // The id filter in `missingFor` covers the id a manifest *names*; nothing binds a
            // store row to the id its jar declares, so the bytes have to be checked too - an
            // api jar reaching `installPlugin` starts an unload-all / swap / reload-all.
            assertTrue(result.isFailure)
            assertEquals(0, loads, "started an api hot swap from a dependency prompt")
        }

    @Test
    fun `an unreadable manifest is refused rather than loaded hopefully`() =
        runTest {
            var loads = 0
            val result =
                installer(
                    FakeStore(info()),
                    load = {
                        loads++
                        Result.success(Unit)
                    },
                    declaredId = null,
                ).install(PLUGIN_ID)

            assertTrue(result.isFailure)
            assertEquals(0, loads)
        }

    @Test
    fun `the recorded version comes from the jar, not the store row`() =
        runTest {
            val versions = mutableListOf<String>()
            installer(FakeStore(info(version = "1.2.3")), onPersistVersion = { versions += it })
                .install(PLUGIN_ID)

            // Update checking compares against this value, so a row whose version disagrees
            // with its jar would make every future comparison wrong.
            assertEquals(listOf(JAR_VERSION), versions)
        }

    @Test
    fun `a failed download does not delete a jar that was already there`() =
        runTest {
            temp.mkdirs()
            val existing = jar()
            existing.writeText("a plugin the manager never registered")

            installer(FakeStore(info(), downloadBytes = null)).install(PLUGIN_ID)

            // A plugin can be on disk without an entry - a load that failed transiently at
            // startup - and a failed download must not take the user's file with it.
            assertTrue(existing.exists(), "deleted a pre-existing jar this install did not create")
        }

    /** A store whose lookup fails outright, as an offline or rate-limited one does. */

    /** A store whose lookup fails outright, as an offline or rate-limited one does. */
    private class ThrowingStore : PluginRepository {
        override val id = "store"
        override val name = "Store"
        override val isLocal = false
        override val isAvailable = false

        override suspend fun listPlugins(): Result<List<PluginInfo>> = error("offline")

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> = error("offline")

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = error("offline")

        override suspend fun getPluginVersions(pluginId: String) = listPlugins()

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
        ): Result<String> = error("offline")

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = error("offline")
    }

    /** Where a download lands: dots in the id become underscores, the version is sanitised. */
    private fun jar() = File(temp, "${PLUGIN_ID.replace('.', '_')}_1.2.3.jar")

    private fun sidecar() = File(PluginSignatureSidecar.pathFor(jar().absolutePath))

    private companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.aigateway"

        /** Deliberately different from the store row's 1.2.3, so tests can tell them apart. */
        const val JAR_VERSION = "1.2.4"
    }
}
