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
 * Line breaks become `\n` / `\r`, the rest become `\uXXXX`. Nothing is dropped, so the original is
 * recoverable from the escaped form, and the message the service stores is left untouched - only
 * what the kernel prints is neutralized. A tab is kept: it is ordinary layout inside one line.
 *
 * Internal on purpose, and a twin rather than a dependency: the plugin api's `LogLineText` (PR
 * #1055) guards the same invariant for the BossLogger render path, but boss-ipc is a lower-level
 * published artifact that must not depend on plugin-logging, and that helper is internal to its
 * jar anyway.
 */
internal object IpcLogText {
    fun neutralize(text: String): String {
        if (text.none(::needsEscaping)) return text
        return buildString(text.length + 16) {
            for (character in text) {
                when {
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    needsEscaping(character) -> appendEscaped(character)
                    else -> append(character)
                }
            }
        }
    }

    private fun needsEscaping(character: Char): Boolean {
        val code = character.code
        return when {
            character == '\t' -> false
            code < SPACE -> true
            code == DEL || code in C1_START..C1_END -> true
            else -> character in INVISIBLE_OR_DIRECTIONAL
        }
    }

    private fun StringBuilder.appendEscaped(character: Char) {
        append("\\u")
        append(character.code.toString(HEX_RADIX).padStart(HEX_WIDTH, '0'))
    }

    private const val SPACE = 0x20
    private const val DEL = 0x7f
    private const val C1_START = 0x80
    private const val C1_END = 0x9f
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4

    /** Line/paragraph separators, zero-width characters, bidi marks, overrides and isolates, BOM. */
    private val INVISIBLE_OR_DIRECTIONAL: Set<Char> =
        buildSet {
            add(0x061c.toChar())
            for (code in 0x200b..0x200f) add(code.toChar())
            for (code in 0x2028..0x202e) add(code.toChar())
            for (code in 0x2060..0x2064) add(code.toChar())
            for (code in 0x2066..0x2069) add(code.toChar())
            add(0xfeff.toChar())
        }
}
