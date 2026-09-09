package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [PluginUpdateManager.checkForUpdates] routes a newer version to
 * either `availableUpdates` or `incompatibleNotices` based on the injected
 * host-IPC compatibility predicate — i.e. an incompatible newer version is
 * reported, never offered for install (issue #740). The IPC rules themselves
 * live in (and are tested against) IpcVersion / IpcCompatibility; here we only
 * assert that the manager honours the predicate.
 */
class PluginUpdateManagerCheckCompatTest {
    private val pluginId = "com.example.demo"

    private fun candidate(
        version: String,
        minIpc: String,
    ) = PluginInfo(
        pluginId = pluginId,
        displayName = "Demo",
        version = version,
        minIpcVersion = minIpc,
    )

    private fun managerReturning(
        latest: PluginInfo,
        isCompatible: (String) -> Boolean,
    ): PluginUpdateManager {
        val repos = PluginRepositoryManager().apply { addRepository(FakeSingleVersionRepository(latest)) }
        return PluginUpdateManager(
            repositoryManager = repos,
            hostIpcVersion = "1.0.0",
            isIpcCompatible = isCompatible,
        )
    }

    @Test
    fun `incompatible newer version is reported, not offered`() =
        runTest {
            val mgr = managerReturning(candidate("1.1.0", "2.0.0"), isCompatible = { false })

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            assertTrue(result.availableUpdates.isEmpty(), "incompatible update must not be offered")
            assertEquals(1, result.incompatibleNotices.size)
            val notice = result.incompatibleNotices.first()
            assertEquals(pluginId, notice.pluginId)
            assertEquals("1.1.0", notice.advertisedLatest)
            assertEquals("2.0.0", notice.requiredIpcVersion)
            assertEquals("1.0.0", notice.hostIpcVersion)
        }

    @Test
    fun `compatible newer version is offered`() =
        runTest {
            val mgr = managerReturning(candidate("1.1.0", "1.0.0"), isCompatible = { true })

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            assertEquals(1, result.availableUpdates.size)
            assertEquals("1.1.0", result.availableUpdates.first().newVersion)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `no update when installed version is already current`() =
        runTest {
            val mgr = managerReturning(candidate("1.0.0", "1.0.0"), isCompatible = { true })

            val result = mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            assertTrue(result.availableUpdates.isEmpty())
            assertTrue(result.incompatibleNotices.isEmpty())
        }
}
