package ai.rever.boss.ipc

/**
 * Makes text from an IPC message safe to put inside ONE kernel log record.
 *
 * The kernel log is line-oriented: the in-app log panel, the console capture, `boss log-parse` and
 * any grep or tail treat a newline as the end of a record. IPC messages carry text the kernel did
 * not author (a registration manifest's display name, a state key, a supervisor's shutdown target),
 * and the identity layer proves who sent a message, not what it says. An unescaped line break in
 * any of those fields lets a child make the kernel print a second line that looks exactly like a
 * record the kernel produced (CWE-117) - a forged ERROR about a sibling, a fake audit line.
 * Terminal escapes act on whoever reads the console, and bidi overrides and zero-width characters
 * change what a line appears to say without changing its bytes.
 *
 * Line breaks become `\n` / `\r`, backslashes become `\\`, and the rest become UTF-16 `\uXXXX`
 * escapes. Nothing is dropped, so the original is recoverable from the escaped form. The message
 * the service stores is left untouched; only what the kernel prints is neutralized. A tab is kept
 * as ordinary layout inside one line.
 *
 * Internal on purpose, and a twin rather than a dependency: the plugin api's `LogLineText` (PR
 * #1055) guards the same invariant for the BossLogger render path, but boss-ipc is a lower-level
 * published artifact that must not depend on plugin-logging, and that helper is internal to its
 * jar anyway. The hidden-character categories mirror `CLISecurityValidator` in composeApp;
 * boss-ipc cannot depend on that application module.
 */
internal object IpcLogText {
    fun neutralize(text: String): String {
        if (text.codePoints().noneMatch { it == '\\'.code || needsEscaping(it) }) return text
        return buildString(text.length + 16) {
            val codePoints = text.codePoints().iterator()
            while (codePoints.hasNext()) {
                val codePoint = codePoints.nextInt()
                when {
                    codePoint == '\n'.code -> append("\\n")
                    codePoint == '\r'.code -> append("\\r")
                    codePoint == '\\'.code -> append("\\\\")
                    needsEscaping(codePoint) -> Character.toChars(codePoint).forEach { appendEscaped(it) }
                    else -> appendCodePoint(codePoint)
                }
            }
        }
    }

    // Match CLISecurityValidator's CONTROL, FORMAT, LINE_SEPARATOR and PARAGRAPH_SEPARATOR
    // categories for full code points. Also escape other Unicode whitespace. Spaces and tabs
    // remain legible layout inside one log record.
    private fun needsEscaping(codePoint: Int): Boolean =
        codePoint != SPACE &&
            codePoint != '\t'.code &&
            (
                Character.isWhitespace(codePoint) ||
                    Character.isSpaceChar(codePoint) ||
                    Character.getType(codePoint) == Character.CONTROL.toInt() ||
                    Character.getType(codePoint) == Character.FORMAT.toInt()
            )

    private fun StringBuilder.appendEscaped(character: Char) {
        append("\\u")
        append(character.code.toString(HEX_RADIX).padStart(HEX_WIDTH, '0'))
    }

    private const val SPACE = 0x20
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4
}
