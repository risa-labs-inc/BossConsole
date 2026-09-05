package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapValidator
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the standard browser chords merged into every preset by
 * [KeymapPresets.withStandardBrowserBindings].
 *
 * The merge exists so the conventions live in one place instead of four hand-written preset
 * lists; these tests pin the two things that merge makes easy to get wrong - that the chords actually
 * land in each preset, and that landing them never introduces a conflict with a preset's own
 * opinion about a key.
 */
class StandardBrowserBindingsTest {
    @Test
    fun `the standard bindings are internally conflict-free`() {
        // withoutChordsTakenBy checks each addition against the PRESET, not against additions
        // already accepted, so two standard bindings colliding with each other would both land.
        // `no preset ships a conflict` catches that today through validate() over the merged
        // result; this says it directly, at the source.
        val standalone = KeymapSettings.fromBindings(KeymapPresets.standardBrowserBindings())

        assertTrue(
            KeymapValidator.validate(standalone).isEmpty(),
            KeymapValidator.validate(standalone).joinToString { it.description() },
        )
    }

    @Test
    fun `BOSS default binds the standard browser chords`() {
        val settings = KeymapPresets.getBOSSDefault()

        val reopen = assertNotNull(settings.getBinding(KeymapActions.TAB_REOPEN_CLOSED))
        assertEquals("T", reopen.key)
        assertEquals(listOf("Cmd", "Shift"), reopen.modifiers)

        val nextTab = assertNotNull(settings.getBinding(KeymapActions.TAB_NEXT_POSITIONAL))
        assertEquals("DirectionRight", nextTab.key)
        assertEquals(listOf("Cmd", "Alt"), nextTab.modifiers)

        val prevTab = assertNotNull(settings.getBinding(KeymapActions.TAB_PREVIOUS_POSITIONAL))
        assertEquals("DirectionLeft", prevTab.key)
        assertEquals(listOf("Cmd", "Alt"), prevTab.modifiers)

        val devTools = assertNotNull(settings.getBinding(KeymapActions.BROWSER_DEVTOOLS))
        assertEquals("I", devTools.key)
        assertEquals(listOf("Cmd", "Alt"), devTools.modifiers)
        assertEquals(ShortcutContext.BROWSER, devTools.context)

        assertEquals("OpenBracket", assertNotNull(settings.getBinding(KeymapActions.BROWSER_BACK)).key)
        assertEquals("CloseBracket", assertNotNull(settings.getBinding(KeymapActions.BROWSER_FORWARD)).key)
    }

    @Test
    fun `Cmd+9 selects the last tab, not the ninth`() {
        val settings = KeymapPresets.getBOSSDefault()

        // Cmd+1..Cmd+8 are positional...
        val expectedKeys = listOf("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight")
        assertEquals(expectedKeys.size, KeymapActions.TAB_SELECT_BY_INDEX.size)
        KeymapActions.TAB_SELECT_BY_INDEX.forEachIndexed { index, actionId ->
            val binding = assertNotNull(settings.getBinding(actionId), "missing $actionId")
            assertEquals(expectedKeys[index], binding.key)
            assertEquals(listOf("Cmd"), binding.modifiers)
        }

        // ...and Cmd+9 is the last tab, which is a different action, not TAB_SELECT_BY_INDEX[8].
        val last = assertNotNull(settings.getBinding(KeymapActions.TAB_SELECT_LAST))
        assertEquals("Nine", last.key)
        assertEquals(listOf("Cmd"), last.modifiers)
    }

    @Test
    fun `zoom in also answers to Cmd+Shift+Equals`() {
        // A US keyboard reports Cmd+Shift+Equals for what the user thinks of as "Cmd+Plus";
        // the unshifted Equals binding alone never sees it.
        val zoomIn = assertNotNull(KeymapPresets.getBOSSDefault().getBinding(KeymapActions.BROWSER_ZOOM_IN))

        assertEquals("Equals", zoomIn.key)
        assertTrue(zoomIn.hasAlternates, "zoom in should carry a Cmd+Plus alternate")
        val alternate = zoomIn.alternateKeystrokes.single()
        assertEquals("Equals", alternate.key)
        assertTrue(alternate.modifiers.any { it.equals("Shift", ignoreCase = true) })
    }

    @Test
    fun `bracket alternates reach positional tab stepping`() {
        val settings = KeymapPresets.getBOSSDefault()

        val next = assertNotNull(settings.getBinding(KeymapActions.TAB_NEXT_POSITIONAL))
        assertTrue(
            next.allKeystrokes.any { it.key == "CloseBracket" && it.modifiers.containsAll(listOf("Cmd", "Shift")) },
            "Cmd+Shift+] should also step to the next tab",
        )

        val previous = assertNotNull(settings.getBinding(KeymapActions.TAB_PREVIOUS_POSITIONAL))
        assertTrue(
            previous.allKeystrokes.any { it.key == "OpenBracket" && it.modifiers.containsAll(listOf("Cmd", "Shift")) },
            "Cmd+Shift+[ should also step to the previous tab",
        )
    }

    @Test
    fun `a preset's own opinion about a chord outranks the convention`() {
        // IntelliJ binds Cmd+1 to the Project tool window. That must survive, and the tab-select
        // action that wanted Cmd+1 must be dropped rather than shipped as a live conflict.
        val intelliJ = KeymapPresets.getIntelliJPreset()

        val codebase = assertNotNull(intelliJ.getBinding(KeymapActions.CODEBASE_OPEN))
        assertEquals("One", codebase.key)
        assertEquals(listOf("Cmd"), codebase.modifiers)

        assertNull(
            intelliJ.getBinding(KeymapActions.TAB_SELECT_1),
            "Cmd+1 is taken in the IntelliJ preset, so tab.select_1 should be left unbound",
        )

        // The chords IntelliJ does NOT claim still arrive.
        assertNotNull(intelliJ.getBinding(KeymapActions.TAB_REOPEN_CLOSED))
        assertNotNull(intelliJ.getBinding(KeymapActions.TAB_SELECT_2))
    }

