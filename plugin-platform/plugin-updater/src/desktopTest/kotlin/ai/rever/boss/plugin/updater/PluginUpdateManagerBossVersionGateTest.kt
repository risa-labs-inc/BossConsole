package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [PluginUpdateManager.checkForUpdates] refuses to OFFER a newer
 * version whose `minBossVersion` exceeds the running host version, reporting it
 * as an [IncompatibleNotice] instead. Without this gate the update replaces the
 * working jar and only then gets rejected at load, leaving the plugin broken —
 * how Toolbox 1.8.4 bricked itself on BOSS 9.2.25 (its manifest understated
 * minBossVersion as 9.2.20, so this gate protects the NEXT such release, whose
 * manifest declares the true requirement).
 *
 * The gate fails OPEN on blank/unparseable versions: the loader remains the
 * backstop; the updater must never block every update because version info is
 * missing (dev builds, pre-migration store rows).
 */
class PluginUpdateManagerBossVersionGateTest {
    private val pluginId = "com.example.demo"

    private fun candidate(
        version: String,
        minBoss: String,
    ) = PluginInfo(
        pluginId = pluginId,
        displayName = "Demo",
        version = version,
        minBossVersion = minBoss,
    )

    private fun manager(
        latest: PluginInfo,
        hostBossVersion: String,
    ): PluginUpdateManager {
        val repos = PluginRepositoryManager().apply { addRepository(FakeRepository(latest)) }
        return PluginUpdateManager(
            repositoryManager = repos,
            hostBossVersion = hostBossVersion,
        )
    }

    @Test
    fun `update requiring newer host is reported, not offered`() =
        runTest {
            val mgr = manager(candidate("1.8.4", minBoss = "9.2.26"), hostBossVersion = "9.2.25")

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.8.3"))

            assertTrue(result.availableUpdates.isEmpty(), "host-incompatible update must not be offered")
            assertEquals(1, result.incompatibleNotices.size)
            val notice = result.incompatibleNotices.first()
            assertEquals(pluginId, notice.pluginId)
            assertEquals("1.8.4", notice.advertisedLatest)
            assertEquals("9.2.26", notice.requiredBossVersion)
            assertEquals("9.2.25", notice.hostBossVersion)
            assertEquals("", notice.requiredIpcVersion, "boss-version notice must not fabricate an IPC reason")
        }

    @Test
    fun `update whose minBossVersion the host satisfies is offered`() =
        runTest {
            val mgr = manager(candidate("1.8.4", minBoss = "9.2.25"), hostBossVersion = "9.2.25")

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.8.3"))

            assertEquals(1, result.availableUpdates.size)
            assertEquals("1.8.4", result.availableUpdates.first().newVersion)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `newer host than required is offered`() =
        runTest {
            val mgr = manager(candidate("1.8.4", minBoss = "9.2.20"), hostBossVersion = "9.2.26")

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.8.3"))

            assertEquals(1, result.availableUpdates.size)
        }

    @Test
    fun `prerelease of the required host version is offered, not gated`() =
        runTest {
            val mgr = manager(candidate("1.8.4", minBoss = "9.2.26"), hostBossVersion = "9.2.26-alpha.1")

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.8.3"))

            assertEquals(1, result.availableUpdates.size)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `blank minBossVersion fails open`() =
        runTest {
            val mgr = manager(candidate("1.8.4", minBoss = ""), hostBossVersion = "9.2.25")

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.8.3"))

            assertEquals(1, result.availableUpdates.size)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `blank host version disables the gate`() =
        runTest {
            val mgr = manager(candidate("1.8.4", minBoss = "9.2.26"), hostBossVersion = "")

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.8.3"))

            assertEquals(1, result.availableUpdates.size)
        }

    @Test
    fun `unparseable minBossVersion fails open`() =
        runTest {
            val mgr = manager(candidate("1.8.4", minBoss = "not-a-version"), hostBossVersion = "9.2.25")

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.8.3"))

            assertEquals(1, result.availableUpdates.size)
        }
}

/** Minimal remote [PluginRepository] that always resolves to [latest]. */
private class FakeRepository(
    private val latest: PluginInfo,
) : PluginRepository {
    override val id = "fake-remote"
    override val name = "Fake Remote"
    override val isLocal = false
    override val isAvailable = true

    override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(listOf(latest))

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        Result.success(PluginSearchResult(listOf(latest), totalCount = 1))

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
        Result.success(if (pluginId == latest.pluginId) latest else null)

    override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        Result.success(if (pluginId == latest.pluginId) listOf(latest) else emptyList())

    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
    ): Result<String> = Result.success(targetPath)

    override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)
}
