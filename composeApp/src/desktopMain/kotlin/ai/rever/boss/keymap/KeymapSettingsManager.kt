package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.keymap.presets.KeymapPresets.claimsChord
import ai.rever.boss.keymap.presets.KeymapPresets.withoutChordsTakenBy
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of KeymapSettingsManager.
 * Manages loading and saving of keyboard shortcut settings.
 * Follows the BOSS settings management pattern with:
 * - JSON persistence in ~/.boss/keymap-settings.json
 * - Automatic directory creation
 * - Synchronous load on init, asynchronous save
 * - Graceful error handling with fallback to defaults
 */
actual object KeymapSettingsManager {
    private val logger = BossLogger.forComponent("KeymapSettingsManager")
    private val settingsFile = BossDirectories.resolve("keymap-settings.json")
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val _currentSettings = MutableStateFlow<KeymapSettings>(KeymapPresets.getBOSSDefault())
    actual val currentSettings: StateFlow<KeymapSettings> = _currentSettings.asStateFlow()

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * Load settings synchronously on startup.
     * If file doesn't exist, uses default keymap.
     * Applies migration to add any new actions from presets.
     */
    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val loaded = json.decodeFromString<KeymapSettings>(content)
                logger.debug(LogCategory.SYSTEM, "Loaded keymap settings", mapOf("path" to settingsFile.absolutePath))

                // Apply migration to add any new actions from preset
                val migrated = migrateSettings(loaded)

                // Save if migration made changes
                if (migrated != loaded) {
                    try {
                        val migratedContent = json.encodeToString(KeymapSettings.serializer(), migrated)
                        settingsFile.writeText(migratedContent)
                        logger.debug(LogCategory.SYSTEM, "Migrated keymap settings saved")
                    } catch (e: Exception) {
                        logger.warn(LogCategory.SYSTEM, "Could not save migrated keymap settings", error = e)
                    }
                }

                _currentSettings.value = migrated
            } else {
                // First run - create default keymap file
                logger.debug(LogCategory.SYSTEM, "No keymap settings file found, creating default")
                val defaultSettings = KeymapPresets.getBOSSDefault()
                _currentSettings.value = defaultSettings

                // Save default settings to file
                try {
                    val content = json.encodeToString(KeymapSettings.serializer(), defaultSettings)
                    settingsFile.writeText(content)
                    logger.debug(LogCategory.SYSTEM, "Created default keymap settings file", mapOf("path" to settingsFile.absolutePath))
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Could not write default keymap settings file", error = e)
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to load keymap settings, using defaults", error = e)
            _currentSettings.value = KeymapPresets.getBOSSDefault()
        }
    }

    /**
     * Migrate settings by adding any new actions from the preset that are missing.
     * This ensures existing users get new keybindings added to presets while
     * preserving their customizations.
     *
     * @param loaded The loaded user settings
     * @return Migrated settings with any missing actions added from the preset
     */
    internal fun migrateSettings(loaded: KeymapSettings): KeymapSettings {
        // Get the preset that matches user's presetName
        val presetShortcuts =
            when (loaded.presetName) {
                "VS Code" -> KeymapPresets.getVSCodePreset().shortcuts
                "IntelliJ IDEA" -> KeymapPresets.getIntelliJPreset().shortcuts
                "Emacs" -> KeymapPresets.getEmacsPreset().shortcuts
                else -> KeymapPresets.getBOSSDefault().shortcuts
            }

        // Find actions in preset that are missing from user settings
        val missingActions =
            presetShortcuts.filterKeys { actionId ->
                !loaded.shortcuts.containsKey(actionId)
            }

        val alternateTopUps = alternateTopUps(loaded, presetShortcuts)

        // Chord-checked against the keymap as it will stand, exactly as withStandardBrowserBindings
        // checks additions against the preset. Adding a preset's new actions verbatim would ship
        // precisely the conflicts that merge exists to prevent, and a CUSTOMISED keymap is where
        // the user has claimed chords the preset knows nothing about: someone who rebound
        // panel.navigate_right to Cmd+Opt+Right would otherwise also receive
        // tab.next_positional on it. The stored binding wins the match, so the new chord would
        // do nothing while the conflict badge lit up - and this PR lands twenty chords in one
        // migration, not one. An action whose every chord is taken is dropped, as in the merge.
        val toppedUp = loaded.shortcuts + alternateTopUps
        val holders = chordHolders(loaded.copy(shortcuts = toppedUp))
        val newActions =
            missingActions.values
                .mapNotNull { it.withoutChordsTakenBy(holders) }
                .associateBy { it.actionId }

        val dropped = missingActions.keys - newActions.keys
        if (dropped.isNotEmpty()) {
            // Said out loud so a user who reads the docs, does not get Cmd+3, and finds no
            // conflict badge has something to go on. At DEBUG because when every new chord is
            // taken there is nothing to persist, migrateSettings returns `loaded` unchanged, and
            // an INFO line would repeat on every launch for the rest of that keymap's life.
            logger.debug(
                LogCategory.SYSTEM,
                "Keymap migration dropped new actions whose chords this keymap already claims",
                mapOf("actionIds" to dropped.joinToString()),
            )
        }

        if (newActions.isEmpty() && alternateTopUps.isEmpty()) {
            return loaded // No migration needed
        }

        logger.info(
            LogCategory.SYSTEM,
            "Migrating keymap settings",
            mapOf(
                "newActions" to newActions.size,
                "actionIds" to newActions.keys.joinToString(),
                "alternateTopUps" to alternateTopUps.size,
                "alternateActionIds" to alternateTopUps.keys.joinToString(),
            ),
        )

        // Merge: user settings, alternates topped up on untouched bindings, then new actions
        val mergedShortcuts = toppedUp + newActions

        return loaded.copy(shortcuts = mergedShortcuts)
    }

    /**
     * Save current settings to disk asynchronously.
     */
    actual suspend fun saveSettings() =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(KeymapSettings.serializer(), _currentSettings.value)
                settingsFile.writeText(content)
                logger.debug(LogCategory.SYSTEM, "Keymap settings saved")
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to save keymap settings", error = e)
            }
        }

    /**
     * Update the current settings and save to disk.
     */
    actual suspend fun updateSettings(settings: KeymapSettings) {
        _currentSettings.value = settings
        saveSettings()
    }

    /**
     * Load a preset keymap by name.
     */
    actual suspend fun loadPreset(presetName: String) {
        val preset =
            when (presetName) {
                "BOSS Default" -> {
                    KeymapPresets.getBOSSDefault()
                }

                "VS Code" -> {
                    KeymapPresets.getVSCodePreset()
                }

                "IntelliJ IDEA" -> {
                    KeymapPresets.getIntelliJPreset()
                }

                "Emacs" -> {
                    KeymapPresets.getEmacsPreset()
                }

                else -> {
                    logger.warn(LogCategory.SYSTEM, "Unknown keymap preset, using BOSS Default", mapOf("presetName" to presetName))
                    KeymapPresets.getBOSSDefault()
                }
            }
        updateSettings(preset)
    }

    /**
     * Reset to default BOSS keymap.
     */
    actual suspend fun resetToDefault() {
        updateSettings(KeymapPresets.getBOSSDefault())
    }

    /**
     * Import keymap from JSON string.
     * Returns null if import fails.
     */
    actual suspend fun importFromJson(jsonString: String): KeymapSettings? =
        try {
            val settings = json.decodeFromString<KeymapSettings>(jsonString)
            updateSettings(settings)
            settings
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to import keymap settings", error = e)
            null
        }

    /**
     * Export current keymap to JSON string.
     */
    actual fun exportToJson(): String = json.encodeToString(KeymapSettings.serializer(), _currentSettings.value)

    /**
     * Import keymap from file.
     * Returns null if import fails.
     */
    suspend fun importFromFile(file: File): KeymapSettings? =
        withContext(Dispatchers.IO) {
            try {
                val content = file.readText()
                importFromJson(content)
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to import keymap from file", error = e)
                null
            }
        }

    /**
     * Export current keymap to file.
     */
    suspend fun exportToFile(file: File) =
        withContext(Dispatchers.IO) {
            try {
                file.writeText(exportToJson())
                logger.debug(LogCategory.SYSTEM, "Exported keymap settings", mapOf("path" to file.absolutePath))
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to export keymap to file", error = e)
            }
        }
}

