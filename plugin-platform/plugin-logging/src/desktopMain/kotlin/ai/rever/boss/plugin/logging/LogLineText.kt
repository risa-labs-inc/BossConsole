package ai.rever.boss.plugin.logging

/**
 * Makes text safe to put inside ONE log record.
 *
 * A log file is line-oriented: whatever reads it treats a newline as the end of a record. Log text
 * routinely includes strings the app did not write (URLs, file names, plugin ids, an origin's error
 * body), so an unescaped line break lets whoever controls one of them write a second line that looks
 * exactly like a record the logger produced (CWE-117). The other characters here are not line breaks
 * but are equally not text: terminal escapes act on whoever `cat`s the file or watches the console,
 * and bidi overrides and zero-width characters change what a line appears to say without changing
 * its bytes.
 *
 * Line breaks become `\n` / `\r`, the rest become `\uXXXX`. Nothing is dropped, so the original is
 * still recoverable from the escaped form. A tab is kept: it is ordinary layout inside one line.
 *
 * Internal on purpose. `LogSanitizer` and `BossLogger` are also shipped in the plugin api jar, and a
 * new public member here would exist for the host and not for a plugin compiled against that jar.
 */
internal object LogLineText {
    fun neutralize(text: String): String {
        if (text.none(::needsEscaping)) return text
        return buildString(text.length + 16) {
            for (c in text) {
                when {
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    needsEscaping(c) -> append("\\u").append(c.code.toString(HEX_RADIX).padStart(HEX_WIDTH, '0'))
                    else -> append(c)
                }
            }
        }
    }

    private fun needsEscaping(c: Char): Boolean {
        val code = c.code
        return when {
            c == '\t' -> false
            code < SPACE -> true
            code == DEL || code in C1_START..C1_END -> true
            else -> c in INVISIBLE_OR_DIRECTIONAL
        }
    }

    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4
    private const val SPACE = 0x20
    private const val DEL = 0x7f
    private const val C1_START = 0x80
    private const val C1_END = 0x9f

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
