package ai.rever.boss.plugin

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [PluginStoreSetup.partitionForResourceMode].
 *
 * This logic sat inline in a large `suspend` function and so had no coverage, which is how its
 * one interesting rule shipped broken and had to be fixed in review: **a plugin the user disabled
 * is not a plugin the tier skipped.** Getting that wrong put user-disabled plugins in the
 * "Plugins Skipped This Launch" list, blaming a reduced tier for the user's own choice.
 */
class ResourceModePartitionTest {
    private fun entry(
        id: String,
        enabled: Boolean = true,
    ) = PluginPersistence.InstalledPluginEntry(
        pluginId = id,
        jarPath = "/tmp/$id.jar",
        enabled = enabled,
    )

    private val core = entry("core")
    private val extra = entry("extra")
    private val userDisabled = entry("user-disabled", enabled = false)

    /** Admits only "core". */
    private val allow: (String) -> Boolean = { it == "core" }

    @Test
    fun `an ungated tier admits everything and skips nothing`() {
        for (mode in listOf(BossResourceMode.FULL, BossResourceMode.LITE)) {
            val (admitted, skipped) =
                PluginStoreSetup.partitionForResourceMode(listOf(core, extra), mode) { false }
            assertEquals(2, admitted.size, mode.name)
            assertTrue(skipped.isEmpty(), "${mode.name} must not skip anything")
        }
    }

    @Test
    fun `ULTRA_LITE skips what the policy declines`() {
        val (admitted, skipped) =
            PluginStoreSetup.partitionForResourceMode(
                listOf(core, extra),
                BossResourceMode.ULTRA_LITE,
                allow,
            )
        assertEquals(listOf("core"), admitted.map { it.pluginId })
        assertEquals(listOf("extra"), skipped.map { it.pluginId })
    }

    /**
     * The rule that had to be fixed by hand. A user-disabled plugin is not loaded either way, so
     * attributing it to the resource mode makes Settings blame the tier for a choice the user
     * made themselves.
     */
    @Test
    fun `a user-disabled plugin is never reported as tier-skipped`() {
        val (_, skipped) =
            PluginStoreSetup.partitionForResourceMode(
                listOf(userDisabled),
                BossResourceMode.ULTRA_LITE,
                // Even though the policy would also decline it.
                { false },
            )
        assertTrue(
            skipped.isEmpty(),
            "a plugin the user disabled must not appear as skipped by the tier",
        )
    }

    @Test
    fun `an empty input produces empty halves`() {
        val (admitted, skipped) =
            PluginStoreSetup.partitionForResourceMode(
                emptyList(),
                BossResourceMode.ULTRA_LITE,
                allow,
            )
        assertTrue(admitted.isEmpty())
        assertTrue(skipped.isEmpty())
    }
}
