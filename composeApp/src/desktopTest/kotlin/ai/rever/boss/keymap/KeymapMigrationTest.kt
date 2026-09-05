package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapValidator
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
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
 * Migration of an existing ~/.boss/keymap-settings.json onto a newer preset.
 *
 * Adding missing ACTIONS was enough while presets only ever gained actions. A preset can also
 * gain a new alternate chord for an action every keymap file already contains - zoom in picking
 * up Cmd+Shift+Equals, what a US keyboard reports for "Cmd+Plus" - and that change reaches
 * nobody who has launched BOSS before, because the stored alternate-less copy wins.
 */
class KeymapMigrationTest {
    /** A keymap as written before the standard-browser-chords change: no alternates anywhere. */
    private fun legacySettings(): KeymapSettings {
        val stripped =
            KeymapPresets
                .getBOSSDefault()
                .shortcuts
                .filterKeys { it !in KeymapActions.TAB_SELECT_BY_INDEX && it != KeymapActions.TAB_REOPEN_CLOSED }
                .mapValues { (_, binding) -> binding.clearAlternateKeystrokes() }
        return KeymapSettings(shortcuts = stripped, presetName = "BOSS Default")
    }

    @Test
    fun `a new action is dropped when the stored keymap already claims its chord`() {
        // A customised keymap is exactly where the user holds chords the preset does not know
        // about. Adding the preset's new actions verbatim would ship the conflicts
        // withStandardBrowserBindings exists to prevent, twenty at a time.
        val legacy = legacySettings()
        val rebound =
            assertNotNull(legacy.getBinding(KeymapActions.PANEL_NAVIGATE_RIGHT))
                .copy(key = "Three", modifiers = listOf("Cmd"))
        val customised =
            legacy.copy(shortcuts = legacy.shortcuts + (KeymapActions.PANEL_NAVIGATE_RIGHT to rebound))

        val migrated = KeymapSettingsManager.migrateSettings(customised)

        assertEquals(
            "Three",
            migrated.getBinding(KeymapActions.PANEL_NAVIGATE_RIGHT)?.key,
            "the user's own binding is untouched",
        )
        assertNull(
            migrated.getBinding(KeymapActions.TAB_SELECT_BY_INDEX[2]),
            "Cmd+3 was taken, so the new action is dropped rather than shipped as a conflict",
        )
        // The rest of the batch still lands: dropping is per action, not all-or-nothing.
        assertNotNull(migrated.getBinding(KeymapActions.TAB_SELECT_BY_INDEX[0]))
        assertNotNull(migrated.getBinding(KeymapActions.TAB_REOPEN_CLOSED))
    }

    @Test
    fun `migration never introduces a conflict onto a customised keymap`() {
        val legacy = legacySettings()
        // Two rebinds sitting on chords this migration is about to deliver.
        val customised =
            legacy.copy(
                shortcuts =
                    legacy.shortcuts +
                        mapOf(
                            KeymapActions.PANEL_NAVIGATE_RIGHT to
                                assertNotNull(legacy.getBinding(KeymapActions.PANEL_NAVIGATE_RIGHT))
                                    .copy(key = "Three", modifiers = listOf("Cmd")),
                            KeymapActions.WORKSPACE_SAVE to
                                assertNotNull(legacy.getBinding(KeymapActions.WORKSPACE_SAVE)).copy(
                                    key = "T",
                                    modifiers = listOf("Cmd", "Shift"),
                                    context = ShortcutContext.GLOBAL,
                                ),
                        ),
            )
        assertTrue(KeymapValidator.validate(customised).isEmpty(), "the input itself is conflict-free")

        val migrated = KeymapSettingsManager.migrateSettings(customised)

        assertTrue(
            KeymapValidator.validate(migrated).isEmpty(),
            "migration must leave the keymap as conflict-free as it found it: " +
                KeymapValidator.validate(migrated).joinToString { it.description() },
        )
        assertNull(migrated.getBinding(KeymapActions.TAB_REOPEN_CLOSED), "Cmd+Shift+T was taken")
    }

