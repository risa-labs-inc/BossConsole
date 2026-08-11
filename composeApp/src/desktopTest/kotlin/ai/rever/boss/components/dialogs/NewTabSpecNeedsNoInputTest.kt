package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.api.NewTabSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [needsNoInput], the predicate that decides whether clicking a plugin tab type opens it
 * immediately or drops into the input step.
 *
 * It is a heuristic over three fields rather than a declared flag, because `NewTabSpec` ships in
 * the external `boss-plugin-api` artifact and an explicit `opensImmediately` would need an api
 * release. That makes the exact boundary worth pinning: getting it wrong in the permissive
 * direction takes an input field away from a plugin that wanted one, and the plugin author has no
 * way to see why from their side.
 */
class NewTabSpecNeedsNoInputTest {
    private fun spec(
        label: String = "",
        placeholder: String = "",
        optional: Boolean = true,
    ) = NewTabSpec(
        order = 0,
        inputLabel = label,
        inputPlaceholder = placeholder,
        inputOptional = optional,
        confirmLabel = "Play",
    )

    @Test
    fun `blank label, blank placeholder and optional input declares no input`() {
        // Arcade, the one type this describes today.
        assertTrue(spec().needsNoInput())
    }

    @Test
    fun `a placeholder alone keeps the input step`() {
        // The case most likely to regress: a plugin that omits the label but still prompts through
        // the placeholder is asking for input, and instant-opening would silently discard it.
        assertFalse(spec(placeholder = "Search term").needsNoInput())
    }

    @Test
    fun `a label alone keeps the input step`() {
        assertFalse(spec(label = "Query").needsNoInput())
    }

    @Test
    fun `required input keeps the input step even with nothing to show`() {
        // Blank on both strings but not optional is a contradictory spec. Opening instantly would
        // hand the plugin the empty string it just said it would not accept, so the conservative
        // reading wins and the user still gets a field.
        assertFalse(spec(optional = false).needsNoInput())
    }

    @Test
    fun `whitespace counts as blank`() {
        // isBlank, not isEmpty: a spec padded with a space is not declaring an input, and treating
        // it as one would leave an empty labelled field that looks like a bug.
        assertTrue(spec(label = "   ", placeholder = "\t").needsNoInput())
    }
}
