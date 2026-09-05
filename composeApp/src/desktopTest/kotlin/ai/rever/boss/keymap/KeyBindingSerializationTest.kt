package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.ShortcutContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KeyBinding]'s serialized shape.
 *
 * `primaryKeystroke` and `allKeystrokes` are cached body properties, and kotlinx.serialization
 * takes any property with a backing field unless it is `@Transient`. Without that annotation
 * `keymap-settings.json` - documented as hand-editable - could carry an `allKeystrokes` array
 * that both matchers then consult in place of an edited `key`, which is a rebind that appears to
 * do nothing and shows no conflict badge. These assert the annotation is still there.
 */
class KeyBindingSerializationTest {
    private val json = Json { prettyPrint = true }

    private val binding =
        KeyBinding(
            actionId = "browser.zoom_in",
            key = "Equals",
            modifiers = listOf("Cmd"),
            alternateKeystrokes = listOf(KeyStroke("Equals", listOf("Cmd", "Shift"))),
            context = ShortcutContext.BROWSER,
            description = "Zoom in",
        )

    @Test
    fun `the cached keystrokes are not written`() {
        val encoded = json.encodeToString(KeyBinding.serializer(), binding)

        assertFalse(encoded.contains("allKeystrokes"), encoded)
        assertFalse(encoded.contains("primaryKeystroke"), encoded)
        assertTrue(encoded.contains("alternateKeystrokes"), "the real field still round-trips")
    }

    @Test
    fun `a stale cached keystroke in a hand-edited file cannot win over the key`() {
        // The failure this guards: an allKeystrokes carrying the OLD chord, decoded over a key
        // the user has since edited. With @Transient the field is simply not part of the
        // descriptor, so the caches are rebuilt from key/modifiers on decode.
        val handEdited =
            """
            {
              "actionId": "browser.zoom_in",
              "key": "Backslash",
              "modifiers": ["Cmd"],
              "allKeystrokes": [{ "key": "Equals", "modifiers": ["Cmd"] }],
              "context": "BROWSER"
            }
            """.trimIndent()

        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(KeyBinding.serializer(), handEdited)

        assertEquals("Backslash", decoded.key)
        assertEquals(listOf("Backslash"), decoded.allKeystrokes.map { it.key }, "rebuilt from key, not read back")
    }
}
