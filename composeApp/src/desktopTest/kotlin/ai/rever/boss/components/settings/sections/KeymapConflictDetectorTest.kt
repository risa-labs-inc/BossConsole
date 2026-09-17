package ai.rever.boss.components.settings.sections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeymapConflictDetectorTest {

    @Test
    fun `canonicalCombo normalizes modifiers in sorted order`() {
        val s1 = KeyboardShortcut(
            action = "Close Tab",
            key = "W",
            modifiers = listOf("Cmd"),
            category = ShortcutCategory.TAB_MANAGEMENT,
            description = "Test",
        )

        val s2 = KeyboardShortcut(
            action = "Close Window",
            key = "W",
            modifiers = listOf("Shift", "Cmd"),
            category = ShortcutCategory.WINDOW_MANAGEMENT,
            description = "Test",
        )

        assertEquals("cmd+w", s1.canonicalCombo())
        assertEquals("cmd+shift+w", s2.canonicalCombo())
    }

    @Test
    fun `findShortcutConflicts returns empty map when no duplicate shortcuts exist`() {
        val shortcuts = listOf(
            KeyboardShortcut("A1", "T", listOf("Cmd"), ShortcutCategory.TAB_MANAGEMENT, "D1"),
            KeyboardShortcut("A2", "W", listOf("Cmd"), ShortcutCategory.TAB_MANAGEMENT, "D2"),
        )

        val conflicts = findShortcutConflicts(shortcuts)
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `findShortcutConflicts detects conflicting shortcuts sharing same key combo`() {
        val s1 = KeyboardShortcut("Action 1", "W", listOf("Cmd"), ShortcutCategory.TAB_MANAGEMENT, "D1")
        val s2 = KeyboardShortcut("Action 2", "W", listOf("Cmd"), ShortcutCategory.OTHER, "D2")

        val conflicts = findShortcutConflicts(listOf(s1, s2))

        assertEquals(1, conflicts.size)
        assertTrue(conflicts.containsKey("cmd+w"))
        assertEquals(2, conflicts["cmd+w"]?.size)
    }
}
