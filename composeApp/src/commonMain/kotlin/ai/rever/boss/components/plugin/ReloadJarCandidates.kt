package ai.rever.boss.components.plugin

/**
 * The known reload candidates in original position order: [loadedJarPath] (the jar the plugin is
 * running from) first, then [persistedJarPath] (the installer's recorded one). Grouped so the
 * resolver signature stays within detekt's [LongParameterList] budget.
 */
internal data class ReloadJarCandidates(
    val loadedJarPath: String?,
    val persistedJarPath: String?,
)
