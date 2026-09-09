package ai.rever.boss.keymap.model

import androidx.compose.ui.input.key.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * The modifier set a chord really requires, with the aliases every matcher already accepts
 * folded together: Cmd and Meta are one modifier, so are Ctrl and Control, and so are Alt and
 * Option. Order and case do not survive.
 *
 * One definition because there were three that had to agree - `KeymapMatcher.RequiredModifiers`,
 * `AWTKeyboardInterceptor.chordMatchesEvent` and the migration's chord comparison - and the
 * third had drifted: a hand-edited keymap written with "Meta" read as rebound and silently
 * missed its alternate top-up, though both matchers would have fired it.
 */
internal fun canonicalModifiers(modifiers: List<String>): Set<String> =
    modifiers
        .mapTo(mutableSetOf()) { modifier ->
            when (val lower = modifier.lowercase()) {
                "cmd", "meta" -> "cmd"
                "ctrl", "control" -> "ctrl"
                "alt", "option" -> "alt"
                else -> lower
            }
        }

/**
 * The one name a key answers to, with every spelling the codebase can produce folded together.
 *
 * Three vocabularies reach this: Compose's `Key` property names, which the presets store
 * ("DirectionLeft", "OpenBracket"); Compose's rendered display names ("Left Bracket", the arrow
 * glyphs, U+2423 for space), which the Compose matcher sees; and AWT's `getKeyText` output plus
 * the older `"Left"` spelling a keymap file written by an earlier build still carries. They are
 * the same keys, so a comparison that does not fold them answers "different chord" for one the
 * user experiences as identical.
 *
 * The result is an opaque comparison key, not a display string. Case is not preserved.
 */
internal fun canonicalKeyName(keyName: String): String {
    val lower = keyName.lowercase()
    return KEY_ALIASES[lower] ?: lower
}

/**
 * Every spelling that is not already its own canonical name, keyed lowercase.
 *
 * A table rather than a `when` so adding a spelling is a one-line edit and the function stays
 * trivial. Anything absent canonicalises to itself, lowercased, which is what makes the presets'
 * own vocabulary the default answer.
 */
private val KEY_ALIASES: Map<String, String> =
    buildMap {
        fun alias(
            canonical: String,
            vararg spellings: String,
        ) = spellings.forEach { put(it, canonical) }

        alias("directionleft", "left", "arrowleft", "←")
        alias("directionright", "right", "arrowright", "→")
        alias("directionup", "up", "arrowup", "↑")
        alias("directiondown", "down", "arrowdown", "↓")
        alias("space", "spacebar", "␣", " ")
        alias("escape", "esc")
        alias("enter", "return")
        // A dedicated + key and Shift+= are the same chord to every preset: zoom in is stored as
        // Equals with a Cmd+Shift+Equals alternate.
        alias("equals", "plus", "+", "=")
        alias("minus", "-")
        // Both spaced spellings: Compose renders the bracket keys "Left Bracket"/"Right Bracket"
        // on some platforms and "Open Bracket"/"Close Bracket" on others, and the presets store
        // Compose's own Key property names. Dropping either half takes Cmd+[ and Cmd+] out on
        // exactly the platforms that use the spelling you dropped, which a macOS-only test run
        // cannot see - CI caught this one.
        alias("openbracket", "open bracket", "left bracket", "leftbracket", "[")
        alias("closebracket", "close bracket", "right bracket", "rightbracket", "]")
        // Shift+/ reports "?" on a US layout.
        alias("slash", "/", "?")
        alias("backslash", "\\")
        alias("semicolon", ";")
        alias("apostrophe", "'")
        alias("comma", ",")
        alias("period", ".")
        alias("grave", "`")
        // Digit characters against the word forms the presets store ("One" for Cmd+1).
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
            .forEachIndexed { digit, word -> put(digit.toString(), word) }
    }

/**
 * Represents a single key combination (key + modifiers).
 * Used to support multiple key combos per action (e.g., Cmd+C AND Ctrl+C).
 *
 * @property key The key name (e.g., "N", "T", "Space")
 * @property modifiers List of modifier key names (e.g., ["Cmd", "Shift"])
 */
