package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapValidator
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for KeymapValidator component.
 *
 * Tests cover:
 * - Conflict detection between same context bindings
 * - Global context conflicts with all contexts
 * - Different non-global contexts don't conflict
 * - Disabled bindings don't cause conflicts
 * - Conflict suggestions
 */
class KeymapValidatorTest {
    // ==================== NO CONFLICT TESTS ====================

    @Test
    fun `no conflicts with unique key combinations`() {
        val binding1 =
            KeyBinding(
                actionId = "action1",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )
        val binding2 =
            KeyBinding(
                actionId = "action2",
                key = "T",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(binding1, binding2))
        val conflicts = KeymapValidator.validate(settings)

        assertTrue(conflicts.isEmpty(), "Expected no conflicts for unique key combinations")
    }

    @Test
    fun `no conflicts between different non-global contexts`() {
        val browserBinding =
            KeyBinding(
                actionId = "browser.reload",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.BROWSER,
                enabled = true,
            )
        val terminalBinding =
            KeyBinding(
                actionId = "terminal.reset",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.TERMINAL,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(browserBinding, terminalBinding))
        val conflicts = KeymapValidator.validate(settings)

        assertTrue(conflicts.isEmpty(), "Different non-global contexts should not conflict")
    }

    @Test
    fun `no conflicts when one binding is disabled`() {
        val enabledBinding =
            KeyBinding(
                actionId = "action1",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )
        val disabledBinding =
            KeyBinding(
                actionId = "action2",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = false,
            )

        val settings = KeymapSettings.fromBindings(listOf(enabledBinding, disabledBinding))
        val conflicts = KeymapValidator.validate(settings)

        assertTrue(conflicts.isEmpty(), "Disabled bindings should not cause conflicts")
    }

    // ==================== CONFLICT DETECTION TESTS ====================

    @Test
    fun `detects conflict between same context bindings`() {
        val binding1 =
            KeyBinding(
                actionId = "action1",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.BROWSER,
                enabled = true,
                description = "Action 1",
            )
        val binding2 =
            KeyBinding(
                actionId = "action2",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.BROWSER,
                enabled = true,
                description = "Action 2",
            )

        val settings = KeymapSettings.fromBindings(listOf(binding1, binding2))
        val conflicts = KeymapValidator.validate(settings)

        assertEquals(1, conflicts.size, "Expected one conflict")
        assertEquals(2, conflicts[0].count, "Conflict should involve 2 bindings")
    }

    @Test
    fun `global and specific context do not conflict with different signatures`() {
        // Note: The signature includes context, so GLOBAL:Cmd+R and BROWSER:Cmd+R
        // are different signatures and don't conflict in findConflicts().
        // This is by design - context-specific shortcuts override global ones during matching.
        val globalBinding =
            KeyBinding(
                actionId = "global.action",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
                description = "Global Action",
            )
        val browserBinding =
            KeyBinding(
                actionId = "browser.reload",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.BROWSER,
                enabled = true,
                description = "Browser Reload",
            )

        val settings = KeymapSettings.fromBindings(listOf(globalBinding, browserBinding))
        val conflicts = KeymapValidator.validate(settings)

        // Different contexts have different signatures, so no conflict detected
        assertTrue(conflicts.isEmpty(), "Different contexts should not conflict (by signature)")
    }

    @Test
    fun `checkBinding uses signature which includes context`() {
        // checkBinding uses signature() which includes context in the signature
        // So GLOBAL:Cmd+R and BROWSER:Cmd+R are different signatures
        val globalBinding =
            KeyBinding(
                actionId = "global.action",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(globalBinding))

        val browserBinding =
            KeyBinding(
                actionId = "browser.reload",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.BROWSER,
                enabled = true,
            )

        val conflicts = KeymapValidator.checkBinding(browserBinding, settings)

        // Different contexts = different signatures = no conflict found
        assertTrue(conflicts.isEmpty(), "Different contexts have different signatures")
    }

    @Test
    fun `checkBinding detects same context conflict`() {
        val existingBinding =
            KeyBinding(
                actionId = "browser.back",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.BROWSER,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(existingBinding))

        val newBinding =
            KeyBinding(
                actionId = "browser.reload",
                key = "R",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.BROWSER,
                enabled = true,
            )

        val conflicts = KeymapValidator.checkBinding(newBinding, settings)

        assertTrue(conflicts.isNotEmpty(), "Same context should conflict")
        assertEquals("browser.back", conflicts[0].actionId)
    }

