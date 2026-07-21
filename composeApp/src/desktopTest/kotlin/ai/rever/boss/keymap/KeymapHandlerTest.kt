package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapHandler
import ai.rever.boss.keymap.handler.MapBasedActionExecutor
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for KeymapHandler component.
 *
 * Tests cover:
 * - Binding retrieval
 * - Display string generation
 * - Settings update
 * - Context determination
 * - MapBasedActionExecutor
 */
class KeymapHandlerTest {

    // ==================== BINDING RETRIEVAL TESTS ====================

    @Test
    fun `getBinding returns correct binding`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true,
            description = "Test action"
        )

        val settings = KeymapSettings.fromBindings(listOf(binding))
        val handler = KeymapHandler.from(settings)

        val retrieved = handler.getBinding("test.action")

        assertNotNull(retrieved)
        assertEquals("test.action", retrieved.actionId)
        assertEquals("N", retrieved.key)
    }

    @Test
    fun `getBinding returns null for nonexistent action`() {
        val settings = KeymapSettings.fromBindings(emptyList())
        val handler = KeymapHandler.from(settings)

        val retrieved = handler.getBinding("nonexistent.action")

        assertNull(retrieved)
    }

    @Test
    fun `isBound returns true for bound action`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val settings = KeymapSettings.fromBindings(listOf(binding))
        val handler = KeymapHandler.from(settings)

        assertTrue(handler.isBound("test.action"))
    }

    @Test
    fun `isBound returns false for disabled binding`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = false
        )

        val settings = KeymapSettings.fromBindings(listOf(binding))
        val handler = KeymapHandler.from(settings)

        assertFalse(handler.isBound("test.action"))
    }

    @Test
    fun `isBound returns false for unbound action`() {
        val settings = KeymapSettings.fromBindings(emptyList())
        val handler = KeymapHandler.from(settings)

        assertFalse(handler.isBound("nonexistent.action"))
    }

    // ==================== DISPLAY STRING TESTS ====================

    @Test
    fun `getDisplayString returns formatted string for bound action`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd", "Shift"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val settings = KeymapSettings.fromBindings(listOf(binding))
        val handler = KeymapHandler.from(settings)

        val displayString = handler.getDisplayString("test.action")

        assertNotNull(displayString)
        // The exact format depends on the platform, but it should contain N
        assertTrue(displayString.contains("N"))
    }

    @Test
    fun `getDisplayString returns null for unbound action`() {
        val settings = KeymapSettings.fromBindings(emptyList())
        val handler = KeymapHandler.from(settings)

        val displayString = handler.getDisplayString("nonexistent.action")

        assertNull(displayString)
    }

    // ==================== SETTINGS UPDATE TESTS ====================

    @Test
    fun `updateSettings changes handler settings`() {
        val binding1 = KeyBinding(
            actionId = "action1",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val settings1 = KeymapSettings.fromBindings(listOf(binding1))
        val handler = KeymapHandler.from(settings1)

        assertTrue(handler.isBound("action1"))
        assertFalse(handler.isBound("action2"))

        // Update with new settings
        val binding2 = KeyBinding(
            actionId = "action2",
            key = "T",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val settings2 = KeymapSettings.fromBindings(listOf(binding2))
        handler.updateSettings(settings2)

        assertFalse(handler.isBound("action1"))
        assertTrue(handler.isBound("action2"))
    }

    @Test
    fun `settings property returns current settings`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val settings = KeymapSettings.fromBindings(listOf(binding), presetName = "Test Preset")
        val handler = KeymapHandler.from(settings)

        assertEquals("Test Preset", handler.settings.presetName)
    }

    // ==================== CONTEXT DETERMINATION TESTS ====================

    @Test
    fun `determineContext returns BROWSER for fluck component`() {
        val context = KeymapHandler.determineContext("fluck")
        assertEquals(ShortcutContext.BROWSER, context)
    }

    @Test
    fun `determineContext returns BROWSER for browser component`() {
        val context = KeymapHandler.determineContext("browser")
        assertEquals(ShortcutContext.BROWSER, context)
    }

    @Test
    fun `determineContext returns TERMINAL for terminal component`() {
        val context = KeymapHandler.determineContext("terminal")
        assertEquals(ShortcutContext.TERMINAL, context)
    }

    @Test
    fun `determineContext returns EDITOR for editor component`() {
        val context = KeymapHandler.determineContext("editor")
        assertEquals(ShortcutContext.EDITOR, context)
    }

    @Test
    fun `determineContext returns EDITOR for code component`() {
        val context = KeymapHandler.determineContext("code")
        assertEquals(ShortcutContext.EDITOR, context)
    }

    @Test
    fun `determineContext returns WORKSPACE for workspace component`() {
        val context = KeymapHandler.determineContext("workspace")
        assertEquals(ShortcutContext.WORKSPACE, context)
    }

    @Test
    fun `determineContext returns GLOBAL for unknown component`() {
        val context = KeymapHandler.determineContext("unknown")
        assertEquals(ShortcutContext.GLOBAL, context)
    }

    @Test
    fun `determineContext returns GLOBAL for null component`() {
        val context = KeymapHandler.determineContext(null)
        assertEquals(ShortcutContext.GLOBAL, context)
    }

    // ==================== MAP BASED EXECUTOR TESTS ====================

    @Test
    fun `MapBasedActionExecutor executes registered handler`() {
        var executed = false

        val executor = MapBasedActionExecutor.builder()
            .on("test.action") { executed = true }
            .build()

        val result = executor.execute("test.action")

        assertTrue(result, "Execute should return true for registered action")
        assertTrue(executed, "Handler should be executed")
    }

    @Test
    fun `MapBasedActionExecutor returns false for unregistered action`() {
        val executor = MapBasedActionExecutor.builder()
            .on("test.action") { }
            .build()

        val result = executor.execute("unknown.action")

        assertFalse(result, "Execute should return false for unregistered action")
    }

    @Test
    fun `MapBasedActionExecutor builder chains correctly`() {
        var action1Executed = false
        var action2Executed = false
        var action3Executed = false

        val executor = MapBasedActionExecutor.builder()
            .on("action1") { action1Executed = true }
            .on("action2") { action2Executed = true }
            .on("action3") { action3Executed = true }
            .build()

        executor.execute("action1")
        executor.execute("action2")
        executor.execute("action3")

        assertTrue(action1Executed)
        assertTrue(action2Executed)
        assertTrue(action3Executed)
    }

    @Test
    fun `MapBasedActionExecutor handles multiple executions`() {
        var counter = 0

        val executor = MapBasedActionExecutor.builder()
            .on("increment") { counter++ }
            .build()

        executor.execute("increment")
        executor.execute("increment")
        executor.execute("increment")

        assertEquals(3, counter)
    }

    // ==================== FACTORY METHOD TESTS ====================

    @Test
    fun `from factory creates handler with settings`() {
        val binding = KeyBinding(
            actionId = "test.action",
            key = "N",
            modifiers = listOf("Cmd"),
            context = ShortcutContext.GLOBAL,
            enabled = true
        )

        val settings = KeymapSettings.fromBindings(listOf(binding))
        val handler = KeymapHandler.from(settings)

        assertNotNull(handler)
        assertTrue(handler.isBound("test.action"))
    }
}