@Serializable
data class KeyStroke(
    val key: String,
    val modifiers: List<String> = emptyList(),
) {
    /**
     * Returns a display string for this keystroke.
     */
    fun displayString(platform: String = System.getProperty("os.name")): String {
        val isMac = platform.contains("Mac", ignoreCase = true)

        val modifierStrings =
            modifiers.map { modifier ->
                when (modifier.lowercase()) {
                    "cmd", "meta" -> if (isMac) "⌘" else "Ctrl"
                    "ctrl", "control" -> if (isMac) "⌃" else "Ctrl"
                    "shift" -> if (isMac) "⇧" else "Shift"
                    "alt", "option" -> if (isMac) "⌥" else "Alt"
                    else -> modifier
                }
            }

        val keyString = formatKeyDisplay(key)
        return (modifierStrings + keyString).joinToString(if (isMac) "" else "+")
    }

    /**
     * Formats the key name for display.
     */
    private fun formatKeyDisplay(keyName: String): String =
        when (keyName.lowercase()) {
            "space", "spacebar" -> "Space"
            "arrowleft", "directionleft" -> "←"
            "arrowright", "directionright" -> "→"
            "arrowup", "directionup" -> "↑"
            "arrowdown", "directiondown" -> "↓"
            "enter", "return" -> "↩"
            "backspace" -> "⌫"
            "delete" -> "⌦"
            "escape", "esc" -> "Esc"
            "tab" -> "Tab"
            else -> keyName.uppercase()
        }

    /**
     * Returns a signature for conflict detection.
     * Format: "modifiers+key"
     */
    fun signature(): String {
        // Canonicalised on both halves, not merely sorted and uppercased. Everything that asks
        // "is this the same chord" compares signatures - KeymapValidator's conflict grouping,
        // KeymapPresets.claimsChord, the migration's chord check - while findMatchingBinding
        // folds aliases when it matches. A file spelling a chord ["Meta","Option"]+"Right" is
        // the same chord to the matcher and used to be a different one here, which let migration
        // add a second action onto it: a chord that then neither works nor shows a badge.
        val modifierStr = canonicalModifiers(modifiers).sorted().joinToString("+")
        val keyStr = canonicalKeyName(key)
        return if (modifierStr.isNotEmpty()) "$modifierStr+$keyStr" else keyStr
    }

    /**
     * Checks if this keystroke matches the given key event properties.
     */
    fun matches(
        eventKey: String,
        isMetaPressed: Boolean,
        isCtrlPressed: Boolean,
        isShiftPressed: Boolean,
        isAltPressed: Boolean,
    ): Boolean {
        // Check if key matches
        if (!key.equals(eventKey, ignoreCase = true)) return false

        // Check modifiers
        val hasCmd = modifiers.any { it.equals("Cmd", true) || it.equals("Meta", true) }
        val hasCtrl = modifiers.any { it.equals("Ctrl", true) || it.equals("Control", true) }
        val hasShift = modifiers.any { it.equals("Shift", true) }
        val hasAlt = modifiers.any { it.equals("Alt", true) || it.equals("Option", true) }

        return (hasCmd == isMetaPressed) &&
            (hasCtrl == isCtrlPressed) &&
            (hasShift == isShiftPressed) &&
            (hasAlt == isAltPressed)
    }

    companion object {
        /**
         * Creates a KeyStroke from key name and modifier strings.
         */
        fun of(
            key: String,
            vararg modifiers: String,
        ): KeyStroke = KeyStroke(key, modifiers.toList())
    }
}

/**
 * Represents a single keyboard shortcut binding.
 *
 * @property actionId Unique identifier for the action (e.g., "window.new", "tab.close")
 * @property key The primary key name (e.g., "N", "T", "Space", "ArrowLeft")
 * @property modifiers List of modifier key names (e.g., ["Cmd", "Shift"], ["Ctrl", "Alt"])
 * @property alternateKeystrokes Additional key combinations that also trigger this action
 * @property context The context where this shortcut is active
 * @property enabled Whether this shortcut is currently enabled
 * @property category The category this shortcut belongs to (for UI grouping)
 * @property description Human-readable description of what this shortcut does
 */
