package ai.rever.boss.keymap.model

import kotlinx.serialization.Serializable

/**
 * How Ctrl+Tab / Ctrl+Shift+Tab cycle between tabs in the active panel.
 *
 * - [POSITIONAL]: move to the next/previous tab in tab-bar order, wrapping at the ends.
 * - [MRU]: cycle in most-recently-used order (Alt+Tab style); the landed tab is promoted
 *   to the front when the cycling modifier is released.
 */
@Serializable
enum class TabSwitchMode {
    POSITIONAL,
    MRU
}

/**
 * Container for all keyboard shortcut settings.
 *
 * @property shortcuts Map of action ID to KeyBinding
 * @property presetName Name of the preset being used (e.g., "BOSS Default", "VS Code", "IntelliJ")
 * @property customized Whether this keymap has been customized from the preset
 * @property tabSwitchMode How Ctrl+Tab cycles between tabs (positional vs. most-recently-used)
 * @property version Schema version for future migrations
 */
@Serializable
data class KeymapSettings(
    val shortcuts: Map<String, KeyBinding> = emptyMap(),
    val presetName: String = "BOSS Default",
    val customized: Boolean = false,
    val tabSwitchMode: TabSwitchMode = TabSwitchMode.MRU,
    val version: Int = 1
) {
    /**
     * Get a key binding by action ID.
     */
    fun getBinding(actionId: String): KeyBinding? = shortcuts[actionId]

    /**
     * Get all bindings for a specific context.
     */
    fun getBindingsForContext(context: ShortcutContext): List<KeyBinding> {
        return shortcuts.values.filter { it.context == context }
    }

    /**
     * Get all enabled bindings.
     */
    fun getEnabledBindings(): List<KeyBinding> {
        return shortcuts.values.filter { it.enabled }
    }

    /**
     * Get bindings grouped by category.
     */
    fun getBindingsByCategory(): Map<String, List<KeyBinding>> {
        return shortcuts.values.groupBy { it.category }
    }

    /**
     * Check if a specific action is bound to any key.
     */
    fun hasBinding(actionId: String): Boolean {
        return shortcuts.containsKey(actionId) && shortcuts[actionId]?.enabled == true
    }

    /**
     * Get all action IDs that are bound.
     */
    fun getBoundActionIds(): Set<String> {
        return shortcuts.keys
    }

    /**
     * Create a modified copy with a new or updated binding.
     */
    fun withBinding(binding: KeyBinding): KeymapSettings {
        val updatedShortcuts = shortcuts.toMutableMap()
        updatedShortcuts[binding.actionId] = binding
        return copy(
            shortcuts = updatedShortcuts,
            customized = true
        )
    }

    /**
     * Create a modified copy with a binding removed.
     */
    fun withoutBinding(actionId: String): KeymapSettings {
        val updatedShortcuts = shortcuts.toMutableMap()
        updatedShortcuts.remove(actionId)
        return copy(
            shortcuts = updatedShortcuts,
            customized = true
        )
    }

    /**
     * Create a modified copy with a binding disabled.
     */
    fun withBindingDisabled(actionId: String): KeymapSettings {
        val binding = shortcuts[actionId] ?: return this
        return withBinding(binding.copy(enabled = false))
    }

    /**
     * Create a modified copy with a binding enabled.
     */
    fun withBindingEnabled(actionId: String): KeymapSettings {
        val binding = shortcuts[actionId] ?: return this
        return withBinding(binding.copy(enabled = true))
    }

    companion object {
        /**
         * Create an empty KeymapSettings with no shortcuts.
         */
        fun empty(): KeymapSettings = KeymapSettings()

        /**
         * Create a KeymapSettings from a list of bindings.
         */
        fun fromBindings(
            bindings: List<KeyBinding>,
            presetName: String = "BOSS Default",
            customized: Boolean = false
        ): KeymapSettings {
            val shortcutsMap = bindings.associateBy { it.actionId }
            return KeymapSettings(
                shortcuts = shortcutsMap,
                presetName = presetName,
                customized = customized,
                version = 1
            )
        }
    }
}