    @Test
    fun `an alias-spelled stored chord still counts as taken`() {
        // The case sameChordAs exists for, applied to the other half of migration: a keymap file
        // written by an older build, hand-edited, or imported can spell a chord
        // ["Meta","Option"] + "Right" where the presets say ["Cmd","Alt"] + "DirectionRight".
        // findMatchingBinding folds both, so if the chord check does not, migration adds a
        // second action onto an occupied chord and neither one reliably wins - with no conflict
        // badge either, since the validator compares the same signatures.
        val legacy = legacySettings()
        val aliasSpelled =
            assertNotNull(legacy.getBinding(KeymapActions.PANEL_NAVIGATE_RIGHT))
                .copy(key = "Three", modifiers = listOf("Meta"))
        val customised =
            legacy.copy(shortcuts = legacy.shortcuts + (KeymapActions.PANEL_NAVIGATE_RIGHT to aliasSpelled))

        val migrated = KeymapSettingsManager.migrateSettings(customised)

        assertNull(
            migrated.getBinding(KeymapActions.TAB_SELECT_BY_INDEX[2]),
            "Meta+3 is Cmd+3, so the chord is taken",
        )
        assertTrue(
            KeymapValidator.validate(migrated).isEmpty(),
            KeymapValidator.validate(migrated).joinToString { it.description() },
        )
    }

    @Test
    fun `an alias-spelled key name still counts as taken`() {
        // Same case, on the key half. "Right" is what an older build wrote for what the presets
        // now call "DirectionRight", and tab.next_positional is one of the actions this
        // migration delivers on Cmd+Alt+DirectionRight.
        val legacy = legacySettings()
        val aliasSpelled =
            assertNotNull(legacy.getBinding(KeymapActions.PANEL_NAVIGATE_RIGHT))
                .copy(key = "Right", modifiers = listOf("Cmd", "Alt"))
        val customised =
            legacy.copy(
                shortcuts =
                    legacy.shortcuts - KeymapActions.TAB_NEXT_POSITIONAL +
                        (KeymapActions.PANEL_NAVIGATE_RIGHT to aliasSpelled),
            )
        assertTrue(KeymapValidator.validate(customised).isEmpty(), "the input itself is conflict-free")

        val migrated = KeymapSettingsManager.migrateSettings(customised)

        assertTrue(
            KeymapValidator.validate(migrated).isEmpty(),
            "Cmd+Alt+Right is Cmd+Alt+DirectionRight: " +
                KeymapValidator.validate(migrated).joinToString { it.description() },
        )
        // The Cmd+Shift+] alternate is free, so the action still lands - on that chord alone.
        val stepping = migrated.getBinding(KeymapActions.TAB_NEXT_POSITIONAL)
        val heldChord = aliasSpelled.primaryKeystroke.signature()
        assertTrue(
            stepping == null || stepping.allKeystrokes.none { it.signature() == heldChord },
            "it must not have been given the chord panel navigation holds",
        )
    }

    @Test
    fun `a disabled binding does not reserve its chord`() {
        // The validator only counts enabled bindings as conflicting, so migration has to agree:
        // otherwise switching a shortcut off in the Shortcuts screen blocks the new action that
        // wants its chord, with nothing but a log line to say why.
        val legacy = legacySettings()
        val switchedOff =
            assertNotNull(legacy.getBinding(KeymapActions.PANEL_NAVIGATE_RIGHT))
                .copy(key = "Three", modifiers = listOf("Cmd"), enabled = false)
        val customised =
            legacy.copy(shortcuts = legacy.shortcuts + (KeymapActions.PANEL_NAVIGATE_RIGHT to switchedOff))

        val migrated = KeymapSettingsManager.migrateSettings(customised)

        assertNotNull(
            migrated.getBinding(KeymapActions.TAB_SELECT_BY_INDEX[2]),
            "a disabled binding is not holding Cmd+3",
        )
    }

