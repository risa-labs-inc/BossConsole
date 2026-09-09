package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.flow.Flow

/**
 * Minimal remote [PluginRepository] that always resolves to [latest].
 *
 * Shared because three update-manager test classes each want the same "the store advertises
 * exactly this one version" fixture, and each had grown its own copy of these ten overrides.
 */
internal class FakeSingleVersionRepository(
    private val latest: PluginInfo,
    override val id: String = "fake-remote",
    override val name: String = "Fake Remote",
) : PluginRepository {
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
        onProgress: ((Float) -> Unit)?,
    ): Result<String> = Result.success(targetPath)

    override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)
}
