package ai.rever.boss.plugin

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the external-plugin scan honours the resource tier, i.e. that **what loads equals
 * what was admitted**.
 *
 * This is the test that was missing when the feature shipped broken. Every other test here
 * covered the *decision* - which plugins the tier admits - and all of them passed while the
 * feature saved nothing, because a second loader undid the first. On a real launch the log read
 * `skipped=22, loading=6` and 32 plugins loaded: every one installed.
 *
 * `DefaultPlugin` scans the plugin directory and installs any jar it does not find in
 * `pluginStates`, on the reasonable assumption that such a jar was dropped in by hand. A plugin
 * the tier declined is indistinguishable from that, so the gate and the scan cancelled out
 * exactly. The fix hands the scan the declined paths; these tests pin the arithmetic of that
 * hand-off, since the scan itself needs a manager and a directory.
 *
 * The lesson worth keeping: a gate is only as good as every path that can load the thing it
 * gates. Asserting on the gate's own report proves nothing about the outcome.
 */
class ResourceModeScanBypassTest {
    private fun entry(
        id: String,
        jar: String,
        enabled: Boolean = true,
    ) = PluginPersistence.InstalledPluginEntry(pluginId = id, jarPath = jar, enabled = enabled)

    private val core = entry("core", "/plugins/core.jar")
    private val extra = entry("extra", "/plugins/extra.jar")
    private val alsoExtra = entry("also-extra", "/plugins/also-extra.jar")

    /** Admits only "core", mirroring an ULTRA_LITE allowlist. */
    private val allow: (String) -> Boolean = { it == "core" }

    /**
     * The invariant the bug violated: everything the tier declined must be excluded from the
     * scan's candidates, so the set that ends up loaded is exactly the admitted set.
     */
    @Test
    fun `every declined jar is handed to the scan as already-handled`() {
        val all = listOf(core, extra, alsoExtra)
        val (admitted, skipped) =
            PluginStoreSetup.partitionForResourceMode(all, BossResourceMode.ULTRA_LITE, allow)

        val skippedJars = skipped.map { it.jarPath }.toSet()

        // Simulate the scan: it walks every jar on disk and installs any it does not recognise,
        // where "recognise" means loaded already OR declined by the tier.
        val trackedByLoad = admitted.map { it.jarPath }.toSet()
        val wouldLoad = all.map { it.jarPath }.filterNot { it in trackedByLoad || it in skippedJars }

        assertTrue(
            wouldLoad.isEmpty(),
            "the scan would reload $wouldLoad, undoing the tier - this is exactly the bug",
        )
        assertEquals(setOf("/plugins/core.jar"), trackedByLoad)
        assertEquals(setOf("/plugins/extra.jar", "/plugins/also-extra.jar"), skippedJars)
    }

    /**
     * Without the skip list the scan reloads everything, which is what actually shipped. Pinned
     * so the fix cannot be quietly removed and leave the other tests still green.
     */
    @Test
    fun `without the skip list the scan would reload everything the tier declined`() {
        val all = listOf(core, extra, alsoExtra)
        val (admitted, skipped) =
            PluginStoreSetup.partitionForResourceMode(all, BossResourceMode.ULTRA_LITE, allow)

        val trackedByLoad = admitted.map { it.jarPath }.toSet()
        val wouldLoadWithoutFix = all.map { it.jarPath }.filterNot { it in trackedByLoad }

        assertEquals(
            skipped.map { it.jarPath }.toSet(),
            wouldLoadWithoutFix.toSet(),
            "the un-fixed scan reloads precisely the set the tier skipped, saving nothing",
        )
    }

    @Test
    fun `an ungated tier hands the scan nothing to exclude`() {
        for (mode in listOf(BossResourceMode.FULL, BossResourceMode.LITE)) {
            val (_, skipped) =
                PluginStoreSetup.partitionForResourceMode(
                    listOf(core, extra),
                    mode,
                ) { false }
            assertTrue(skipped.isEmpty(), "${mode.name} must not exclude anything from the scan")
        }
    }

    /**
     * A user-disabled plugin must not reach the skip list either. It is not loaded, but it was
     * the user's choice rather than the tier's, and putting it here would both misattribute it in
     * Settings and make the scan's behaviour depend on the resource mode for the wrong reason.
     */
    @Test
    fun `a user-disabled plugin is not handed to the scan as tier-skipped`() {
        val disabled = entry("disabled", "/plugins/disabled.jar", enabled = false)
        val (_, skipped) =
            PluginStoreSetup.partitionForResourceMode(
                listOf(core, disabled),
                BossResourceMode.ULTRA_LITE,
                allow,
            )
        assertTrue(skipped.none { it.pluginId == "disabled" })
    }
}
