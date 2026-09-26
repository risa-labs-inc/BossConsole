package ai.rever.boss.utils.logging

import kotlinx.serialization.SerializationException

/**
 * Log fields for a local file that failed to decode, with none of the file's content in them.
 *
 * Log these instead of passing the exception as `error = e`. kotlinx appends the whole offending
 * document to a malformed-input error (`JSON input: ...`), and even the diagnostic before it can
 * quote a value (`Failed to parse ... for input '...'`). For the files this is used on, that
 * document is visited URLs with their query strings, the domains a user zoomed, or their keymap,
 * and a log line is what people attach to bug reports (#1629, #1695).
 *
 * So this keeps only what cannot carry the document: the exception type, the offset the decoder
 * stopped at, and the JSON path, with map keys masked because a map key is data (the zoom settings
 * key their map by domain). Both are read from the diagnostic only, never from the appended
 * document, and both must look like what kotlinx writes or they are left out: a value can quote
 * the markers this looks for, and a quoted value comes BEFORE the genuine ones.
 *
 * This never throws. It runs inside the caller's `catch (e: SerializationException)`, where a throw
 * would skip the caller's recovery (moving the file aside, writing defaults back).
 *
 * Where the caller also moves the file aside, the preserved copy holds the full document for anyone
 * diagnosing it. Two neighbours are deliberately separate: the Supabase decoders use
 * `sanitizeSupabaseFailure`, which returns a throwable for `Result.failure`, and
 * `SelfHealingSettingsManager.reportUnreadableKeys` logs the exception type alone because that file
 * holds API keys. Do not unify that one down to this.
 */
internal fun decodeFailure(error: SerializationException): Map<String, Any?> {
    // Everything after the marker is the file; nothing below may search it.
    val diagnostic = error.message.orEmpty().substringBefore(JSON_INPUT_MARKER)
    return buildMap {
        put("decodeFailure", error::class.simpleName ?: "SerializationException")
        offsetOf(diagnostic)?.let { put("offset", it) }
        pathOf(diagnostic)?.let { put("path", it) }
    }
}

/** kotlinx's separator between its diagnostic and the document it appends. */
private const val JSON_INPUT_MARKER = "\nJSON input:"

/**
 * `at offset 73`, only where kotlinx puts it: at the start of the diagnostic, before anything it
 * quotes (`Unexpected JSON token at offset 73: ...`). A quoted value cannot supply it, and an
 * out-of-range number is dropped rather than thrown.
 */
private fun offsetOf(diagnostic: String): Int? =
    LEADING_OFFSET
        // kotlinx puts it within its first few words; bounded so the scan never walks a long
        // message, should a kotlinx version stop writing the JSON input marker it is cut at.
        .find(diagnostic.take(OFFSET_SCAN_CHARS))
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

private val LEADING_OFFSET = Regex("""^[^']*?\bat offset (\d+)""")

private const val OFFSET_SCAN_CHARS = 200

/**
 * The JSON path, from the LAST `at path: ` in the first line of the diagnostic: kotlinx appends
 * the genuine path at the end of its message, after any value it quotes. The map keys in it are
 * masked, and the result must then be pure structure (`$`, `.field`, `[3]`, `[*]`), or it is left
 * out: a marker that came from inside a key or a value is followed by that text, not by a path.
 * A field whose serial name is not an ASCII identifier (`@SerialName("plugin-id")`) drops the
 * path the same way. That is the intended direction; do not widen [STRUCTURAL_PATH] to fit one.
 *
 * A quoted value with a newline in it moves the genuine path off the first line, which then ends
 * inside that value's quotes. kotlinx quotes in pairs, so an odd number of `'` before the marker
 * usually means that case, and the path is left out. This is a heuristic, not a proof: kotlinx does
 * not escape an apostrophe inside a value it quotes, so a value can restore the parity (and an
 * ordinary `it's` can break it, dropping a genuine path). What bounds the outcome is
 * [STRUCTURAL_PATH]: whatever gets through is `$`, `.identifier`, `[N]` and `[*]` only, so no URL,
 * domain, file path or token can.
 */
private fun pathOf(diagnostic: String): String? {
    val line = diagnostic.substringBefore('\n')
    val marker = line.lastIndexOf(PATH_MARKER)
    // Counted before the marker only: a map key's own quotes belong to the path after it.
    if (marker < 0 || line.substring(0, marker).count { it == '\'' } % 2 != 0) return null
    val masked = maskMapKeys(line.substring(marker + PATH_MARKER.length))
    return masked.takeIf { STRUCTURAL_PATH.matches(it) }
}

private const val PATH_MARKER = " at path: "

/**
 * Masks from the first map key's `['` to the last `']` as one `[*]`, or to the end when no `']`
 * follows. Index arithmetic rather than a regex, so no character in a key (a quote and a bracket,
 * a Unicode line separator) can end the mask early. Two keys and the fields between them collapse
 * into one `[*]`, which is the safe direction.
 */
private fun maskMapKeys(path: String): String {
    val start = path.indexOf("['")
    if (start < 0) return path
    val end = path.lastIndexOf("']")
    val rest = if (end > start) path.substring(end + 2) else ""
    return path.substring(0, start) + "[*]" + rest
}

/** A JSON path with nothing in it but structure: object fields, list indices and masked keys. */
private val STRUCTURAL_PATH = Regex("""\$(?:\.[A-Za-z_][A-Za-z0-9_]*|\[\d+]|\[\*])*""")
