package ai.rever.boss.startup

/** Remove Kotlin comments and literals while preserving offsets for the startup-order assertions. */
internal fun startupCodeOnly(source: String): String {
    val code = source.toCharArray()
    var index = 0
    while (index < source.length) {
        val end =
            when {
                source.startsWith("//", index) -> {
                    source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                }

                source.startsWith("/*", index) -> {
                    blockCommentEnd(source, index)
                }

                source.startsWith("\"\"\"", index) -> {
                    source.indexOf("\"\"\"", index + 3).takeIf { it >= 0 }?.plus(3) ?: source.length
                }

                source[index] == '"' || source[index] == '\'' -> {
                    quotedEnd(source, index)
                }

                else -> {
                    index
                }
            }
        if (end > index) {
            blankRange(code, index, end)
            index = end
        } else {
            index++
        }
    }
    return code.concatToString()
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
    return index
}

private fun quotedEnd(
    source: String,
    start: Int,
): Int {
    var index = start + 1
    while (index < source.length) {
        when (source[index]) {
            '\\' -> index += 2
            source[start] -> return index + 1
            else -> index++
        }
    }
    return source.length
}

private fun blankRange(
    code: CharArray,
    start: Int,
    end: Int,
) {
    for (position in start until end) {
        if (code[position] != '\n' && code[position] != '\r') code[position] = ' '
    }
}
