package ai.rever.boss.fullscreen

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.presets.KeymapPresets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins that every preset can actually leave captured full screen, and that the chord chosen for it
 * is one `KeymapMatcher` can express.
 */
class CapturedFullScreenPresetsTest {
    private val presets: Map<String, KeymapSettings> =
        mapOf(
            "BOSS Default" to KeymapPresets.getBOSSDefault(),
            "VS Code" to KeymapPresets.getVSCodePreset(),
            "IntelliJ" to KeymapPresets.getIntelliJPreset(),
            "Emacs" to KeymapPresets.getEmacsPreset(),
        )

    private fun isCmdFamily(m: String) = m.equals("Cmd", true) || m.equals("Meta", true)

    private fun isCtrlFamily(m: String) = m.equals("Ctrl", true) || m.equals("Control", true)

    @Test
    fun `every preset binds both escapes`() {
        presets.forEach { (name, settings) ->
            assertNotNull(
                settings.getBinding(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE),
                "$name cannot enter or leave captured full screen",
            )
            assertNotNull(
                settings.getBinding(KeymapActions.POINTER_RELEASE),
                "$name cannot release the pointer",
            )
        }
    }

    @Test
    fun `no binding anywhere mixes Cmd and Ctrl`() {
        // KeymapMatcher.matchesBinding ORs the two primary modifiers:
        //     (hasCmd && event.isMetaPressed) || (hasCtrl && event.isCtrlPressed)
        // so a Ctrl+Cmd+F binding also matches bare Cmd+F - which is find-in-page. That is why the
        // escapes here are Cmd+Alt rather than the Ctrl+Cmd the platform convention would suggest.
        // No preset mixes them today; this keeps it that way, for every action rather than only the
        // two added with it.
        presets.forEach { (name, settings) ->
            settings.shortcuts.values.forEach { binding ->
                val mixed = binding.modifiers.any(::isCmdFamily) && binding.modifiers.any(::isCtrlFamily)
                assertFalse(
                    mixed,
                    "$name binds ${binding.actionId} to ${binding.modifiers}+${binding.key}, which mixes " +
                        "Cmd and Ctrl. The matcher ORs them, so this also fires on the bare primary chord.",
                )
            }
        }
    }

    @Test
    fun `the escapes carry a system modifier, or the interceptor never sees them`() {
        // AWTKeyboardInterceptor returns before consulting the keymap unless Meta, Ctrl or Alt is
        // down. A Shift-only or modifier-less escape would be bound, listed in Settings, and dead.
        presets.forEach { (name, settings) ->
            listOf(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE, KeymapActions.POINTER_RELEASE).forEach { id ->
                val binding = settings.getBinding(id) ?: return@forEach
                val hasSystemModifier =
                    binding.modifiers.any { m ->
                        isCmdFamily(m) || isCtrlFamily(m) || m.equals("Alt", true) || m.equals("Option", true)
                    }
                assertTrue(hasSystemModifier, "$name binds $id without a system modifier, so it would never dispatch")
            }
        }
    }

    @Test
    fun `neither escape collides with another action in the same preset`() {
        presets.forEach { (name, settings) ->
            val byChord = mutableMapOf<String, MutableList<KeyBinding>>()
            settings.shortcuts.values.filter { it.enabled }.forEach { binding ->
                val chord =
                    binding.modifiers
                        .map { it.lowercase() }
                        .sorted()
                        .joinToString("+") +
                        "+" + binding.key.lowercase() + "@" + binding.context
                byChord.getOrPut(chord) { mutableListOf() } += binding
            }
            listOf(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE, KeymapActions.POINTER_RELEASE).forEach { id ->
                val binding = settings.getBinding(id) ?: return@forEach
                val chord =
                    binding.modifiers
                        .map { it.lowercase() }
                        .sorted()
                        .joinToString("+") +
                        "+" + binding.key.lowercase() + "@" + binding.context
                val sharing = byChord[chord].orEmpty().filter { it.actionId != id }
                assertTrue(
                    sharing.isEmpty(),
                    "$name binds $id to the same chord as ${sharing.map { it.actionId }}",
                )
            }
        }
    }
}
