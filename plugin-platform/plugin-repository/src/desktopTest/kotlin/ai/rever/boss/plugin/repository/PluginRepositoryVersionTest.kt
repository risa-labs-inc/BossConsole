package ai.rever.boss.plugin.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Update-check version ordering.
 *
 * The cases the previous hand-rolled comparison got wrong are the regression
 * tests here: it split on "." and dropped any segment that was not a bare
 * integer, and each dropped segment shifted every later one into the wrong
 * position - "1.0.0+build.7" parsed as [1, 0, 7], the 7 landing in the PATCH
 * slot, and beat the installed [1, 0, 0]. Those are the build-metadata case
 * below, the release-candidate downgrade, the upgrade over a pre-release
 * install, the pre-release ordering among themselves, and the "v"-prefixed tag.
 *
 * The remaining cases ALSO pass against the old comparison. They are pinned as
 * guards so the delegation to SemanticVersion cannot quietly regress the
 * ordinary orderings the old code already got right.
 *
 * The two that a user would actually notice are pinned first.
 */
class PluginRepositoryVersionTest {
    private val manager = PluginRepositoryManager()

    @Test
    fun `build metadata does not make a version newer than itself`() {
        // The loop case. "1.0.0+build.7" read as [1, 0, 7] - the "0+build" segment
        // was dropped, so the 7 landed in the PATCH slot - beating [1, 0, 0], so the
        // store offered an update to the version already installed, on every check,
        // forever. SemVer section 10 says build metadata is ignored for precedence.
        assertFalse(manager.isNewerVersion("1.0.0+build.7", "1.0.0"))
    }

    @Test
    fun `a release candidate is never offered over its own release`() {
        // "2.0.0-rc.1" read as [2, 0, 1] - "0-rc" was dropped, so the 1 landed in
        // the PATCH slot - and the old loop returned true the moment 1 > 0 at that
        // third position, offering the pre-release over its own release.
        assertFalse(manager.isNewerVersion("2.0.0-rc.1", "2.0.0"))
    }

    @Test
    fun `a real upgrade over a pre-release install is still offered`() {
        // The silent-stall case. Installed "1.0-beta.5" read as [1, 5], so 5 sat in
        // the MINOR position and beat the 2 of "1.2.3". The genuine update was never
        // offered at all.
        assertTrue(manager.isNewerVersion("1.2.3", "1.0-beta.5"))
    }

    @Test
    fun `ordinary upgrades are offered`() {
        assertTrue(manager.isNewerVersion("1.2.4", "1.2.3"))
        assertTrue(manager.isNewerVersion("1.10.0", "1.9.0"))
        assertTrue(manager.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun `downgrades and equal versions are not offered`() {
        assertFalse(manager.isNewerVersion("1.2.3", "1.2.4"))
        assertFalse(manager.isNewerVersion("1.2.3", "1.2.3"))
        assertFalse(manager.isNewerVersion("1.9.0", "1.10.0"))
    }

    @Test
    fun `a release is newer than its own pre-release`() {
        assertTrue(manager.isNewerVersion("1.2.3", "1.2.3-rc1"))
        assertFalse(manager.isNewerVersion("1.2.3-rc1", "1.2.3"))
    }

    @Test
    fun `pre-releases order among themselves the way SemVer says`() {
        // alpha < beta < rc, by identifier comparison.
        assertTrue(manager.isNewerVersion("1.2.3-rc1", "1.2.3-beta.1"))
        assertFalse(manager.isNewerVersion("1.2.3-beta.1", "1.2.3-rc1"))
    }

    @Test
    fun `a missing patch or minor counts as zero rather than as absent`() {
        assertFalse(manager.isNewerVersion("1.2", "1.2.0"))
        assertFalse(manager.isNewerVersion("1", "1.0.0"))
        assertTrue(manager.isNewerVersion("1.2.1", "1.2"))
    }

    @Test
    fun `an unreadable candidate version is never offered`() {
        // Nothing can be said about it, so proposing it would be a guess. A
        // "v"-prefixed tag is the common case: it is not a SemVer version.
        assertFalse(manager.isNewerVersion("v1.2.3", "1.2.3"))
        assertFalse(manager.isNewerVersion("", "1.2.3"))
        assertFalse(manager.isNewerVersion("not-a-version", "1.2.3"))
    }

    @Test
    fun `a plugin whose installed version is unreadable is still offered an update`() {
        // Deliberately the opposite of the rule above, and the one behaviour this
        // change had to preserve from the old comparison. Such a plugin is already
        // in a broken state; withholding every future update would strand it there.
        assertTrue(manager.isNewerVersion("1.2.3", ""))
        assertTrue(manager.isNewerVersion("1.2.3", "unknown"))
    }

    // The two cases below drive checkForUpdates end to end. It has no production
    // caller today, and without them the offerUpdateIfNewer helper and its debug
    // log would have no coverage at all; everything else in this file pins
    // isNewerVersion directly.

    @Test
    fun `a newer store version is offered through checkForUpdates`() =
        runTest {
            val manager = PluginRepositoryManager()
            manager.addRepository(UpdateCheckRepository(Result.success(plugin("1.0.1"))))

            val result = manager.checkForUpdates(mapOf("com.example.plugin" to "1.0.0"))

            assertTrue(result.isSuccess)
            val offered = result.getOrThrow()
            assertEquals(1, offered.size)
            assertEquals("1.0.1", offered.single().plugin.version)
        }

    @Test
    fun `an unparseable store version is never offered through checkForUpdates`() =
        runTest {
            val manager = PluginRepositoryManager()
            manager.addRepository(UpdateCheckRepository(Result.success(plugin("1.0.1.RELEASE"))))

            val updates = manager.checkForUpdates(mapOf("com.example.plugin" to "1.0.0"))

            assertTrue(updates.isSuccess)
            assertTrue(
                updates.getOrThrow().isEmpty(),
                "nothing can be said about a version we cannot parse",
            )
        }

    private fun plugin(version: String) =
        PluginInfo(
            pluginId = "com.example.plugin",
            displayName = "Example",
            version = version,
            description = "Test plugin",
        )

    /** Answers every lookup with one canned [Result]; only getPlugin is exercised. */
    private class UpdateCheckRepository(
        private val answer: Result<PluginInfo?>,
    ) : PluginRepository {
        override val id: String = "store"
        override val name: String = id
        override val isLocal: Boolean = false
        override val isAvailable: Boolean = true

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = answer

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> = Result.failure(UnsupportedOperationException())

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }
}
