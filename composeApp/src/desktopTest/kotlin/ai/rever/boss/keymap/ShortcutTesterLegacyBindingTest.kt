package ai.rever.boss.keymap

import ai.rever.boss.components.settings.keymap.ShortcutTestRunner
import ai.rever.boss.components.settings.keymap.TestStatus
import ai.rever.boss.keymap.model.KeyBinding
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortcutTesterLegacyBindingTest {
    @Test
    fun `imported legacy binding has the same verdict as a named binding`() =
        runTest {
            val named = KeyBinding(actionId = "window.new", key = "N", modifiers = listOf("Cmd"))
            val legacy = named.copy(key = Key.N.keyCode.toString())
            assertEquals(TestStatus.SUCCESS, ShortcutTestRunner.testShortcut(named).status)
            assertEquals(TestStatus.SUCCESS, ShortcutTestRunner.testShortcut(legacy).status)
        }

    @Test
    fun `an overflowing numeric key keeps a failed verdict`() =
        runTest {
            val binding = KeyBinding(actionId = "window.new", key = "999999999999999999999999")
            val result = ShortcutTestRunner.testShortcut(binding)
            assertEquals(TestStatus.FAILED, result.status)
            assertTrue(result.message.contains("re-record"))
        }

    @Test
    fun `empty keys fail while the literal space alias remains valid`() =
        runTest {
            listOf("", "  ", "\t").forEach { key ->
                val result = ShortcutTestRunner.testShortcut(KeyBinding(actionId = "window.new", key = key))
                assertEquals(TestStatus.FAILED, result.status)
            }
            val space = ShortcutTestRunner.testShortcut(KeyBinding(actionId = "window.new", key = " "))
            assertEquals(TestStatus.SUCCESS, space.status)
        }

    @Test
    fun `valid function and punctuation keys pass the actual tester`() =
        runTest {
            listOf("F5", "F13", "-", "Left Bracket", "MoveHome", "⇥").forEach { key ->
                val result = ShortcutTestRunner.testShortcut(KeyBinding(actionId = "window.new", key = key))
                assertEquals(TestStatus.SUCCESS, result.status, "$key: ${result.message}")
            }
        }
}