/**
 * The bindings in [loaded] that should gain an alternate chord from [presetShortcuts].
 *
 * Adding missing ACTIONS is not enough on its own: a preset can also gain a new alternate
 * chord for an action every existing keymap file already contains, and such a change would
 * never reach anyone who has launched BOSS before. Zoom in picking up Cmd+Shift+Equals (what
 * a US keyboard reports for "Cmd+Plus") is exactly that shape.
 *
 * Only where the user has not touched the binding: same primary keystroke as the preset
 * means they kept the default, so the preset still speaks for it. A rebound chord is the
 * user's, and silently bolting alternates onto it would resurrect a chord they moved away
 * from.
 *
 * Top-level for the same reason as [chordHolders] and [sameChordAs]: the object sits on its
 * TooManyFunctions threshold, and this is a property of a keymap and a preset, not of the manager.
 */
private fun alternateTopUps(
    loaded: KeymapSettings,
    presetShortcuts: Map<String, KeyBinding>,
): Map<String, KeyBinding> {
    // Hoisted out of the predicate below, which would otherwise re-filter the whole
    // shortcut map once per candidate alternate.
    val holders = chordHolders(loaded)
    return loaded.shortcuts
        .mapNotNull { (actionId, stored) ->
            val preset = presetShortcuts[actionId] ?: return@mapNotNull null
            val untouched = stored.primaryKeystroke.sameChordAs(preset.primaryKeystroke)
            // sameChordAs on this half too, not data-class equality: KeyStroke.modifiers is
            // a List, so a hand-edited ["Shift","Cmd"] alternate would read as absent and
            // get the preset's ["Cmd","Shift"] appended next to it - a duplicate chord in
            // the file and in allSignatures(), which the conflict badge reads.
            val gained =
                preset.alternateKeystrokes.filter { candidate ->
                    stored.alternateKeystrokes.none { it.sameChordAs(candidate) } &&
                        // And not a chord this keymap already gives to something else, for
                        // the same reason migrateSettings filters its additions.
                        holders.none {
                            it.actionId != actionId && it.claimsChord(candidate, stored.context)
                        }
                }
            if (untouched && gained.isNotEmpty()) {
                actionId to stored.copy(alternateKeystrokes = stored.alternateKeystrokes + gained)
            } else {
                null
            }
        }.toMap()
}

