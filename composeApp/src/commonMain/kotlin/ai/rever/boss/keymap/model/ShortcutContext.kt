package ai.rever.boss.keymap.model

import kotlinx.serialization.Serializable

/**
 * Defines the context in which a keyboard shortcut is active.
 * Context-aware shortcuts allow different key bindings in different parts of the application.
 */
@Serializable
enum class ShortcutContext {
    /**
     * Global shortcuts that work everywhere in the application.
     * Examples: New Window, Settings, Quick Switcher
     */
    GLOBAL,

    /**
     * Shortcuts that only work when focus is in a Fluck browser tab.
     * Examples: Reload Page, Zoom controls, Navigate Back/Forward
     */
    BROWSER,

    /**
     * Shortcuts that only work when focus is in a terminal panel.
     * Examples: Clear terminal, Copy/Paste (terminal-specific), Split pane
     */
    TERMINAL,

    /**
     * Shortcuts that only work when focus is in a code editor panel.
     * Examples: Go to Definition, Find References, Format Code
     * Note: Currently not implemented, reserved for future editor integration
     */
    EDITOR,

    /**
     * Shortcuts that only work when focus is in workspace/project management panels.
     * Examples: Save Workspace, Switch Project, Manage Workspace
     */
    WORKSPACE;

    /**
     * Human-readable display name for this context.
     */
    val displayName: String
        get() = when (this) {
            GLOBAL -> "Global"
            BROWSER -> "Browser"
            TERMINAL -> "Terminal"
            EDITOR -> "Editor"
            WORKSPACE -> "Workspace"
        }

    /**
     * Description of what this context represents.
     */
    val description: String
        get() = when (this) {
            GLOBAL -> "Works everywhere in the application"
            BROWSER -> "Active only in browser tabs"
            TERMINAL -> "Active only in terminal panels"
            EDITOR -> "Active only in code editor (future)"
            WORKSPACE -> "Active in workspace/project panels"
        }

    companion object {
        /**
         * Get all contexts that are currently implemented.
         */
        fun implemented(): List<ShortcutContext> = listOf(GLOBAL, BROWSER, TERMINAL, WORKSPACE)

        /**
         * Get contexts that are not yet implemented.
         */
        fun notImplemented(): List<ShortcutContext> = listOf(EDITOR)
    }
}
