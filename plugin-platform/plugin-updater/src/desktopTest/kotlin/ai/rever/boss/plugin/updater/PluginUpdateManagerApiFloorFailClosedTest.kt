package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A declared `minApiVersion` must not be skipped just because the installed API layer version
 * could not be read - but "could not be read" has two meanings and only one of them may withhold
 * updates indefinitely.
 *
 * `hostApiVersion` returns null before `DynamicPluginManager.initializeApiLayer` publishes
 * `boss.api.version`, and the empty string once it ran and found no api jar
 * (`ApiClassLoader.apiVersion` is null on a first run offline). The first is transient and worth
 * failing closed on; the second can last a whole session, and failing closed there would
 * withhold every floor-declaring plugin update on that host forever.
 *
 * The case that motivated the gate: terminal-tab 2.5.74 declares `minApiVersion` 1.0.88 and
 * implements renameTab / tabActivity / initialCommand against it. On a host carrying API 1.0.87
 * those symbols are absent, so "offered but broken" is worse than "not offered yet".
 */
class PluginUpdateManagerApiFloorFailClosedTest {
    private val pluginId = "com.example.demo"

    private fun candidate(
        version: String,
        minApi: String,
    ) = PluginInfo(
        pluginId = pluginId,
        displayName = "Demo",
        version = version,
        minApiVersion = minApi,
    )

    private fun manager(
        latest: PluginInfo,
        installedApi: String?,
    ): PluginUpdateManager {
        val repos = PluginRepositoryManager().apply { addRepository(FakeSingleVersionRepository(latest)) }
        return PluginUpdateManager(
            repositoryManager = repos,
            hostApiVersion = { installedApi },
        )
    }

    private suspend fun check(
        minApi: String,
        installedApi: String?,
    ) = manager(candidate("2.5.74", minApi = minApi), installedApi = installedApi)
        .checkForUpdates(mapOf(pluginId to "2.5.71"))

    // ---- the API layer has not resolved yet: fail closed ----

    @Test
    fun `declared floor with an unresolved API layer is not offered`() =
        runTest {
            val result = check(minApi = "1.0.88", installedApi = null)

            assertTrue(
                result.availableUpdates.isEmpty(),
                "a floor we cannot prove is met must not be offered",
            )
            assertEquals(1, result.incompatibleNotices.size)
            val notice = result.incompatibleNotices.first()
            assertEquals("2.5.74", notice.advertisedLatest)
            assertEquals("1.0.88", notice.requiredApiVersion)
        }

    @Test
    fun `declared floor with an unreadable API version is not offered`() =
        runTest {
            // A locally built api jar whose Implementation-Version is not strict semver.
            val result = check(minApi = "1.0.88", installedApi = "not-a-version")

            assertTrue(result.availableUpdates.isEmpty())
            assertEquals(1, result.incompatibleNotices.size)
        }

    // ---- resolved, but no api jar present: keep the pre-existing fail-open answer ----

    @Test
    fun `declared floor with a resolved but absent api jar is still offered`() =
        runTest {
            // Blank means initializeApiLayer ran and found no jar, which can persist for the
            // whole session. Withholding here would be an indefinite outage, and the loader
            // does not reject in this state either - it skips minApiVersion validation.
            val result = check(minApi = "1.0.88", installedApi = "")

            assertEquals(1, result.availableUpdates.size)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    // ---- no floor, or a floor we cannot parse: fail open unconditionally ----

    @Test
    fun `no declared floor is offered even when the API layer is unresolved`() =
        runTest {
            // Every version published before min_api_version existed carries "". Gating those
            // would withhold every update on a host whose API had not resolved.
            val result = check(minApi = "", installedApi = null)

            assertEquals(1, result.availableUpdates.size)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `a malformed floor is offered regardless of the installed version`() =
        runTest {
            // The floor is store data, not ours, and one malformed row must not withhold a
            // plugin's updates. Both combinations are pinned because the guard order decides
            // this: asking about the installed version first made the malformed case depend
            // on it, which contradicted the documented behaviour.
            val resolved = check(minApi = "garbage", installedApi = "1.0.88")
            assertEquals(1, resolved.availableUpdates.size, "malformed floor, resolved host")
            assertTrue(resolved.incompatibleNotices.isEmpty())

            val unresolved = check(minApi = "garbage", installedApi = null)
            assertEquals(1, unresolved.availableUpdates.size, "malformed floor, unresolved host")
            assertTrue(unresolved.incompatibleNotices.isEmpty())
        }

    // ---- ordinary comparisons ----

    @Test
    fun `satisfied floor is offered`() =
        runTest {
            val result = check(minApi = "1.0.88", installedApi = "1.0.88")

            assertEquals(1, result.availableUpdates.size)
            assertEquals("2.5.74", result.availableUpdates.first().newVersion)
            assertTrue(result.incompatibleNotices.isEmpty())
        }

    @Test
    fun `floor one patch above the installed API is not offered`() =
        runTest {
            // The exact terminal-tab case: 1.0.87 against a 1.0.88 floor.
            val result = check(minApi = "1.0.88", installedApi = "1.0.87")

            assertTrue(result.availableUpdates.isEmpty())
            assertEquals("1.0.87", result.incompatibleNotices.first().hostApiVersion)
        }

    @Test
    fun `a prerelease installed API still satisfies its release floor`() =
        runTest {
            // satisfiesVersionFloor compares the release core only, and
            // PluginUpdateManagerBossVersionGateTest pins that for the Boss gate. This gate now
            // parses the installed version itself before delegating, so pin it here too.
            val result = check(minApi = "1.0.88", installedApi = "1.0.88-alpha.1")

            assertEquals(1, result.availableUpdates.size)
            assertTrue(result.incompatibleNotices.isEmpty())
        }
}