/**
 * The bindings in [settings] that really hold a chord against a new action.
 *
 * Disabled bindings are excluded, so that switching a shortcut off in the Shortcuts screen frees
 * its chord for a migration to fill - which is the same rule [ai.rever.boss.keymap.handler.KeymapValidator]
 * applies when it decides what conflicts, and the two answering differently is how a user ends
 * up with a chord that neither works nor shows a badge. The cost is that re-enabling the old
 * binding then produces a real conflict, visible in the badge, which is the honest outcome of
 * asking for both.
 */
private fun chordHolders(settings: KeymapSettings): List<KeyBinding> = settings.shortcuts.values.filter { it.enabled }

/**
 * Same key and same set of modifiers, whatever order or spelling either is written in.
 *
 * KeyStroke.modifiers is a List, and the keymap file is documented as hand-editable, so
 * ["Shift","Cmd"] would otherwise read as a rebind and silently miss the alternate top-up.
 *
 * Just [KeyStroke.signature], which canonicalises both halves - "Left" and "DirectionLeft" are
 * one key, "Meta" and "Cmd" one modifier. It reads as its own function because the question here
 * is "did the user rebind this", and because there used to be a hand-rolled comparison in its
 * place that folded key names but not modifiers, so a keymap written with "Meta" read as rebound
 * and silently missed its top-up though both matchers would have fired it.
 *
 * Top-level rather than a member of the object: it is a property of two KeyStrokes, and the
 * object is at its TooManyFunctions threshold.
 */
private fun KeyStroke.sameChordAs(other: KeyStroke): Boolean = signature() == other.signature()
