package ai.rever.boss.keymap.model

/**
 * A keystroke rendered for display: "⌘⇧P" on macOS, "Ctrl+Shift+P" elsewhere.
 *
 * **Shared because there are now two callers and there used to be a third that lied.** Global
 * search formats the binding it shows next to each command, and the home screen's tool grid
 * shows the binding for the actions that have one. The old home screen instead carried string
 * literals - `shortcut = "Cmd+O"`, `"Cmd+P"`, `"Cmd+\`"` - which named no real action (there is
 * no `FILE_OPEN`, `PROJECT_OPEN` or `TERMINAL_NEW` in [KeymapActions]), said "Cmd" on Windows and
 * Linux, and went stale the moment anyone rebound anything.
 *
 * Reads the modifier names the keymap persists (`cmd`, `ctrl`, `shift`, `alt`, and the `meta` /
 * `control` / `option` spellings that also appear in stored bindings), and passes anything else
 * through unchanged rather than dropping it.
 */
fun formatShortcutLabel(
    modifiers: List<String>,
    key: String,
): String = KeyStroke(canonicalKeyName(key), modifiers).displayString()

/**
 * The display label for [actionId]'s current binding, or null when it has none.
 *
 * Null rather than a placeholder: a tile with no binding should show nothing, which is the whole
 * correction here. A binding that exists but is disabled also counts as none.
 */
fun shortcutLabelFor(
    actionId: String,
    bindings: Map<String, KeyBinding>,
): String? =
    bindings[actionId]
        ?.takeIf { it.enabled }
        ?.let { formatShortcutLabel(it.modifiers, it.key) }
