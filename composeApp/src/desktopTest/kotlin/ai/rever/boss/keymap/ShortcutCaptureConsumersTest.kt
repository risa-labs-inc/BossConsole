package ai.rever.boss.keymap

import ai.rever.boss.components.settings.keymap.isShortcutCaptureKey
import ai.rever.boss.keymap.menu.MenuShortcutBridge
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.formatShortcutLabel
import ai.rever.boss.keymap.model.storedKeyName
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShortcutCaptureConsumersTest {
    @Test
    fun `home and search labels render captured keys like settings`() {
        listOf(Key.N, Key.DirectionLeft, Key.LeftBracket, Key.One).forEach { key ->
            val name = storedKeyName(key)
            val modifiers = listOf("Cmd")
            assertEquals(KeyStroke(name, modifiers).displayString(), formatShortcutLabel(modifiers, name))
        }
        assertEquals(formatShortcutLabel(listOf("Cmd"), "N"), formatShortcutLabel(listOf("Cmd"), "n"))
    }

    @Test
    fun `capture rejects modifier keys but accepts ordinary keys`() {
        listOf(
            Key.MetaLeft,
            Key.MetaRight,
            Key.CtrlLeft,
            Key.CtrlRight,
            Key.AltLeft,
            Key.AltRight,
            Key.ShiftLeft,
            Key.ShiftRight,
            Key.CapsLock,
            Key.NumLock,
            Key.ScrollLock,
        ).forEach { assertFalse(isShortcutCaptureKey(it), "$it is not an AWT-dispatchable shortcut key") }
        listOf(Key.N, Key.Tab, Key.One, Key.DirectionLeft).forEach { assertTrue(isShortcutCaptureKey(it)) }
    }

    @Test
    fun `native menu accelerator resolves a legacy numeric key`() {
        val binding = KeyBinding(actionId = "window.new", key = Key.N.keyCode.toString(), modifiers = listOf("Cmd"))
        val bridge = MenuShortcutBridge(KeymapSettings(shortcuts = mapOf(binding.actionId to binding)))
        assertEquals(KeyShortcut(Key.N, meta = true), bridge.getKeyShortcut(binding.actionId))
    }

    @Test
    fun `import repairs legacy names immediately without adding preset actions`() =
        runTest {
            val previous = KeymapSettingsManager.currentSettings.value
            try {
                val binding = KeyBinding(actionId = "window.new", key = Key.N.keyCode.toString())
                val settings = KeymapSettings(shortcuts = mapOf(binding.actionId to binding))
                val encoded = Json.encodeToString(KeymapSettings.serializer(), settings)
                val imported = assertNotNull(KeymapSettingsManager.importFromJson(encoded))
                assertEquals("n", imported.shortcuts.getValue(binding.actionId).key)
                assertEquals(setOf(binding.actionId), imported.shortcuts.keys)
            } finally {
                KeymapSettingsManager.updateSettings(previous)
            }
        }
}