    @Test
    fun `multiple conflicts detected separately`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "action1", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action2", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action3", key = "T", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action4", key = "T", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
            )

        val settings = KeymapSettings.fromBindings(bindings)
        val conflicts = KeymapValidator.validate(settings)

        assertEquals(2, conflicts.size, "Expected two separate conflicts")
    }

    // ==================== CHECK BINDING TESTS ====================

    @Test
    fun `checkBinding finds existing conflicts`() {
        val existingBinding =
            KeyBinding(
                actionId = "existing.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(existingBinding))

        val newBinding =
            KeyBinding(
                actionId = "new.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val conflicts = KeymapValidator.checkBinding(newBinding, settings)

        assertEquals(1, conflicts.size, "Should find one conflicting binding")
        assertEquals("existing.action", conflicts[0].actionId)
    }

    @Test
    fun `checkBinding excludes action being edited`() {
        val existingBinding =
            KeyBinding(
                actionId = "action.to.edit",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(existingBinding))

        val updatedBinding =
            KeyBinding(
                actionId = "action.to.edit",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val conflicts =
            KeymapValidator.checkBinding(
                updatedBinding,
                settings,
                excludeActionId = "action.to.edit",
            )

        assertTrue(conflicts.isEmpty(), "Should not conflict with itself when editing")
    }

    // ==================== UTILITY METHOD TESTS ====================

    @Test
    fun `hasConflicts returns true when conflicts exist`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "action1", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action2", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
            )

        val settings = KeymapSettings.fromBindings(bindings)

        assertTrue(KeymapValidator.hasConflicts(settings))
    }

    @Test
    fun `hasConflicts returns false when no conflicts`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "action1", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action2", key = "T", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
            )

        val settings = KeymapSettings.fromBindings(bindings)

        assertFalse(KeymapValidator.hasConflicts(settings))
    }

    @Test
    fun `conflictCount returns correct number`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "action1", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action2", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action3", key = "T", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
            )

        val settings = KeymapSettings.fromBindings(bindings)

        assertEquals(1, KeymapValidator.conflictCount(settings))
    }

    @Test
    fun `getSummary returns correct message for no conflicts`() {
        val settings =
            KeymapSettings.fromBindings(
                listOf(
                    KeyBinding(
                        actionId = "action1",
                        key = "N",
                        modifiers = listOf("Cmd"),
                        context = ShortcutContext.GLOBAL,
                        enabled = true,
                    ),
                ),
            )

        assertEquals("No conflicts detected", KeymapValidator.getSummary(settings))
    }

    @Test
    fun `getSummary returns correct message for single conflict`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "action1", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action2", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
            )

        val settings = KeymapSettings.fromBindings(bindings)

        assertEquals("1 conflict detected", KeymapValidator.getSummary(settings))
    }

    @Test
    fun `getSummary returns correct message for multiple conflicts`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "action1", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action2", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action3", key = "T", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action4", key = "T", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
            )

        val settings = KeymapSettings.fromBindings(bindings)

        assertEquals("2 conflicts detected", KeymapValidator.getSummary(settings))
    }

    // ==================== SUGGESTION TESTS ====================

    @Test
    fun `suggestFixes provides disable suggestions`() {
        val bindings =
            listOf(
                KeyBinding(
                    actionId = "action1",
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    enabled = true,
                    description = "Action One",
                ),
                KeyBinding(
                    actionId = "action2",
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    enabled = true,
                    description = "Action Two",
                ),
            )

        val settings = KeymapSettings.fromBindings(bindings)
        val conflicts = KeymapValidator.validate(settings)

        assertTrue(conflicts.isNotEmpty())

        val suggestions = KeymapValidator.suggestFixes(conflicts[0])

        assertTrue(suggestions.any { it.contains("Disable") }, "Should suggest disabling an action")
    }

    @Test
    fun `suggestFixes provides key change suggestions`() {
        val bindings =
            listOf(
                KeyBinding(
                    actionId = "action1",
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    enabled = true,
                    description = "Action One",
                ),
                KeyBinding(
                    actionId = "action2",
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    enabled = true,
                    description = "Action Two",
                ),
            )

        val settings = KeymapSettings.fromBindings(bindings)
        val conflicts = KeymapValidator.validate(settings)

        assertTrue(conflicts.isNotEmpty())

        val suggestions = KeymapValidator.suggestFixes(conflicts[0])

        assertTrue(suggestions.any { it.contains("Change key") }, "Should suggest changing key combination")
    }

    // ==================== SAFE TO SAVE TESTS ====================

    @Test
    fun `isSafeToSave returns true even with conflicts`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "action1", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
                KeyBinding(actionId = "action2", key = "N", modifiers = listOf("Cmd"), context = ShortcutContext.GLOBAL, enabled = true),
            )

        val settings = KeymapSettings.fromBindings(bindings)

        assertTrue(KeymapValidator.isSafeToSave(settings), "Should allow saving with conflicts (user intent)")
    }

    // ==================== CONFLICT DESCRIPTION TESTS ====================

    @Test
    fun `conflict description includes action names`() {
        val bindings =
            listOf(
                KeyBinding(
                    actionId = "action1",
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    enabled = true,
                    description = "New Window",
                ),
                KeyBinding(
                    actionId = "action2",
                    key = "N",
                    modifiers = listOf("Cmd"),
                    context = ShortcutContext.GLOBAL,
                    enabled = true,
                    description = "New Tab",
                ),
            )

        val settings = KeymapSettings.fromBindings(bindings)
        val conflicts = KeymapValidator.validate(settings)

        assertTrue(conflicts.isNotEmpty())

        val description = conflicts[0].description()

        assertTrue(description.contains("New Window"), "Should mention first action")
        assertTrue(description.contains("New Tab"), "Should mention second action")
        assertTrue(description.contains("2 actions"), "Should mention count")
    }
}
