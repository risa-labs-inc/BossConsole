package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.ShortcutContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for KeymapMatcher component.
 *
 * Tests cover:
 * - Context-aware matching (context-specific > workspace > global)
 * - Modifier key matching
 * - Key name normalization
 * - Platform-specific modifier handling
 */
class KeymapMatcherTest {

    // ==================== CONTEXT PRIORITY TESTS ====================

    @Test
    fun `context-specific binding takes priority over global binding`() {
        val browserBinding = KeyBinding(
            actionId = "browser.reload",
            key = "R",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.BROWSER,
            enabled = true,
            description = "Reload browser"
        )
        val globalBinding = KeyBinding(
            actionId = "global.action",
            key = "R",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true,
            description = "Global action"
        )

        val settings = KeymapSettings.fromBindings(listOf(browserBinding, globalBinding))
        val matcher = KeymapMatcher.from(settings)

        // In browser context, browser binding should be returned
        val bindings = settings.getBindingsForContext(ShortcutContext.BROWSER)
        assertEquals(1, bindings.size)
        assertEquals("browser.reload", bindings[0].actionId)
    }

    @Test
    fun `global binding matches when no context-specific binding exists`() {
        val globalBinding = KeyBinding(
            actionId = "global.settings",
            key = "Comma",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true,
            description = "Open settings"
        )

        val settings = KeymapSettings.fromBindings(listOf(globalBinding))

        // GLOBAL binding should be accessible
        val binding = settings.getBinding("global.settings")
        assertNotNull(binding)
        assertEquals(ShortcutContext.GLOBAL, binding.context)
    }

    @Test
    fun `workspace binding is checked when context-specific not found`() {
        val workspaceBinding = KeyBinding(
            actionId = "workspace.save",
            key = "S",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.WORKSPACE,
            enabled = true,
            description = "Save workspace"
        )

        val settings = KeymapSettings.fromBindings(listOf(workspaceBinding))

        val binding = settings.getBinding("workspace.save")
        assertNotNull(binding)
        assertEquals(ShortcutContext.WORKSPACE, binding.context)
    }

    // ==================== ENABLED STATE TESTS ====================

    @Test
    fun `disabled binding is not matched`() {
        val disabledBinding = KeyBinding(
            actionId = "test.action",
            key = "T",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = false,
            description = "Test action"
        )

        val settings = KeymapSettings.fromBindings(listOf(disabledBinding))

        // hasBinding should return false for disabled bindings
        assertEquals(false, settings.hasBinding("test.action"))
    }

    @Test
    fun `enabled binding is matched`() {
        val enabledBinding = KeyBinding(
            actionId = "test.action",
            key = "T",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true,
            description = "Test action"
        )

        val settings = KeymapSettings.fromBindings(listOf(enabledBinding))

        assertEquals(true, settings.hasBinding("test.action"))
    }

    // ==================== DISPLAY STRING TESTS ====================

