package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.utils.awaitRegistryCondition
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

private val logger = BossLogger.forComponent("WorkspaceApplier")

/**
 * Suspend until every [typeIds] entry is registered, or until the plugin
 * registration timeout elapses. On timeout the apply proceeds anyway — tabs of
 * still-missing types are skipped exactly as before, but a warning is logged
 * instead of failing silently.
 */
internal suspend fun TabRegistry.awaitTabTypes(typeIds: Set<TabTypeId>) {
    fun missing() = typeIds.filterNot { isRegistered(it) }
    if (missing().isEmpty()) return

    logger.info(
        LogCategory.WORKSPACE,
        "Waiting for plugin tab types before applying workspace",
        mapOf(
            "missing" to missing().joinToString { it.typeId },
        ),
    )
    val registered =
        awaitRegistryCondition(::addChangeListener, ::removeChangeListener) {
            missing().isEmpty()
        }
    if (!registered) {
        logger.warn(
            LogCategory.WORKSPACE,
            "Tab types still unregistered after wait - their tabs will be skipped",
            mapOf(
                "missing" to missing().joinToString { it.typeId },
            ),
        )
    }
}
