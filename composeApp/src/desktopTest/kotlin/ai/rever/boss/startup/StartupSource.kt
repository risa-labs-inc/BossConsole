package ai.rever.boss.startup

/** Mask imports, comments and literal text, retaining executed template expressions and offsets. */
internal fun startupCodeOnly(source: String): String = StartupSourceScanner(source).read()

private class StartupSourceScanner(
    private val source: String,
) {
    private val code = source.toCharArray()
    private var index = 0

    fun read(): String {
        readCode(braced = false)
        Regex("(?m)^[ \\t]*(?:import|package)\\b[^\\r\\n]*").findAll(code.concatToString()).forEach {
            blank(it.range.first, it.range.last + 1)
        }
        return code.concatToString()
    }

    private fun readCode(braced: Boolean) {
        var depth = 1
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    skip(source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length)
                }

                source.startsWith("/*", index) -> {
                    skip(blockCommentEnd(source, index))
                }

                source[index] == '"' -> {
                    readString()
                }

                source[index] == '\'' -> {
                    skip(characterEnd(source, index))
                }

                braced && source[index] == '{' -> {
                    depth++
                    index++
                }

                braced && source[index] == '}' -> {
                    index++
                    if (--depth == 0) return
                }

                else -> {
                    index++
                }
            }
        }
        check(!braced) { "Unterminated Kotlin string template at $index" }
    }

    private fun readString() {
        val start = index
        val raw = source.startsWith("\"\"\"", index)
        val delimiter = if (raw) "\"\"\"" else "\""
        var literalStart = index
        index += delimiter.length
        while (index < source.length) {
            when {
                source.startsWith(delimiter, index) -> {
                    index += delimiter.length
                    blank(literalStart, index)
                    return
                }

                source.startsWith("\${", index) -> {
                    blank(literalStart, index + 2)
                    index += 2
                    readCode(braced = true)
                    literalStart = index
                }

                !raw && source[index] == '\\' -> {
                    index += 2
                }

                else -> {
                    index++
                }
            }
        }
        error("Unterminated Kotlin string at $start")
    }

    private fun skip(end: Int) {
        blank(index, end)
        index = end
    }

    private fun blank(
        start: Int,
        end: Int,
    ) {
        for (position in start until end) {
            if (code[position] != '\n' && code[position] != '\r') code[position] = ' '
        }
    }
}

private fun blockCommentEnd(
    source: String,
    start: Int,
): Int {
    var depth = 1
    var index = start + 2
    while (index < source.length && depth > 0) {
        when {
            source.startsWith("/*", index) -> {
                depth++
                index += 2
            }

            source.startsWith("*/", index) -> {
                depth--
                index += 2
            }

            else -> {
                index++
            }
        }
    }
    check(depth == 0) { "Unterminated Kotlin comment at $start" }
    return index
}

private fun characterEnd(
    source: String,
    start: Int,
): Int {
    var index = start + 1
    while (index < source.length) {
        when (source[index]) {
            '\\' -> index += 2
            '\'' -> return index + 1
            else -> index++
        }
    }
    error("Unterminated Kotlin character at $start")
}
