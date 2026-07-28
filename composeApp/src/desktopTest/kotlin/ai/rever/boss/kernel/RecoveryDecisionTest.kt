package ai.rever.boss.kernel

import ai.rever.boss.ipc.proto.RepairAction
import ai.rever.boss.ipc.proto.RepairStrategy
import ai.rever.boss.ipc.proto.RestartAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what the kernel does with the orchestrator's repair advice.
 *
 * The case that matters most is the one with no advice at all: routing crash recovery through
 * another process is only safe while an unreachable, wedged or crashed orchestrator still leaves
 * the kernel doing exactly what it did before — respawning. Everything else here is about not
 * silently swallowing a repair that a human was supposed to see.
 */
class RecoveryDecisionTest {
    private fun action(
        strategy: RepairStrategy,
        approval: Boolean = false,
        description: String = "",
        tunedArgs: List<String> = emptyList(),
    ): RepairAction =
        RepairAction
            .newBuilder()
            .setRepairId("r1")
            .setStrategy(strategy)
            .setRequiresUserApproval(approval)
            .setDescription(description)
            .apply {
                if (tunedArgs.isNotEmpty()) {
                    restart = RestartAction.newBuilder().addAllJvmArgsOverride(tunedArgs).build()
                }
            }.build()

    @Test
    fun `no advice still recovers the process`() {
        // The orchestrator is down, unreachable, or timed out. This is the whole safety argument
        // for consulting it at all.
        assertEquals(Recovery.Respawn, recoveryFor(null))
    }

    @Test
    fun `a plain restart is a plain respawn`() {
        assertEquals(Recovery.Respawn, recoveryFor(action(RepairStrategy.REPAIR_STRATEGY_RESTART)))
    }

    @Test
    fun `a state reset is a respawn on this side`() {
        // The orchestrator has already picked the snapshot; the kernel only brings it back up.
        assertEquals(Recovery.Respawn, recoveryFor(action(RepairStrategy.REPAIR_STRATEGY_RESET_STATE)))
    }

    @Test
    fun `a tuned restart carries the new jvm args`() {
        val recovery =
            recoveryFor(
                action(RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED, tunedArgs = listOf("-Xmx512m")),
            )

        assertEquals(Recovery.RespawnTuned(listOf("-Xmx512m")), recovery)
    }

    @Test
    fun `a tuned restart with no args is just a restart`() {
        // Nothing to apply — don't hand an empty jvmArgs list to the spawner and lose the
        // process's configured defaults.
        assertEquals(
            Recovery.Respawn,
            recoveryFor(action(RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)),
        )
    }

    @Test
    fun `anything needing approval reaches the operator and still restarts`() {
        val recovery =
            recoveryFor(
                action(
                    RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE,
                    approval = true,
                    description = "Proposed fix for NullPointerException",
                ),
            )

        assertEquals(Recovery.NotifyAndRespawn("Proposed fix for NullPointerException"), recovery)
    }

    @Test
    fun `an escalation reaches the operator and still restarts`() {
        val recovery = recoveryFor(action(RepairStrategy.REPAIR_STRATEGY_ESCALATE, description = "Needs a human"))

        assertEquals(Recovery.NotifyAndRespawn("Needs a human"), recovery)
    }

    @Test
    fun `a strategy the kernel cannot apply is never silently dropped`() {
        // PATCH_CONFIG without an approval flag: the kernel patches nothing, so the operator hears
        // about it rather than the advice evaporating into a restart.
        val recovery = recoveryFor(action(RepairStrategy.REPAIR_STRATEGY_PATCH_CONFIG))

        assertEquals(Recovery.NotifyAndRespawn("REPAIR_STRATEGY_PATCH_CONFIG"), recovery)
    }

    @Test
    fun `a repair with no description falls back to naming its strategy`() {
        val recovery = recoveryFor(action(RepairStrategy.REPAIR_STRATEGY_ESCALATE, approval = true))

        assertEquals(Recovery.NotifyAndRespawn("REPAIR_STRATEGY_ESCALATE"), recovery)
    }
}
