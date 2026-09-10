package ai.rever.boss.plugin

import ai.rever.boss.plugin.PluginStoreSetup.RepairDisposition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the per-plugin decision the store-repair drain makes, from BossConsole#399.
 *
 * The drain polls its queue, so whatever it does not act on is gone for the life
 * of the process. Both skip reasons exist to stop it doing damage rather than to
 * save work: re-downloading a plugin another path has since installed would leave
 * two versions in the plugin directory for `PluginJarReconciler` to resolve, and
 * re-attempting a plugin with no store row would spend a lookup per trigger for a
 * fault that will not clear until the next launch.
 */
class StoreRepairDispositionTest {
    private fun disposition(
        jarPresent: Boolean = false,
        alreadyAttempted: Boolean = false,
    ) = PluginStoreSetup.repairDisposition(jarPresent, alreadyAttempted)

    @Test
    fun `a plugin the GitHub path could not supply is repaired`() {
        assertEquals(RepairDisposition.REPAIR, disposition())
    }

    @Test
    fun `a JAR that arrived by another route between queueing and draining is left alone`() {
        // The real race this guards. The entry is queued during startup; the drain
        // runs after the auth token lands, and a background update check or a
        // realtime manifest re-run can install the plugin in between.
        assertEquals(RepairDisposition.ALREADY_PRESENT, disposition(jarPresent = true))
    }

    @Test
    fun `presence wins over having already attempted`() {
        // Ordering, not just the individual answers. Both are skips, but they are
        // reported separately in the log, and "already present" is the honest
        // reason when the JAR is there.
        assertEquals(
            RepairDisposition.ALREADY_PRESENT,
            disposition(jarPresent = true, alreadyAttempted = true),
        )
    }

    @Test
    fun `a plugin that already spent its store attempt is not retried this process`() {
        assertEquals(RepairDisposition.ALREADY_ATTEMPTED, disposition(alreadyAttempted = true))
    }
}
