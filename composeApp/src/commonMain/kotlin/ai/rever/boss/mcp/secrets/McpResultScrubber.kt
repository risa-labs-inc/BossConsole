package ai.rever.boss.mcp.secrets

import ai.rever.boss.plugin.api.McpToolResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A transform applied to a tool's result before the host caps and returns it.
 *
 * The seam the scrubber plugs into. Deliberately tiny: the registry composes at most one of
 * these per call today, and it exists so the defense-in-depth step below can be switched off
 * without touching the path that carries the actual guarantee.
 */
fun interface McpResultFilter {
    fun apply(result: McpToolResult): McpToolResult

    companion object {
        /** No transform. What every call without secret references gets. */
        val NONE: McpResultFilter = McpResultFilter { it }
    }
}

/**
 * Removes resolved secret values from a tool's result text: defense in depth, not the guarantee.
 *
 * **What this is for.** A handler that echoes its input - a file read back after a write, a
 * stack trace that quotes an argument, a tool that returns the request it was given - would
 * otherwise carry the value straight back to the agent. Exact-match replacement stops that.
 *
 * **What this is not.** It cannot recognise a value the handler transformed: a hash, a base64
 * encoding, a ciphertext, a case change. The non-disclosure guarantee therefore does not rest
 * here; it rests on the argument path (the agent never authored the value), the approval (the
 * operator saw which secret the tool receives) and the ledger (references, never values). This
 * filter reduces accidental disclosure by an honest handler. It is switchable
 * (`resultScrubbingEnabled`) precisely so the invariant tests can prove the rest of the pipeline
 * holds without it.
 *
 * Covered encodings, longest match replaced first so an encoding that contains the raw form is
 * caught before the raw form:
 * - the value itself
 * - JSON string escaping (what a tool that re-serialises its arguments produces)
 * - URL percent-encoding, both the `+`-for-space and the `%20` spellings
 *
 * Values shorter than [minLength] are not scrubbed at all. A four-character PIN would match
 * inside ordinary words and turn unrelated output into tokens; the floor is documented, and the
 * value still never reached the agent through the argument path.
 */
class McpResultScrubber(
    values: Map<SecretReference, String>,
    private val minLength: Int = DEFAULT_MIN_LENGTH,
) : McpResultFilter {
    /** (needle, replacement) pairs, longest needle first. */
    private val needles: List<Pair<String, String>> =
        values
            .filter { (_, value) -> value.length >= minLength }
            .flatMap { (ref, value) -> encodings(value).map { it to ref.token } }
            .distinctBy { it.first }
            .sortedByDescending { it.first.length }

    override fun apply(result: McpToolResult): McpToolResult {
        if (needles.isEmpty()) return result
        val scrubbed = scrub(result.text)
        return if (scrubbed === result.text) result else result.copy(text = scrubbed)
    }

    /** Scrub free text. Returns the same instance when nothing matched. */
    fun scrub(text: String): String {
        var out = text
        for ((needle, token) in needles) {
            if (out.contains(needle)) out = out.replace(needle, token)
        }
        return out
    }

    companion object {
        const val DEFAULT_MIN_LENGTH: Int = 8

        private const val FORM_FEED: Int = 0x0C

        /**
         * The forms of [value] this filter recognises. The raw form is always first; the others
         * are included only when they differ from it, so a plain alphanumeric value costs one
         * needle, not four.
         */
        fun encodings(value: String): List<String> {
            val forms = LinkedHashSet<String>()
            forms.add(value)
            forms.add(jsonEscape(value))
            val plusForm = URLEncoder.encode(value, StandardCharsets.UTF_8)
            forms.add(plusForm)
            forms.add(plusForm.replace("+", "%20"))
            return forms.toList()
        }

        /**
         * The body a JSON encoder writes for [value], without the surrounding quotes. Hand-rolled
         * rather than borrowed from kotlinx so the escaping rules here are the ones tested here:
         * quote, backslash and control characters, with the short forms for the common ones.
         */
        fun jsonEscape(value: String): String {
            val sb = StringBuilder(value.length + 8)
            for (ch in value) {
                when (ch) {
                    '"' -> {
                        sb.append("\\\"")
                    }

                    '\\' -> {
                        sb.append("\\\\")
                    }

                    '\n' -> {
                        sb.append("\\n")
                    }

                    '\r' -> {
                        sb.append("\\r")
                    }

                    '\t' -> {
                        sb.append("\\t")
                    }

                    '\b' -> {
                        sb.append("\\b")
                    }

                    else -> {
                        when {
                            // Form feed, spelled by code point: a formatter once rewrote the
                            // escape as a raw control byte in this file.
                            ch.code == FORM_FEED -> {
                                sb.append("\\f")
                            }

                            ch < ' ' -> {
                                sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                            }

                            else -> {
                                sb.append(ch)
                            }
                        }
                    }
                }
            }
            return sb.toString()
        }
    }
}
