package ai.rever.boss.cli

/**
 * Text that came from somewhere other than the operator, made safe to print to their terminal.
 *
 * The CLI's human reports are read by an operator deciding what to trust: an audit ledger, a tool
 * catalogue, a health report. Every string in them that originates outside BOSS - a tool's error
 * text, a plugin's tool name or description, a project directory name - is attacker-influenceable,
 * and printed raw it does two things. A newline starts a second, perfectly formatted "record" or
 * "tool" that was never there, and an ESC sequence acts on the terminal itself: clearing the screen
 * over what the operator was about to read, retitling the window, or (on terminals that honour
 * OSC 52) writing the clipboard. JSON output is unaffected, because its serializer already escapes
 * control characters; this is for the human renderings.
 */
internal object TerminalText {
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4
    private const val ESCAPE_HEADROOM = 16

    /**
     * [text] with every character that could act on a terminal, or on the reader, written out as a
     * visible `\uXXXX` escape instead, and line breaks and tabs as `\n`, `\r` and `\t`.
     *
     * Escaped rather than dropped, so the operator can see that something was there. Covered are the
     * C0 and C1 controls including DEL, and the Unicode characters that reorder or hide text: the
     * bidi embeddings, overrides and isolates (which can make one name read as another), zero-width
     * characters and the line and paragraph separators. Ordinary text, including accents, CJK and
     * emoji, is returned unchanged.
     */
    fun safe(text: String): String = escape(text, keepLineBreaks = false)

    /**
     * [text] split into lines, each escaped as [safe] does, but with real line breaks kept as the
     * split points. For a value that is legitimately several lines, such as a tool's description.
     * Only the caller can say how each line is then presented, so it must indent or prefix every
     * one of them; [safeIndented] does that.
     */
    fun safeLines(text: String): List<String> = escape(text.replace("\r\n", "\n"), keepLineBreaks = true).split('\n')

    /** [text] as [safeLines], with [indent] in front of every line, so no line can pass for a heading. */
    fun safeIndented(
        text: String,
        indent: String,
    ): String = safeLines(text).joinToString("\n") { indent + it }

    private fun escape(
        text: String,
        keepLineBreaks: Boolean,
    ): String {
        if (text.none { it in "\n\r\t" || needsEscaping(it) }) return text
        return buildString(text.length + ESCAPE_HEADROOM) {
            for (ch in text) {
                when {
                    ch == '\n' && keepLineBreaks -> append('\n')
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    needsEscaping(ch) -> append("\\u").append(ch.code.toString(HEX_RADIX).padStart(HEX_WIDTH, '0'))
                    else -> append(ch)
                }
            }
        }
    }

    private fun needsEscaping(ch: Char): Boolean =
        ch.code < 0x20 ||
            ch.code in 0x7f..0x9f ||
            ch.code in 0x200b..0x200f ||
            ch.code in 0x2028..0x202e ||
            ch.code in 0x2066..0x2069 ||
            ch.code == 0x061c ||
            ch.code == 0xfeff
}