@Serializable
data class KeyBinding(
    val actionId: String,
    val key: String,
    val modifiers: List<String> = emptyList(),
    val alternateKeystrokes: List<KeyStroke> = emptyList(),
    val context: ShortcutContext = ShortcutContext.GLOBAL,
    val enabled: Boolean = true,
    val category: String = "Other",
    val description: String = "",
) {
    /**
     * Returns the primary keystroke for this binding.
     *
     * Computed once rather than per access: both matchers now walk [allKeystrokes] for every
     * binding on every modified keypress, and as getters these allocated a KeyStroke and a list
     * per binding per event.
     *
     * `@Transient` is load-bearing, not decoration. kotlinx.serialization takes any property
     * with a backing field, body properties included, so without it the descriptor would grow
     * two elements and `keymap-settings.json` - documented here as hand-editable - could carry
     * an `allKeystrokes` that both matchers then consult in place of an edited `key`. A rebind
     * that appears to do nothing, with no conflict badge to explain it.
     */
    @Transient
    val primaryKeystroke: KeyStroke = KeyStroke(key, modifiers)

    /** Returns all keystrokes (primary + alternates) for this binding. See [primaryKeystroke]. */
    @Transient
    val allKeystrokes: List<KeyStroke> = listOf(primaryKeystroke) + alternateKeystrokes

    /**
     * Returns a display string for this key binding (primary keystroke only).
     * Examples: "Cmd+N", "Ctrl+Shift+T", "Alt+Left"
     */
    fun displayString(platform: String = System.getProperty("os.name")): String = primaryKeystroke.displayString(platform)

    /**
     * Returns a display string showing all keystrokes (primary + alternates).
     * Examples: "⌘N / Ctrl+N", "⌘⇧T / Ctrl+Shift+T"
     */
    fun displayStringAll(platform: String = System.getProperty("os.name")): String =
        allKeystrokes.joinToString(" / ") { it.displayString(platform) }

    /**
     * Checks if this key binding matches the given key event properties.
     * Checks against primary keystroke and all alternate keystrokes.
     */
    fun matches(
        eventKey: String,
        isMetaPressed: Boolean,
        isCtrlPressed: Boolean,
        isShiftPressed: Boolean,
        isAltPressed: Boolean,
    ): Boolean {
        if (!enabled) return false

        // Check against all keystrokes (primary + alternates)
        return allKeystrokes.any { keystroke ->
            keystroke.matches(eventKey, isMetaPressed, isCtrlPressed, isShiftPressed, isAltPressed)
        }
    }

    /**
     * Returns a unique signature for this key binding's primary keystroke (for conflict detection).
     * Format: "context:modifiers+key"
     * Example: "GLOBAL:Cmd+Shift+N"
     */
    fun signature(): String = "${context.name}:${primaryKeystroke.signature()}"

    /**
     * Returns all signatures for this key binding (primary + alternates).
     * Used for comprehensive conflict detection.
     */
    fun allSignatures(): List<String> = allKeystrokes.map { "${context.name}:${it.signature()}" }

    /**
     * Creates a copy with an additional alternate keystroke.
     */
    fun withAlternateKeystroke(keystroke: KeyStroke): KeyBinding = copy(alternateKeystrokes = alternateKeystrokes + keystroke)

    /**
     * Creates a copy with an additional alternate keystroke from key and modifiers.
     */
    fun withAlternateKeystroke(
        key: String,
        vararg modifiers: String,
    ): KeyBinding = withAlternateKeystroke(KeyStroke(key, modifiers.toList()))

    /**
     * Creates a copy without the specified alternate keystroke.
     */
    fun withoutAlternateKeystroke(keystroke: KeyStroke): KeyBinding =
        copy(alternateKeystrokes = alternateKeystrokes.filter { it != keystroke })

    /**
     * Creates a copy with all alternate keystrokes cleared.
     */
    fun clearAlternateKeystrokes(): KeyBinding = copy(alternateKeystrokes = emptyList())

    /**
     * Checks if this binding has any alternate keystrokes.
     */
    val hasAlternates: Boolean
        get() = alternateKeystrokes.isNotEmpty()

    companion object {
        /**
         * Creates a KeyBinding from a Compose Key object and modifiers.
         */
        fun fromComposeKey(
            actionId: String,
            key: Key,
            isMetaPressed: Boolean,
            isCtrlPressed: Boolean,
            isShiftPressed: Boolean,
            isAltPressed: Boolean,
            context: ShortcutContext = ShortcutContext.GLOBAL,
            category: String = "Other",
            description: String = "",
        ): KeyBinding {
            val modifiers = mutableListOf<String>()
            if (isMetaPressed) modifiers.add("Cmd")
            if (isCtrlPressed) modifiers.add("Ctrl")
            if (isShiftPressed) modifiers.add("Shift")
            if (isAltPressed) modifiers.add("Alt")

            return KeyBinding(
                actionId = actionId,
                key = key.keyCode.toString(),
                modifiers = modifiers,
                context = context,
                enabled = true,
                category = category,
                description = description,
            )
        }

        // `crossPlatform(actionId, key, ...)` used to live here, manufacturing a Ctrl+<key>
        // alternate beside a Cmd+<key> primary. It was inert while alternateKeystrokes was
        // consulted by neither matcher; now that both walk allKeystrokes it would be wrong in
        // both directions, so it is gone rather than left as a trap. On macOS the alternate
        // really fires, so Ctrl+N would open a window as well as Cmd+N. On Windows and Linux
        // "Ctrl" maps to isMetaDown, so the alternate demands the Super key and is unreachable
        // - while the Cmd primary already matches the Control key, which is the whole thing the
        // helper was reaching for. No preset ever used it.

        /**
         * Creates a KeyBinding with multiple keystrokes.
         */
        fun withMultipleKeystrokes(
            actionId: String,
            primaryKey: String,
            primaryModifiers: List<String>,
            alternates: List<KeyStroke>,
            context: ShortcutContext = ShortcutContext.GLOBAL,
            category: String = "Other",
            description: String = "",
        ): KeyBinding =
            KeyBinding(
                actionId = actionId,
                key = primaryKey,
                modifiers = primaryModifiers,
                alternateKeystrokes = alternates,
                context = context,
                enabled = true,
                category = category,
                description = description,
            )
    }
}
