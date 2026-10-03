package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapHandler
import ai.rever.boss.keymap.handler.MapBasedActionExecutor
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
                description = "Test action",
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
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(binding))
        val handler = KeymapHandler.from(settings)

        assertTrue(handler.isBound("test.action"))
    }

    @Test
    fun `isBound returns false for disabled binding`() {
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = false,
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
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd", "Shift"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
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
        val binding1 =
            KeyBinding(
                actionId = "action1",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings1 = KeymapSettings.fromBindings(listOf(binding1))
        val handler = KeymapHandler.from(settings1)

        assertTrue(handler.isBound("action1"))
        assertFalse(handler.isBound("action2"))

        // Update with new settings
        val binding2 =
            KeyBinding(
                actionId = "action2",
                key = "T",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings2 = KeymapSettings.fromBindings(listOf(binding2))
        handler.updateSettings(settings2)

        assertFalse(handler.isBound("action1"))
        assertTrue(handler.isBound("action2"))
    }

    @Test
    fun `settings property returns current settings`() {
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
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

        val executor =
            MapBasedActionExecutor
                .builder()
                .on("test.action") { executed = true }
                .build()

        val result = executor.execute("test.action")

        assertTrue(result, "Execute should return true for registered action")
        assertTrue(executed, "Handler should be executed")
    }

    @Test
    fun `MapBasedActionExecutor returns false for unregistered action`() {
        val executor =
            MapBasedActionExecutor
                .builder()
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

        val executor =
            MapBasedActionExecutor
                .builder()
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

        val executor =
            MapBasedActionExecutor
                .builder()
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
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val settings = KeymapSettings.fromBindings(listOf(binding))
        val handler = KeymapHandler.from(settings)

        assertNotNull(handler)
        assertTrue(handler.isBound("test.action"))
    }

    // ==================== KEY PRESS, RELEASE & REPEAT SEMANTICS TESTS ====================

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun createKeyEvent(
        key: Key,
        type: KeyEventType,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): androidx.compose.ui.input.key.KeyEvent =
        androidx.compose.ui.input.key.KeyEvent(
            key = key,
            type = type,
            isMetaPressed = if (ai.rever.boss.utils.SystemUtils.isMacOS) meta else false,
            isCtrlPressed = if (ai.rever.boss.utils.SystemUtils.isMacOS) ctrl else (meta || ctrl),
            isShiftPressed = shift,
            isAltPressed = alt,
        )

    @Test
    fun `handleKeyEvent on KeyDown consumes event and executes the action`() {
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val handler = KeymapHandler.from(KeymapSettings.fromBindings(listOf(binding)))
        var executed = false

        val keyDown = createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true)
        val handled =
            handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL) {
                executed = true
                true
            }

        assertTrue(handled, "KeyDown matching shortcut should be consumed")
        assertTrue(executed, "KeyDown must execute the action (BossConsole#1568)")
        assertTrue(handler.hasPendingShortcut, "Handler should hold the chord until release")
    }

    @Test
    fun `handleKeyEvent on KeyUp after matching KeyDown is consumed without executing again`() {
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val handler = KeymapHandler.from(KeymapSettings.fromBindings(listOf(binding)))
        var executionCount = 0

        val keyDown = createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true)
        handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL) {
            executionCount++
            true
        }

        val keyUp = createKeyEvent(Key.N, KeyEventType.KeyUp, meta = true)
        val handled =
            handler.handleKeyEvent(keyUp, ShortcutContext.GLOBAL) {
                executionCount++
                true
            }

        assertTrue(handled, "KeyUp should be handled")
        assertEquals(1, executionCount, "Action must be executed exactly once, by the KeyDown")
        assertFalse(handler.hasPendingShortcut, "The held chord should be cleared on release")
    }

    @Test
    fun `repeated KeyDown events do not trigger multiple executions`() {
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val handler = KeymapHandler.from(KeymapSettings.fromBindings(listOf(binding)))
        var executionCount = 0

        // Simulate auto-repeat key-down events
        repeat(5) {
            val keyDown = createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true)
            val handled =
                handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL) {
                    executionCount++
                    true
                }
            assertTrue(handled, "Auto-repeat KeyDown should be consumed")
        }

        assertEquals(1, executionCount, "Only the first KeyDown may execute; repeats must not")

        // Release primary key
        val keyUp = createKeyEvent(Key.N, KeyEventType.KeyUp, meta = true)
        val handled =
            handler.handleKeyEvent(keyUp, ShortcutContext.GLOBAL) {
                executionCount++
                true
            }

        assertTrue(handled, "KeyUp should be handled")
        assertEquals(1, executionCount, "The release must not execute again")
    }

    @Test
    fun `releasing the modifier before the key neither cancels nor re-executes the chord`() {
        val binding = KeyBinding(actionId = "test.action", key = "N", modifiers = listOf("Cmd"))
        val handler = KeymapHandler.from(KeymapSettings.fromBindings(listOf(binding)))
        var executionCount = 0
        val execute: (String) -> Boolean = {
            executionCount++
            true
        }

        val keyDown = createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true)
        assertTrue(handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL, execute))
        assertEquals(1, executionCount)

        // Fast typing: Cmd comes up a few ms before N (BossConsole#1568).
        val primaryModifier = if (ai.rever.boss.utils.SystemUtils.isMacOS) Key.MetaLeft else Key.CtrlLeft
        val modifierKeyUp = createKeyEvent(primaryModifier, KeyEventType.KeyUp, meta = false)
        assertFalse(handler.handleKeyEvent(modifierKeyUp, ShortcutContext.GLOBAL, execute))
        assertTrue(handler.hasPendingShortcut, "The physical-key claim survives modifier release")

        // A bare auto-repeat of the still-held key stays swallowed and runs nothing.
        assertTrue(
            handler.handleKeyEvent(createKeyEvent(Key.N, KeyEventType.KeyDown), ShortcutContext.GLOBAL, execute),
        )
        val keyUp = createKeyEvent(Key.N, KeyEventType.KeyUp, meta = false)
        assertTrue(handler.handleKeyEvent(keyUp, ShortcutContext.GLOBAL, execute), "The release stays consumed")
        assertEquals(1, executionCount, "The chord ran exactly once")
    }

    @Test
    fun `clearPendingShortcut cancels pending shortcut on focus loss or reset`() {
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = "N",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )

        val handler = KeymapHandler.from(KeymapSettings.fromBindings(listOf(binding)))
        var executed = false

        val keyDown = createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true)
        handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL) {
            executed = true
            true
        }
        assertTrue(handler.hasPendingShortcut)

        // Focus loss or cancellation clears pending state
        handler.clearPendingShortcut()
        assertFalse(handler.hasPendingShortcut)

        // The key-down already ran the action; a key up after clearing is not claimed.
        assertTrue(executed)
        executed = false
        val keyUp = createKeyEvent(Key.N, KeyEventType.KeyUp, meta = true)
        val handled =
            handler.handleKeyEvent(keyUp, ShortcutContext.GLOBAL) {
                executed = true
                true
            }

        assertFalse(handled)
        assertFalse(executed)
    }

    @Test
    fun `updated bindings replace matcher and a context change does not re-run a held chord`() {
        val binding = KeyBinding(actionId = "test.action", key = "N", modifiers = listOf("Cmd"))
        val handler = KeymapHandler(KeymapSettings.fromBindings(listOf(binding)))
        handler.updateSettings(KeymapSettings.fromBindings(listOf(binding.copy(key = "T"))))
        var calls = 0
        val execute: (String) -> Boolean = {
            calls++
            true
        }
        assertFalse(
            handler.handleKeyEvent(
                createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.T, KeyEventType.KeyDown, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertFalse(
            handler.handleKeyEvent(
                createKeyEvent(Key.T, KeyEventType.KeyUp, meta = true),
                ShortcutContext.TERMINAL,
                execute,
            ),
            "a context change retires the old ownership record",
        )
        assertEquals(1, calls, "only the new binding's KeyDown ran it")
    }

    @Test
    fun `a new modifier combination after a lost release is matched as a new chord`() {
        val bindings =
            listOf(
                KeyBinding(actionId = "primary.action", key = "N", modifiers = listOf("Cmd")),
                KeyBinding(actionId = "alt.action", key = "N", modifiers = listOf("Alt")),
            )
        val handler = KeymapHandler(KeymapSettings.fromBindings(bindings))
        val calls = mutableListOf<String>()
        val execute: (String) -> Boolean = {
            calls.add(it)
            true
        }

        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertFalse(
            handler.handleKeyEvent(
                createKeyEvent(
                    if (ai.rever.boss.utils.SystemUtils.isMacOS) Key.MetaLeft else Key.CtrlLeft,
                    KeyEventType.KeyUp,
                ),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.N, KeyEventType.KeyDown, alt = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )

        assertEquals(listOf("primary.action", "alt.action"), calls)
    }

    @Test
    fun `an unrelated modifier release leaves the held Compose chord intact`() {
        val binding = KeyBinding(actionId = "test.action", key = "N", modifiers = listOf("Cmd"))
        val handler = KeymapHandler(KeymapSettings.fromBindings(listOf(binding)))
        var calls = 0
        val execute: (String) -> Boolean = {
            calls++
            true
        }

        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertFalse(
            handler.handleKeyEvent(
                createKeyEvent(Key.ShiftLeft, KeyEventType.KeyUp, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )

        assertEquals(1, calls)
    }

    @Test
    fun `a lock-key release cannot retire a held Compose chord`() {
        val binding = KeyBinding(actionId = "test.action", key = "N", modifiers = listOf("Cmd"))
        val handler = KeymapHandler(KeymapSettings.fromBindings(listOf(binding)))
        var calls = 0
        val execute: (String) -> Boolean = {
            calls++
            true
        }

        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertFalse(
            handler.handleKeyEvent(
                createKeyEvent(Key.CapsLock, KeyEventType.KeyUp, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )

        assertEquals(1, calls)
    }

    @Test
    fun `a chord the executor declines is attempted once and never consumed`() {
        val binding = KeyBinding(actionId = "test.action", key = "N", modifiers = listOf("Cmd"))
        val handler = KeymapHandler(KeymapSettings.fromBindings(listOf(binding)))
        var calls = 0
        val decline: (String) -> Boolean = {
            calls++
            false
        }
        val keyDown = createKeyEvent(Key.N, KeyEventType.KeyDown, meta = true)
        assertFalse(handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL, decline))
        assertTrue(handler.hasPendingShortcut, "a declined chord is retained only to identify repeats")
        repeat(3) {
            assertFalse(handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL, decline), "repeat stays unconsumed")
        }
        assertEquals(1, calls, "OS repeats must not retry an action the executor already declined")
        val keyUp = createKeyEvent(Key.N, KeyEventType.KeyUp, meta = true)
        assertFalse(handler.handleKeyEvent(keyUp, ShortcutContext.GLOBAL, decline))
        assertFalse(handler.hasPendingShortcut)
    }

    @Test
    fun `Compose browser print runs on KeyDown once and never on release`() {
        val binding = KeyBinding(actionId = KeymapActions.BROWSER_PRINT, key = "P", modifiers = listOf("Cmd"))
        val handler = KeymapHandler(KeymapSettings.fromBindings(listOf(binding)))
        var calls = 0
        val execute: (String) -> Boolean = {
            calls++
            true
        }
        val keyDown = createKeyEvent(Key.P, KeyEventType.KeyDown, meta = true)

        assertTrue(handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL, execute))
        repeat(3) { assertTrue(handler.handleKeyEvent(keyDown, ShortcutContext.GLOBAL, execute)) }
        assertEquals(1, calls)
        assertTrue(
            handler.handleKeyEvent(
                createKeyEvent(Key.P, KeyEventType.KeyUp, meta = true),
                ShortcutContext.GLOBAL,
                execute,
            ),
        )
        assertEquals(1, calls, "release only closes ownership; AWT owns native print coordination")
    }

    @Test
    fun `overlapping Compose chords retain each action and suppress repeats`() {
        val bindings = listOf("N", "T").map { KeyBinding(actionId = it, key = it, modifiers = listOf("Cmd")) }
        val handler = KeymapHandler(KeymapSettings.fromBindings(bindings))
        val calls = mutableListOf<String>()
        val execute: (String) -> Boolean = {
            calls.add(it)
            true
        }
        for (key in listOf(Key.N, Key.T, Key.N)) {
            assertTrue(
                handler.handleKeyEvent(
                    createKeyEvent(key, KeyEventType.KeyDown, meta = true),
                    ShortcutContext.GLOBAL,
                    execute,
                ),
            )
        }
        assertEquals(listOf("N", "T"), calls, "each chord runs on its own KeyDown; the repeat N does not")
        for (key in listOf(Key.N, Key.T)) {
            assertTrue(
                handler.handleKeyEvent(
                    createKeyEvent(key, KeyEventType.KeyUp, meta = true),
                    ShortcutContext.GLOBAL,
                    execute,
                ),
            )
        }
        assertEquals(listOf("N", "T"), calls)
    }
}
