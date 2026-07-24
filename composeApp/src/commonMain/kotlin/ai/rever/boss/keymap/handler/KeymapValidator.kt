package ai.rever.boss.keymap.handler

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext

/**
 * Data class representing a keyboard shortcut conflict.
 */
data class KeymapConflict(
    val signature: String,
    val bindings: List<KeyBinding>,
) {
    val count: Int get() = bindings.size

    /**
     * Get a human-readable description of this conflict.
     */
    fun description(): String {
        val actions = bindings.joinToString(", ") { "\"${it.description}\"" }
        return "Key combination ${bindings.first().displayString()} is bound to $count actions: $actions"
    }
}

/**
 * Validates keyboard shortcut configurations and detects conflicts.
 */
object KeymapValidator {
    /**
     * Validate a keymap settings object and return list of conflicts.
     * Empty list means no conflicts.
     */
    fun validate(settings: KeymapSettings): List<KeymapConflict> = findConflicts(settings)

    /**
     * Check if a specific key binding would conflict with existing bindings.
     * Returns list of conflicting bindings (empty if no conflicts).
     */
    fun checkBinding(
        binding: KeyBinding,
        settings: KeymapSettings,
        excludeActionId: String? = null,
    ): List<KeyBinding> {
        val signature = binding.signature()

        return settings.shortcuts.values
            .filter { it.actionId != excludeActionId } // Exclude binding being edited
            .filter { it.enabled && binding.enabled } // Both must be enabled
            .filter { it.signature() == signature } // Same key combination
            .filter { contextsConflict(it.context, binding.context) } // Conflicting contexts
    }

    /**
     * Check if a keymap settings object has any conflicts.
     */
    fun hasConflicts(settings: KeymapSettings): Boolean = findConflicts(settings).isNotEmpty()

    /**
     * Get count of conflicts in a keymap settings object.
     */
    fun conflictCount(settings: KeymapSettings): Int = findConflicts(settings).size

    /**
     * Find all conflicts in a keymap settings object.
     */
    private fun findConflicts(settings: KeymapSettings): List<KeymapConflict> {
        val conflicts = mutableMapOf<String, MutableList<KeyBinding>>()

        // Group enabled bindings by signature
        settings.shortcuts.values
            .filter { it.enabled }
            .forEach { binding ->
                val signature = binding.signature()
                conflicts.getOrPut(signature) { mutableListOf() }.add(binding)
            }

        // Filter to only entries with conflicts (2+ bindings)
        // and where contexts actually conflict
        return conflicts
            .filter { (_, bindings) -> bindings.size > 1 }
            .filter { (_, bindings) -> hasContextConflict(bindings) }
            .map { (signature, bindings) ->
                KeymapConflict(signature, bindings)
            }
    }

    /**
     * Check if a list of bindings has any context conflicts.
     */
    private fun hasContextConflict(bindings: List<KeyBinding>): Boolean {
        // If any binding is GLOBAL, it conflicts with everything
        if (bindings.any { it.context == ShortcutContext.GLOBAL }) {
            return bindings.size > 1
        }

        // Otherwise, bindings only conflict if they share the exact same context
        val contextGroups = bindings.groupBy { it.context }
        return contextGroups.any { (_, group) -> group.size > 1 }
    }

    /**
     * Check if two contexts would conflict.
     * GLOBAL conflicts with everything.
     * Same contexts conflict with each other.
     * Different non-global contexts don't conflict.
     */
    private fun contextsConflict(
        context1: ShortcutContext,
        context2: ShortcutContext,
    ): Boolean =
        when {
            context1 == ShortcutContext.GLOBAL || context2 == ShortcutContext.GLOBAL -> true
            context1 == context2 -> true
            else -> false
        }

    /**
     * Suggest fixes for conflicts.
     */
    fun suggestFixes(conflict: KeymapConflict): List<String> {
        val suggestions = mutableListOf<String>()

        // Suggest disabling actions
        conflict.bindings.forEach { binding ->
            suggestions.add("Disable \"${binding.description}\"")
        }

        // Suggest changing keys
        conflict.bindings.forEach { binding ->
            suggestions.add("Change key combination for \"${binding.description}\"")
        }

        // Suggest context restriction
        if (conflict.bindings.any { it.context == ShortcutContext.GLOBAL }) {
            suggestions.add("Restrict global shortcut to specific context")
        }

        return suggestions
    }

    /**
     * Check if settings are safe to save (no critical conflicts).
     * Currently, all conflicts are considered warnings, not errors.
     */
    fun isSafeToSave(settings: KeymapSettings): Boolean {
        // For now, allow saving even with conflicts
        // Users might want to have intentional conflicts
        return true
    }

    /**
     * Get a summary string for validation results.
     */
    fun getSummary(settings: KeymapSettings): String {
        val conflicts = findConflicts(settings)
        return when {
            conflicts.isEmpty() -> "No conflicts detected"
            conflicts.size == 1 -> "1 conflict detected"
            else -> "${conflicts.size} conflicts detected"
        }
    }
}
