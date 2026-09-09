package ai.rever.boss.components.home

/**
 * The INSTALLED version of a plugin from `installed.json`, or null when it has no row.
 *
 * expect/actual only because `installed.json` is a desktop concern: the
 * retired-plugin OFFER filter (commonMain) needs the sweep's floor, which lives
 * in that table. A host without an install table answers null, and the filter
 * fails closed - the retired plugin stays OFFERED, the conservative side.
 */
internal expect fun installedPluginVersionOf(pluginId: String): String?
