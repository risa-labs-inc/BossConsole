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
 * The prefix `Key.toString()` renders in front of a key's name.
 *
 * Compose exposes no accessor for the name itself, so anything that needs one reads it back out
 * of the rendered string. Two places did that with their own copy of the offset; naming it once
 * means they cannot disagree about where the name starts.
 */
private const val KEY_RENDER_PREFIX = "Key: "

/**
 * The name Compose renders for a [Key], which is one of the two key vocabularies in the app.
 *
 * The other is `AWTKeyboardInterceptor.getKeyName`. Neither is derived from the other, and where
 * they disagree a chord fires on one path and silently does nothing on the other;
 * [canonicalKeyName] is the fold that reconciles them and `KeyVocabularyAgreementTest` walks both.
 *
 * Two things about this rendering are worth knowing before depending on it. It is not the
 * presets' vocabulary - the left arrow renders "Left" against their "DirectionLeft". And it is
 * not stable: `Key.toString()` falls through to AWT's `getKeyText`, which answers with a word
 * while the toolkit is cold and with the macOS glyph once it is up, so `Key.Tab` is "Tab" in a
 * headless test and "⇥" in a running app. Anything PERSISTING a name wants the fold, not this.
 *
 * `Key.keyCode` is a packed Long rather than a name, so it is a last resort here and only for a
 * future rendering that does not carry this prefix. Current Compose desktop always prefixes
 * the text; unnamed native codes render as AWT diagnostic strings instead of numbers.
 */
internal fun composeKeyName(key: Key): String {
    val rendered = key.toString()
    return if (rendered.startsWith(KEY_RENDER_PREFIX)) {
        rendered.substring(KEY_RENDER_PREFIX.length).trim()
    } else {
        key.keyCode.toString()
    }
}

/**
 * The name a captured [Key] should be STORED under, as opposed to merely compared by.
 *
 * [composeKeyName] gives whatever Compose renders, which is not the vocabulary the presets use:
 * the left arrow renders "Left" against the presets' "DirectionLeft", the right bracket renders
 * "Close Bracket" against "CloseBracket", and the 1 key renders "1" against "One". Every one of
 * those MATCHES, because [canonicalKeyName] folds them - but none of them DISPLAYS the same,
 * because `KeyStroke.formatKeyDisplay` knows "directionleft" and not "left". Stored raw, a
 * rebound arrow would sit in the Shortcuts list as "⌘LEFT" beside a preset's "⌘←".
 *
 * Folding first makes a rebind indistinguishable from a preset binding everywhere: same match,
 * same signature, same rendering. It also makes the stored value STABLE, which the raw rendering
 * is not: `Key.toString()` falls through to AWT's `getKeyText`, so the same user rebinding Tab
 * gets "Tab" or "⇥" in their file depending on whether the toolkit was up when they did it.
 *
 * Case is the one thing the fold does not carry, so the file
 * gains "directionleft" where a preset has "DirectionLeft" - every comparison in the keymap is
 * case-insensitive, and a lowercase name a reader can recognise beats a correctly-cased one
 * nobody can.
 */
internal fun storedKeyName(key: Key): String = canonicalKeyName(composeKeyName(key))

/**
 * The key name a stored packed `Key.keyCode` stands for, or null when [stored] is not one.
 *
 * Every keymap ever saved through the Shortcuts screen carries these, so two callers need the
 * answer: [canonicalKeyName], so an unmigrated file still MATCHES, and the settings migration,
 * so the file stops holding a fifteen-digit key and the Shortcuts list stops rendering one.
 * Both want the folded name, for the reason [storedKeyName] gives.
 *
 * Guarded to strings no key name can be - two or more characters, all digits. The single-digit
 * spellings are claimed by [KEY_ALIASES] before this is reached, and Compose names no key in
 * digits alone, so this cannot shadow a real name.
 */
internal fun keyNameForStoredKeyCode(stored: String): String? {
    if (stored.length < 2 || !stored.all { it.isDigit() }) return null
    // Check before folding so a numeric rendering cannot recurse through canonicalKeyName.
    // Current desktop Compose uses an AWT diagnostic for unknown keys instead.
    return stored
        .toLongOrNull()
        // Native AWT key codes are non-negative; negative low bits can render invalid Unicode.
        ?.takeIf { it.toInt() >= 0 }
        ?.let { composeKeyName(Key(it)) }
        // AWT names unknown codes with a diagnostic placeholder. That is not a recoverable
        // key name and must not replace the original value in a user's settings file.
        ?.takeIf { it != stored && !it.contains(" keyCode: 0x", ignoreCase = true) }
        ?.let { canonicalKeyName(it) }
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
    // The keyCode branch is for a packed `Key.keyCode`, written by every pre-#329 rebind. It is
    // folded here rather than only migrated because the migration rewrites ONE file: a keymap
    // restored from a backup, copied off another machine, or exported and re-imported reaches the
    // matchers before it reaches the migration, and the whole failure is a rebind that silently
    // does nothing.
    return KEY_ALIASES[lower] ?: keyNameForStoredKeyCode(lower) ?: lower
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
        // Compose renders these two ways on one machine. `Key.toString()` falls through to AWT's
        // `getKeyText`, which answers with a word while the toolkit is cold and with the macOS
        // glyph once it is up - so "Tab" and "⇥" are both real spellings of one key, and which
        // one you get depends on nothing the user did.
        //
        // The GLYPH is what a running app produces, so these were live on the Compose matcher
        // path before any of this: `KeymapMatcher` derives the event's name from `Key.toString()`
        // and compares it against the preset's, so Ctrl+Tab and Ctrl+Shift+Tab - TAB_NEXT and
        // TAB_PREVIOUS, shipped in all four presets - asked whether "⇥" was "Tab" and were told
        // no. They survive on the AWT interceptor, which says "Tab" on both sides. Measured warm
        // and cold; `KeyVocabularyAgreementTest` covers whichever one an environment renders and
        // `CanonicalKeyNameTest` enumerates both.
        //
        // The arrow and space glyphs above are here for the same reason; these are the rest.
        alias("escape", "esc", "⎋")
        alias("enter", "return", "⏎")
        alias("tab", "⇥")
        alias("backspace", "⌫")
        alias("delete", "⌦")
        alias("home", "movehome", "↖")
        alias("end", "moveend", "↘")
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
        // AWT can name this key "Back Slash" while hand-edited files use the character.
        // Accept both without depending on whether a platform installs a display override.
        alias("backslash", "back slash", "\\")
        alias("semicolon", ";")
        alias("comma", ",")
        alias("period", ".")
        // Cold spellings again, in the same shape: Compose renders "Quote", "Back Quote",
        // "Page Up" and "Page Down" where the interceptor says "Apostrophe", "Grave", "PageUp"
        // and "PageDown". A running app renders "'" and "`" for the first two (already folded)
        // and the glyphs above for the page keys. `KeyVocabularyAgreementTest` walks both tables
        // so the next divergence fails a build rather than a keystroke.
        alias("apostrophe", "quote", "'")
        alias("grave", "back quote", "`")
        alias("pageup", "page up", "⇞")
        alias("pagedown", "page down", "⇟")
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
                // The name, not `key.keyCode` - see [composeKeyName]. Same defect as the capture
                // dialog's (#329); this copy has no caller today, which is exactly why it would
                // have been the one to survive.
                key = storedKeyName(key),
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
