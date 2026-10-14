package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelId

/**
 * Well-known panel IDs used by the host application.
 * The host uses these IDs to address panels registered by dynamic plugins.
 *
 * Kept minimal on purpose: only entries with host callers live here.
 * Adding a panel? Add its constant alongside its first caller so the table cannot drift silently (see #719).
 */
object PanelIds {
    // Left panel plugins
    val CODEBASE = PanelId("codebase", 1)
    val BOOKMARKS = PanelId("bookmarks", 2)
    val DOWNLOADS = PanelId("downloads", 2)

    // Right panel plugins
    val TERMINAL = PanelId("terminal", 2)

    // Admin/Security panels
    val SECRET_MANAGER = PanelId("secret-manager", 2)

    // Plugin Manager (bundled)
    val PLUGIN_MANAGER = PanelId("plugin-manager", 2)
}
