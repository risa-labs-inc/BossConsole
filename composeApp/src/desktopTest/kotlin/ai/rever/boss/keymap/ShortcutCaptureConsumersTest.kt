package ai.rever.boss.keymap

import ai.rever.boss.components.settings.keymap.isShortcutCaptureKey
import ai.rever.boss.keymap.menu.MenuAcceleratorModifiers
import ai.rever.boss.keymap.menu.MenuShortcutBridge
import ai.rever.boss.keymap.menu.menuAcceleratorModifiers
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.formatShortcutLabel
import ai.rever.boss.keymap.model.storedKeyName
import ai.rever.boss.utils.SystemUtils
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
    fun `native menu accelerator maps a Cmd binding to the platform primary modifier`() {
        val binding =
            KeyBinding(
                actionId = "window.new",
                // Packed-keycode spelling, on purpose: the bridge's only exercise of
                // canonicalKeyName's legacy numeric branch.
                key = Key.N.keyCode.toString(),
                modifiers = listOf("Cmd"),
            )
        val bridge = MenuShortcutBridge(KeymapSettings(shortcuts = mapOf(binding.actionId to binding)))
        val shortcut = bridge.getKeyShortcut(binding.actionId)

        if (SystemUtils.isMacOS) {
            assertEquals(KeyShortcut(Key.N, meta = true), shortcut)
        } else {
            assertEquals(KeyShortcut(Key.N, ctrl = true), shortcut)
        }
    }

    @Test
    fun `the platform modifier mapping pins both branches on any runner`() {
        // Cmd is the primary modifier everywhere: Meta on macOS, Ctrl on Windows/Linux, never
        // Compose's meta (the Super key). A platform-conditional test could only ever assert
        // the branch of the OS it runs on; the pure function takes the platform, so both
        // branches are asserted unconditionally here.
        assertEquals(
            MenuAcceleratorModifiers(ctrl = true, meta = false),
            menuAcceleratorModifiers(hasCmd = true, hasCtrl = false, isMacOS = false),
        )
        assertEquals(
            MenuAcceleratorModifiers(ctrl = false, meta = true),
            menuAcceleratorModifiers(hasCmd = true, hasCtrl = false, isMacOS = true),
        )

        // An explicit Ctrl stays Ctrl on every platform and never becomes meta: that is the
        // asymmetry the interceptor relies on, and a regression of it would otherwise only
        // surface on the OS the author is not using.
        assertEquals(
            MenuAcceleratorModifiers(ctrl = true, meta = false),
            menuAcceleratorModifiers(hasCmd = false, hasCtrl = true, isMacOS = false),
        )
        assertEquals(
            MenuAcceleratorModifiers(ctrl = true, meta = false),
            menuAcceleratorModifiers(hasCmd = false, hasCtrl = true, isMacOS = true),
        )

        // Both modifiers: mac keeps them distinct, non-mac collapses onto ctrl.
        assertEquals(
            MenuAcceleratorModifiers(ctrl = true, meta = true),
            menuAcceleratorModifiers(hasCmd = true, hasCtrl = true, isMacOS = true),
        )
        assertEquals(
            MenuAcceleratorModifiers(ctrl = true, meta = false),
            menuAcceleratorModifiers(hasCmd = true, hasCtrl = true, isMacOS = false),
        )

        // Neither: nothing to map.
        assertEquals(
            MenuAcceleratorModifiers(ctrl = false, meta = false),
            menuAcceleratorModifiers(hasCmd = false, hasCtrl = false, isMacOS = false),
        )
        assertEquals(
            MenuAcceleratorModifiers(ctrl = false, meta = false),
            menuAcceleratorModifiers(hasCmd = false, hasCtrl = false, isMacOS = true),
        )
    }

    @Test
    fun `native menu accelerator keeps an explicit Ctrl binding on Ctrl on every platform`() {
        val binding =
            KeyBinding(
                actionId = "window.close",
                key = "W",
                modifiers = listOf("Ctrl"),
            )
        val bridge = MenuShortcutBridge(KeymapSettings(shortcuts = mapOf(binding.actionId to binding)))
        val shortcut = bridge.getKeyShortcut(binding.actionId)
        assertEquals(KeyShortcut(Key.W, ctrl = true), shortcut)
    }

    @Test
    fun `native menu accelerator carries Shift and Alt through the platform mapping`() {
        val binding =
            KeyBinding(
                actionId = "window.new",
                key = "N",
                modifiers = listOf("Cmd", "Shift", "Alt"),
            )
        val bridge = MenuShortcutBridge(KeymapSettings(shortcuts = mapOf(binding.actionId to binding)))
        val shortcut = bridge.getKeyShortcut(binding.actionId)

        if (SystemUtils.isMacOS) {
            assertEquals(KeyShortcut(Key.N, meta = true, shift = true, alt = true), shortcut)
        } else {
            assertEquals(KeyShortcut(Key.N, ctrl = true, shift = true, alt = true), shortcut)
        }
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
