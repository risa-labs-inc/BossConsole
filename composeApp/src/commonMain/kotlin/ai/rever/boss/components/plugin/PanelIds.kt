package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelId

/**
 * Well-known panel IDs used by the host application.
 * These IDs are shared between the host app and dynamic plugins.
 */
object PanelIds {
    // Left panel plugins
    val CODEBASE = PanelId("codebase", 1)
    val BOOKMARKS = PanelId("bookmarks", 2)
    val DOWNLOADS = PanelId("downloads", 2)
    val RUN_CONFIGURATIONS = PanelId("run-configurations", 2)

    // Right panel plugins
    val TERMINAL = PanelId("terminal", 2)
    val CONSOLE = PanelId("console", 2)
    val PERFORMANCE = PanelId("performance", 2)
    val TOP_OF_MIND = PanelId("topofmind", 2)
    val GIT_STATUS = PanelId("git-status", 2)
    val GIT_LOG = PanelId("git-log", 2)

    // Admin/Security panels
    val SECRET_MANAGER = PanelId("secret-manager", 2)
    val USER_SECRET_LIST = PanelId("user-secret-list", 2)
    val ADMIN_ROLE_MANAGEMENT = PanelId("admin-role-management", 2)
    val ROLE_CREATION = PanelId("role-creation", 2)

    // RPA panels
    val LLM_RPA = PanelId("llmrpa", 2)
    val RPA_RECORDER = PanelId("rparecorder", 2)
    val RPA_ENGINE = PanelId("rpaengine", 2)

    // Browser panel
    val FLUCK = PanelId("fluck", 2)

    // Plugin Manager (bundled)
    val PLUGIN_MANAGER = PanelId("plugin-manager", 2)
}
