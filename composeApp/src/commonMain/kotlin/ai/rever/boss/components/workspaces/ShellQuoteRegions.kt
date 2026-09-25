package ai.rever.boss.components.workspaces

/** Quote state before each character in a command template. NUL means unquoted. */
internal class ShellQuoteRegions private constructor(
    private val before: CharArray,
) {
    val balanced: Boolean get() = before.last() == NONE

    fun quoteBefore(index: Int): Char? = before[index].takeUnless { it == NONE }

    companion object {
        private const val NONE = '\u0000'

        /** [escape] is backslash for POSIX and backtick for PowerShell. */
        fun scan(
            text: CharSequence,
            escape: Char,
        ): ShellQuoteRegions {
            val before = CharArray(text.length + 1)
            var quote = NONE
            var index = 0
            while (index < text.length) {
                before[index] = quote
                val character = text[index]
                val paired =
                    isEscapedNext(character, quote, escape, index, text.length) ||
                        isDoubledApostrophe(character, quote, escape, index, text)
                if (paired) {
                    before[index + 1] = quote
                    index += 2
                } else {
                    quote = nextQuote(quote, character)
                    index++
                }
            }
            before[text.length] = quote
            return ShellQuoteRegions(before)
        }

        private fun isEscapedNext(
            character: Char,
            quote: Char,
            escape: Char,
            index: Int,
            length: Int,
        ): Boolean = quote != '\'' && character == escape && index + 1 < length

        private fun isDoubledApostrophe(
            character: Char,
            quote: Char,
            escape: Char,
            index: Int,
            text: CharSequence,
        ): Boolean {
            if (escape != '`' || quote != '\'') return false
            return character == '\'' && text.getOrNull(index + 1) == '\''
        }

        private fun nextQuote(
            quote: Char,
            character: Char,
        ): Char =
            when {
                quote == NONE && (character == '\'' || character == '"') -> character
                character == quote -> NONE
                else -> quote
            }
    }
}
