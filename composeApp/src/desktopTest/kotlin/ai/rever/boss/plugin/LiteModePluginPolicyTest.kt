package ai.rever.boss.plugin

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards which plugins survive a reduced [BossResourceMode].
 *
 * The bootstrap exemption is the load-bearing part. `ai.rever.boss.plugin.api` supplies the API
 * layer every other plugin links against, and the plugin manager supplies the only UI from which
 * a user can leave the tier. Gating either would produce an install that cannot load plugins and
 * cannot be told to stop - recoverable only by editing a config file the user has no reason to
 * know about.
 */
class LiteModePluginPolicyTest {
    private val api = "ai.rever.boss.plugin.api"
    private val pluginManager = "ai.rever.boss.plugin.dynamic.pluginmanager"
    private val terminal = "ai.rever.boss.plugin.dynamic.terminaltab"
    private val random = "ai.rever.boss.plugin.dynamic.somethingelse"

    private fun allowed(
        pluginId: String,
        mode: BossResourceMode,
        liteEligible: Set<String> = setOf(terminal),
        user: Set<String> = emptySet(),
    ) = LiteModePluginPolicy.isAllowed(
        pluginId = pluginId,
        mode = mode,
        liteEligibleIds = liteEligible,
        userAllowlist = user,
    )

    @Test
    fun `ungated tiers admit everything`() {
        for (mode in listOf(BossResourceMode.FULL, BossResourceMode.LITE)) {
            for (id in listOf(api, pluginManager, terminal, random)) {
                assertTrue(allowed(id, mode, liteEligible = emptySet()), "$id under ${mode.name}")
            }
        }
    }

    /**
     * LITE must not gate plugins even though it is a reduced tier. If this ever flips, the two
     * tiers have collapsed into one and everything Settings says about LITE is wrong.
     */
    @Test
    fun `LITE is reduced but never gates plugins`() {
        assertTrue(BossResourceMode.LITE.isReduced)
        assertFalse(BossResourceMode.LITE.gatesPlugins)
        assertTrue(allowed(random, BossResourceMode.LITE, liteEligible = emptySet()))
    }

    @Test
    fun `ULTRA_LITE always admits the bootstrap plugins`() {
        // Even with an empty manifest and an empty user list - which is exactly the state of a
        // first run on a machine whose cache was wiped.
        for (id in SystemPluginManifestService.BOOTSTRAP_PLUGIN_IDS) {
            assertTrue(
                allowed(id, BossResourceMode.ULTRA_LITE, liteEligible = emptySet()),
                "$id must survive ULTRA_LITE",
            )
        }
    }

    @Test
    fun `the real bootstrap set covers the api and the plugin manager`() {
        // Pinned by id rather than by count: this is about those two capabilities existing,
        // not about the set's size.
        assertTrue(api in SystemPluginManifestService.BOOTSTRAP_PLUGIN_IDS)
        assertTrue(pluginManager in SystemPluginManifestService.BOOTSTRAP_PLUGIN_IDS)
    }

    @Test
    fun `ULTRA_LITE admits manifest-curated plugins`() {
        assertTrue(allowed(terminal, BossResourceMode.ULTRA_LITE, liteEligible = setOf(terminal)))
    }

    @Test
    fun `ULTRA_LITE skips anything uncurated`() {
        assertFalse(allowed(random, BossResourceMode.ULTRA_LITE))
    }

    @Test
    fun `a user can opt a plugin back in without leaving the tier`() {
        assertFalse(allowed(random, BossResourceMode.ULTRA_LITE))
        assertTrue(allowed(random, BossResourceMode.ULTRA_LITE, user = setOf(random)))
    }

    /**
     * The shipped fallback set is the core product, so every one of its rows has to survive the
     * tier. A row added later without the flag would silently strip a core feature on Windows,
     * which defaults to ULTRA_LITE - and it would only show up on the platform least likely to
     * be the developer's.
     *
     * This reads the LIVE manifest, cache included, so it also covers the upgrade path pinned
     * by `an older cache cannot revoke eligibility` below.
     */
    @Test
    fun `every shipped system plugin survives ULTRA_LITE`() {
        val eligible = SystemPluginManifestService.liteEligibleIds()
        for (info in SystemPluginManifestService.currentList(isKernelMode = true)) {
            assertTrue(
                LiteModePluginPolicy.isAllowed(
                    pluginId = info.pluginId,
                    mode = BossResourceMode.ULTRA_LITE,
                    liteEligibleIds = eligible,
                    userAllowlist = emptySet(),
                ),
                "${info.pluginId} is a system plugin but would be skipped in ULTRA_LITE",
            )
        }
    }

    /**
     * Every install upgrading into this feature has a cached `system-plugins.json` written
     * before the `lite_eligible` column existed, where the flag decodes to its `false` default.
     * Read verbatim, that cache marks the entire core set ineligible - so the first launch on
     * Windows, which defaults to ULTRA_LITE, would come up with no terminal, no browser, no
     * editor and no plugin manager, and look like a broken install rather than a reduced one.
     *
     * `mergeWithFallback` is what prevents it, on the same principle it already applies to
     * bootstrap rows and version floors: remote and cached rows may GRANT eligibility, never
     * revoke it from a row this build ships.
     *
     * This test found that bug. It was not written in anticipation of it.
     */
    @Test
    fun `an older cache cannot revoke eligibility from the shipped set`() {
        val preUpgradeCache =
            SystemPluginManifestService
                .currentList(isKernelMode = true)
                .map { info ->
                    SystemPluginManifestEntry(
                        pluginId = info.pluginId,
                        githubRepo = info.githubRepo,
                        artifactPrefix = info.artifactPrefix,
                        loadPriority = info.loadPriority,
                        // The state an old cache decodes to: the column simply is not there.
                        liteEligible = false,
                    )
                }

        val merged = SystemPluginManifestService.mergeWithFallbackForTest(preUpgradeCache)
        val eligible = merged.filter { it.liteEligible }.map { it.pluginId }.toSet()

        for (entry in merged) {
            assertTrue(
                LiteModePluginPolicy.isAllowed(
                    pluginId = entry.pluginId,
                    mode = BossResourceMode.ULTRA_LITE,
                    liteEligibleIds = eligible,
                    userAllowlist = emptySet(),
                ),
                "${entry.pluginId} lost its eligibility to a pre-upgrade cache",
            )
        }
    }
}
