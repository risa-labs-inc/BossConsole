package ai.rever.boss.plugin

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

    private fun installer(
        store: PluginRepository?,
        installed: Set<String> = emptySet(),
        load: suspend (String) -> Result<*> = { Result.success(Unit) },
        onPersist: (String) -> Unit = {},
    ): StoreMissingDependencyInstaller {
        temp.mkdirs()
        return StoreMissingDependencyInstaller(
            repository = { store },
            pluginDir = { temp },
            installedNow = { it in installed },
            load = load,
            persist = { pluginId, _, _ -> onPersist(pluginId) },
        )
    }

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

    /** The name a store download uses, so an update or uninstall recognises the jar. */
    private fun jar() = File(temp, "${PLUGIN_ID.replace('.', '_')}_1.2.3.jar")

    private fun sidecar() = File(PluginSignatureSidecar.pathFor(jar().absolutePath))

    private companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.aigateway"
    }
}