    @Test
    fun `a preset claiming the primary keeps the surviving alternate`() {
        // VS Code and IntelliJ both put panel navigation on Cmd+Alt+Arrow - the primary of
        // positional tab stepping. Dropping the whole binding there would take Cmd+Shift+[ and
        // Cmd+Shift+] with it and leave those presets unable to step tabs at all.
        listOf("VS Code" to KeymapPresets.getVSCodePreset(), "IntelliJ IDEA" to KeymapPresets.getIntelliJPreset())
            .forEach { (name, settings) ->
                val navLeft = assertNotNull(settings.getBinding(KeymapActions.PANEL_NAVIGATE_LEFT), name)
                assertEquals(listOf("Cmd", "Alt"), navLeft.modifiers, "$name should keep its own Cmd+Alt+Arrow")

                val next =
                    assertNotNull(settings.getBinding(KeymapActions.TAB_NEXT_POSITIONAL), "$name lost tab stepping")
                assertEquals("CloseBracket", next.key, "$name should fall back to Cmd+Shift+]")
                assertEquals(listOf("Cmd", "Shift"), next.modifiers)
                assertTrue(next.alternateKeystrokes.isEmpty(), "the colliding Cmd+Alt+Arrow should be gone")

                val previous = assertNotNull(settings.getBinding(KeymapActions.TAB_PREVIOUS_POSITIONAL), name)
                assertEquals("OpenBracket", previous.key)
            }
    }

    @Test
    fun `BOSS Default keeps Cmd+Opt+Arrow as the primary`() {
        // The counterpart: where nothing collides, the intended primary survives and the bracket
        // chord stays an alternate, so the View menu shows Cmd+Opt+Arrow on Next/Previous Tab.
        val next = assertNotNull(KeymapPresets.getBOSSDefault().getBinding(KeymapActions.TAB_NEXT_POSITIONAL))
        assertEquals("DirectionRight", next.key)
        assertEquals(listOf("Cmd", "Alt"), next.modifiers)
        assertEquals(1, next.alternateKeystrokes.size)
    }

    @Test
    fun `Cmd+L is bound for the browser plugin in BROWSER context only`() {
        // The layering that keeps Cmd+L working as both Go To Line and Focus Address Bar. The
        // plugin registers its action with NO default, because a plugin default is GLOBAL and
        // would consume the chord in an editor; the context can only be expressed here.
        listOf(
            "BOSS Default" to KeymapPresets.getBOSSDefault(),
            "VS Code" to KeymapPresets.getVSCodePreset(),
            "IntelliJ IDEA" to KeymapPresets.getIntelliJPreset(),
            "Emacs" to KeymapPresets.getEmacsPreset(),
        ).forEach { (name, settings) ->
            val focus = assertNotNull(settings.getBinding(KeymapPresets.FLUCK_FOCUS_ADDRESS_BAR_ACTION), name)
            assertEquals("L", focus.key, name)
            assertEquals(listOf("Cmd"), focus.modifiers, name)
            assertEquals(ShortcutContext.BROWSER, focus.context, "$name must not make it GLOBAL")

            // Where the preset defines Go To Line it stays EDITOR-scoped, whatever chord it
            // gives it: BOSS Default and IntelliJ use Cmd+L, VS Code uses Cmd+G, and Emacs binds
            // no editor actions at all. Disjoint contexts are what let the two coexist on one
            // chord where they do share it.
            settings.getBinding(KeymapActions.EDITOR_GO_TO_LINE)?.let { goToLine ->
                assertEquals(ShortcutContext.EDITOR, goToLine.context, name)
            }
        }
    }

    @Test
    fun `no preset ships a conflict`() {
        val presets =
            mapOf(
                "BOSS Default" to KeymapPresets.getBOSSDefault(),
                "VS Code" to KeymapPresets.getVSCodePreset(),
                "IntelliJ IDEA" to KeymapPresets.getIntelliJPreset(),
                "Emacs" to KeymapPresets.getEmacsPreset(),
            )

        presets.forEach { (name, settings) ->
            val conflicts = KeymapValidator.validate(settings)
            assertTrue(
                conflicts.isEmpty(),
                "$name ships conflicts: ${conflicts.joinToString { it.description() }}",
            )
        }
    }

    @Test
    fun `every standard action carries a description and category`() {
        // getAllActionIds is what the Shortcuts settings screen enumerates; an action missing
        // from the description/category maps renders as "Unknown action" under "Tools".
        KeymapPresets.standardBrowserBindings().forEach { binding ->
            if (binding.actionId.startsWith("plugin.")) {
                // A plugin-contributed action is not in the host registry by design, so it
                // carries its own description on the binding instead of via getDescription.
                assertTrue(binding.description.isNotBlank(), "${binding.actionId} has no description")
                assertTrue(binding.category.isNotBlank(), "${binding.actionId} has no category")
                return@forEach
            }
            assertTrue(
                binding.actionId in KeymapActions.getAllActionIds(),
                "${binding.actionId} is bound but not registered in getAllActionIds()",
            )
            assertTrue(
                KeymapActions.getDescription(binding.actionId) != "Unknown action",
                "${binding.actionId} has no description",
            )
        }
    }
}