    @Test
    fun `display string formats correctly on macOS`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd", "Shift"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val display = binding.displayString("Mac OS X")
        // macOS uses symbols without separators
        assertEquals("⌘⇧N", display)
    }

    @Test
    fun `display string formats correctly on Windows`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd", "Shift"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val display = binding.displayString("Windows 10")
        // Windows uses text with + separators
        assertEquals("Ctrl+Shift+N", display)
    }

    @Test
    fun `display string formats arrow keys correctly`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "ArrowLeft",
            modifiers = listOf("Alt"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val display = binding.displayString("Mac OS X")
        assertEquals("⌥←", display)
    }

    @Test
    fun `display string formats special keys correctly`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "Enter",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val display = binding.displayString("Mac OS X")
        assertEquals("⌘↩", display)
    }

    // ==================== SIGNATURE TESTS ====================

    @Test
    fun `signature includes context and modifiers`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "n",
            modifiers = listOf("Cmd", "Shift"),
            context = ShortcutContext.BROWSER,
            enabled = true
        )

        val signature = binding.signature()
        // Should be sorted modifiers + uppercase key
        assertEquals("BROWSER:Cmd+Shift+N", signature)
    }

    @Test
    fun `signature without modifiers formats correctly`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "Escape",
            modifiers = emptyList(),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val signature = binding.signature()
        assertEquals("GLOBAL:ESCAPE", signature)
    }

    // ==================== MATCHES TESTS ====================

    @Test
    fun `matches returns true for matching key and modifiers`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val result = binding.matches(
            eventKey = "N",
            isMetaPressed = true,
            isCtrlPressed = false,
            isShiftPressed = false,
            isAltPressed = false
        )

        assertEquals(true, result)
    }

    @Test
    fun `matches returns false for wrong key`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val result = binding.matches(
            eventKey = "M",
            isMetaPressed = true,
            isCtrlPressed = false,
            isShiftPressed = false,
            isAltPressed = false
        )

        assertEquals(false, result)
    }

    @Test
    fun `matches returns false for disabled binding`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = false
        )

        val result = binding.matches(
            eventKey = "N",
            isMetaPressed = true,
            isCtrlPressed = false,
            isShiftPressed = false,
            isAltPressed = false
        )

        assertEquals(false, result)
    }

    // ==================== SETTINGS OPERATIONS TESTS ====================

    @Test
    fun `getBindingsForContext returns only matching context`() {
        val browserBinding = KeyBinding(
            actionId = "browser.action",
            key = "R",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.BROWSER,
            enabled = true
        )
        val terminalBinding = KeyBinding(
            actionId = "terminal.action",
            key = "T",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.TERMINAL,
            enabled = true
        )

        val settings = KeymapSettings.fromBindings(listOf(browserBinding, terminalBinding))

        val browserBindings = settings.getBindingsForContext(ShortcutContext.BROWSER)
        assertEquals(1, browserBindings.size)
        assertEquals("browser.action", browserBindings[0].actionId)
    }

    @Test
    fun `getEnabledBindings filters out disabled`() {
        val enabledBinding = KeyBinding(
            actionId = "enabled.action",
            key = "E",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )
        val disabledBinding = KeyBinding(
            actionId = "disabled.action",
            key = "D",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = false
        )

        val settings = KeymapSettings.fromBindings(listOf(enabledBinding, disabledBinding))

        val enabled = settings.getEnabledBindings()
        assertEquals(1, enabled.size)
        assertEquals("enabled.action", enabled[0].actionId)
    }

    @Test
    fun `getBindingsByCategory groups correctly`() {
        val windowBinding = KeyBinding(
            actionId = "window.new",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true,
            category = "Window"
        )
        val tabBinding = KeyBinding(
            actionId = "tab.new",
            key = "T",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true,
            category = "Tabs"
        )

        val settings = KeymapSettings.fromBindings(listOf(windowBinding, tabBinding))

        val byCategory = settings.getBindingsByCategory()
        assertEquals(2, byCategory.size)
        assertEquals(1, byCategory["Window"]?.size)
        assertEquals(1, byCategory["Tabs"]?.size)
    }

    // ==================== KEYSTROKE TESTS ====================

    @Test
    fun `KeyStroke displayString formats correctly on macOS`() {
        val keystroke = KeyStroke("N", listOf("Cmd", "Shift"))
        assertEquals("⌘⇧N", keystroke.displayString("Mac OS X"))
    }

    @Test
    fun `KeyStroke displayString formats correctly on Windows`() {
        val keystroke = KeyStroke("N", listOf("Cmd", "Shift"))
        assertEquals("Ctrl+Shift+N", keystroke.displayString("Windows 10"))
    }

    @Test
    fun `KeyStroke signature formats correctly`() {
        val keystroke = KeyStroke("N", listOf("Shift", "Cmd"))
        // Modifiers should be sorted
        assertEquals("Cmd+Shift+N", keystroke.signature())
    }

    @Test
    fun `KeyStroke of factory creates correctly`() {
        val keystroke = KeyStroke.of("C", "Cmd", "Shift")
        assertEquals("C", keystroke.key)
        assertEquals(listOf("Cmd", "Shift"), keystroke.modifiers)
    }

    @Test
    fun `KeyStroke matches returns true for matching event`() {
        val keystroke = KeyStroke("N", listOf("Cmd"))
        assertTrue(keystroke.matches("N", isMetaPressed = true, isCtrlPressed = false, isShiftPressed = false, isAltPressed = false))
    }

    @Test
    fun `KeyStroke matches returns false for non-matching event`() {
        val keystroke = KeyStroke("N", listOf("Cmd"))
        assertFalse(keystroke.matches("M", isMetaPressed = true, isCtrlPressed = false, isShiftPressed = false, isAltPressed = false))
    }

    // ==================== MULTI-KEYSTROKE TESTS ====================

    @Test
    fun `binding with alternate keystrokes has correct allKeystrokes`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("C", listOf("Ctrl"))),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        assertEquals(2, binding.allKeystrokes.size)
        assertEquals("C", binding.allKeystrokes[0].key)
        assertEquals(listOf("Cmd"), binding.allKeystrokes[0].modifiers)
        assertEquals("C", binding.allKeystrokes[1].key)
        assertEquals(listOf("Ctrl"), binding.allKeystrokes[1].modifiers)
    }

    @Test
    fun `binding matches primary keystroke`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("C", listOf("Ctrl"))),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        assertTrue(binding.matches("C", isMetaPressed = true, isCtrlPressed = false, isShiftPressed = false, isAltPressed = false))
    }

    @Test
    fun `binding matches alternate keystroke`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("C", listOf("Ctrl"))),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        assertTrue(binding.matches("C", isMetaPressed = false, isCtrlPressed = true, isShiftPressed = false, isAltPressed = false))
    }

    @Test
    fun `binding displayStringAll shows all keystrokes`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("C", listOf("Ctrl"))),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val display = binding.displayStringAll("Mac OS X")
        assertTrue(display.contains("⌘C"))
        assertTrue(display.contains("⌃C"))
        assertTrue(display.contains(" / "))
    }

    @Test
    fun `binding allSignatures returns all signatures`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("C", listOf("Ctrl"))),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val signatures = binding.allSignatures()
        assertEquals(2, signatures.size)
        assertTrue(signatures.contains("GLOBAL:Cmd+C"))
        assertTrue(signatures.contains("GLOBAL:Ctrl+C"))
    }

    @Test
    fun `withAlternateKeystroke adds new alternate`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val updated = binding.withAlternateKeystroke("C", "Ctrl")
        assertEquals(1, updated.alternateKeystrokes.size)
        assertEquals("C", updated.alternateKeystrokes[0].key)
        assertEquals(listOf("Ctrl"), updated.alternateKeystrokes[0].modifiers)
    }

    @Test
    fun `withoutAlternateKeystroke removes alternate`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("C", listOf("Ctrl"))),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val updated = binding.withoutAlternateKeystroke(KeyStroke("C", listOf("Ctrl")))
        assertTrue(updated.alternateKeystrokes.isEmpty())
    }

    @Test
    fun `clearAlternateKeystrokes removes all alternates`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(
                KeyStroke("C", listOf("Ctrl")),
                KeyStroke("Insert", listOf("Ctrl"))
            ),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val updated = binding.clearAlternateKeystrokes()
        assertTrue(updated.alternateKeystrokes.isEmpty())
    }

    @Test
    fun `hasAlternates returns true when alternates exist`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("C", listOf("Ctrl"))),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        assertTrue(binding.hasAlternates)
    }

    @Test
    fun `hasAlternates returns false when no alternates`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "C",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        assertFalse(binding.hasAlternates)
    }

    @Test
    fun `crossPlatform creates binding with Cmd and Ctrl alternates`() {
        val binding = KeyBinding.crossPlatform(
            actionId = "copy",
            key = "C",
            context = ShortcutContext.GLOBAL,
            description = "Copy"
        )

        assertEquals("C", binding.key)
        assertEquals(listOf("Cmd"), binding.modifiers)
        assertEquals(1, binding.alternateKeystrokes.size)
        assertEquals("C", binding.alternateKeystrokes[0].key)
        assertEquals(listOf("Ctrl"), binding.alternateKeystrokes[0].modifiers)
    }

    @Test
    fun `crossPlatform with additional modifiers works correctly`() {
        val binding = KeyBinding.crossPlatform(
            actionId = "copy.special",
            key = "C",
            "Shift",
            context = ShortcutContext.GLOBAL,
            description = "Copy special"
        )

        assertEquals(listOf("Cmd", "Shift"), binding.modifiers)
        assertEquals(listOf("Ctrl", "Shift"), binding.alternateKeystrokes[0].modifiers)
    }
}
