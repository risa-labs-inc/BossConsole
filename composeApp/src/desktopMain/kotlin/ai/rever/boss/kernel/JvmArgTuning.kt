package ai.rever.boss.kernel

/**
 * Merge orchestrator tuning into the process's own JVM args. A tuned heap flag replaces its
 * original counterpart, but only ever upward: RESTART_TUNED fires on OutOfMemoryError, and
 * swapping a larger existing heap for a smaller tuned one would deepen the crash loop it is
 * meant to break. A tuned -Xmx with no explicit -Xmx is dropped because it could shrink the
 * JVM's default max heap. A tuned -Xms needs an explicit, readable -Xmx; otherwise it could
 * exceed the JVM's default max and make the process fail to start. Other new flags are
 * appended; every other original arg (GC flags, --add-opens, ...) survives unchanged.
 */
internal fun mergeTunedJvmArgs(
    original: List<String>,
    tuned: List<String>,
): List<String> {
    val result = original.toMutableList()
    for (arg in tuned) {
        mergeTunedArg(result, arg)
    }
    // A tuned -Xms may not end up above the final -Xmx: the JVM refuses to launch with
    // Xms > Xmx, which would turn the remedy into the crash loop it is meant to break.
    // Raise -Xmx to match - never lower either value; upward-only applies to both flags.
    val xmsIdx = result.indexOfLast { heapFlagKey(it) == "-Xms" }
    val xmxIdx = result.indexOfLast { heapFlagKey(it) == "-Xmx" }
    if (xmsIdx >= 0 && xmxIdx >= 0 && isRaise(result[xmxIdx], result[xmsIdx])) {
        result[xmxIdx] = "-Xmx" + result[xmsIdx].removePrefix("-Xms")
    }
    return result
}

private fun mergeTunedArg(
    result: MutableList<String>,
    arg: String,
) {
    val key = heapFlagKey(arg)
    val max = result.lastOrNull { heapFlagKey(it) == "-Xmx" }
    if (key == null) {
        if (arg !in result) result += arg
    } else if (key != "-Xms" || max?.let(::heapBytes) != null) {
        // Raising Xms is safe only when the effective max is explicit and readable.
        val idx = result.indexOfLast { heapFlagKey(it) == key }
        if (idx >= 0) {
            if (isRaise(result[idx], arg)) result[idx] = arg
        } else if (key == "-Xms") {
            result += arg
        }
    }
}

/**
 * True when [candidate] is a strictly larger heap than [current]. An unparsable value on
 * either side is never a raise: an operator's value we can't read is left alone.
 */
private fun isRaise(
    current: String,
    candidate: String,
): Boolean {
    val from = heapBytes(current)
    val to = heapBytes(candidate)
    return from != null && to != null && from < to
}

/** The flag family of a heap arg (`-Xmx` / `-Xms`), or null for anything else. */
private fun heapFlagKey(arg: String): String? =
    when {
        arg.startsWith("-Xmx") -> "-Xmx"
        arg.startsWith("-Xms") -> "-Xms"
        else -> null
    }

/**
 * Heap size of an `-Xmx`/`-Xms` arg in bytes, or null when the value is unparsable.
 *
 * JVM syntax treats a unitless value as bytes, so the unit letter is only consumed after the
 * all-digits case is handled: peeling the last character first would silently drop the last
 * digit of a byte value (`-Xmx8589934592` read as ~819 MB instead of 8192) and the
 * upward-only merge could then swap a larger existing heap for a smaller tuned one. An
 * unrecognised suffix is unparsable rather than "bytes": reading junk as bytes would shrink a
 * heap the operator set deliberately.
 */
private fun heapBytes(arg: String): Long? {
    val raw = arg.removePrefix("-Xmx").removePrefix("-Xms")
    val bytes = raw.toLongOrNull() // all-digits: unitless means bytes
    val number = raw.dropLast(1).toLongOrNull()
    val unit = raw.lastOrNull()?.lowercaseChar()
    return when {
        bytes != null -> bytes.takeIf { it >= 0 }
        number == null || number < 0 -> null
        unit == 't' -> number.timesIfFits(1024L * 1024 * 1024 * 1024)
        unit == 'g' -> number.timesIfFits(1024L * 1024 * 1024)
        unit == 'm' -> number.timesIfFits(1024L * 1024)
        unit == 'k' -> number.timesIfFits(1024L)
        else -> null
    }
}

private fun Long.timesIfFits(factor: Long): Long? = takeIf { it <= Long.MAX_VALUE / factor }?.times(factor)