    @Test
    fun `a modifier alias does not read as a rebind`() {
        // The file is documented as hand-editable, and both matchers treat Meta as Cmd. Without
        // the fold this reads as rebound and silently misses the alternate top-up.
        val legacy = legacySettings()
        val metaSpelled =
            assertNotNull(legacy.getBinding(KeymapActions.BROWSER_ZOOM_IN)).copy(modifiers = listOf("Meta"))
        val handEdited =
            legacy.copy(shortcuts = legacy.shortcuts + (KeymapActions.BROWSER_ZOOM_IN to metaSpelled))

        val migrated = KeymapSettingsManager.migrateSettings(handEdited)

        val zoomIn = assertNotNull(migrated.getBinding(KeymapActions.BROWSER_ZOOM_IN))
        assertTrue(
            zoomIn.alternateKeystrokes.any { it.key == "Equals" && "Shift" in it.modifiers },
            "the Cmd+Shift+Equals alternate should still be topped up",
        )
    }

    @Test
    fun `an untouched binding gains the preset's new alternate`() {
        val migrated = KeymapSettingsManager.migrateSettings(legacySettings())

        val zoomIn = assertNotNull(migrated.getBinding(KeymapActions.BROWSER_ZOOM_IN))
        assertEquals("Equals", zoomIn.key, "the primary is untouched")
        assertTrue(
            zoomIn.alternateKeystrokes.any { it.key == "Equals" && it.modifiers.any { m -> m.equals("Shift", true) } },
            "Cmd+Plus should reach an existing install, not just a fresh profile",
        )
    }

    @Test
    fun `a rebound binding is left alone`() {
        // The user moved zoom in to Cmd+Alt+Z. Bolting the preset's alternates onto that would
        // resurrect a chord they deliberately moved away from.
        val rebound =
            KeyBinding(
                actionId = KeymapActions.BROWSER_ZOOM_IN,
                key = "Z",
                modifiers = listOf("Cmd", "Alt"),
                context = ShortcutContext.BROWSER,
            )
        val settings = legacySettings().let { it.copy(shortcuts = it.shortcuts + (rebound.actionId to rebound)) }

        val migrated = KeymapSettingsManager.migrateSettings(settings)

        val zoomIn = assertNotNull(migrated.getBinding(KeymapActions.BROWSER_ZOOM_IN))
        assertEquals("Z", zoomIn.key)
        assertTrue(zoomIn.alternateKeystrokes.isEmpty(), "a rebound chord is the user's, not the preset's")
    }

    @Test
    fun `new actions still arrive`() {
        val migrated = KeymapSettingsManager.migrateSettings(legacySettings())

        assertNotNull(migrated.getBinding(KeymapActions.TAB_REOPEN_CLOSED), "Cmd+Shift+T should be added")
        assertNotNull(migrated.getBinding(KeymapActions.TAB_SELECT_1))
    }

    @Test
    fun `an already-current keymap migrates to itself`() {
        // Idempotence matters: loadSettingsSync rewrites the file whenever migration changes
        // anything, so a non-identity result here would rewrite on every launch.
        val current = KeymapPresets.getBOSSDefault()

        assertEquals(current, KeymapSettingsManager.migrateSettings(current))
    }

    @Test
    fun `an existing alternate is not duplicated`() {
        val current = KeymapPresets.getBOSSDefault()
        val migrated = KeymapSettingsManager.migrateSettings(KeymapSettingsManager.migrateSettings(current))

        val zoomIn = assertNotNull(migrated.getBinding(KeymapActions.BROWSER_ZOOM_IN))
        assertEquals(
            zoomIn.alternateKeystrokes.distinct().size,
            zoomIn.alternateKeystrokes.size,
            "repeated migration should not stack duplicate alternates",
        )
        assertEquals(listOf(KeyStroke("Equals", listOf("Cmd", "Shift"))), zoomIn.alternateKeystrokes)
    }
}
