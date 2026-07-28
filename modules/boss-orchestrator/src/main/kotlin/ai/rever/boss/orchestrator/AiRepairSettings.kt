package ai.rever.boss.orchestrator

/**
 * Whether AI repair may run, and the only directory it may read source from.
 *
 * [enabled] requires all three of: an explicit opt-in, a named root, and a key. Each covers a
 * different way this could switch itself on by accident — an operator who set a key for something
 * else, a deployment that meant to enable the feature but never said where to read from, or a
 * config that asks for AI repair on a machine with no credentials.
 */
internal data class AiRepairSettings(
    val enabled: Boolean,
    val projectRoot: String?,
)

/**
 * Decide whether source may be sent to a third-party model.
 *
 * Pure and separate from [main] because it is a data-egress decision: it should be readable and
 * testable on its own, not inferred from the shape of an environment.
 */
internal fun aiRepairSettings(
    optIn: String?,
    projectRoot: String?,
    apiKey: String?,
): AiRepairSettings {
    val root = projectRoot?.takeIf { it.isNotBlank() }
    val enabled =
        optIn?.equals("true", ignoreCase = true) == true &&
            root != null &&
            !apiKey.isNullOrBlank()
    return AiRepairSettings(enabled = enabled, projectRoot = root.takeIf { enabled })
}
