package ai.rever.boss.mastery

/**
 * Minimal, deliberately bounded evaluator for [MasteryEdge.condition] expressions.
 *
 * A condition guards a single edge: the edge may be followed only when the
 * expression holds against the source node's output map (the mastery input for
 * the virtual INPUT node). Conditions are author-supplied strings, so this is
 * a hand-written parser with no scripting engine: no property access, no
 * arithmetic, and no compound operators.
 *
 * ## Grammar
 *
 * - `true` / `false` — boolean literals.
 * - `key` — truthiness of the output value at `key`: present, not blank, and
 *   not `false`, `0`, `no` or `off` (case-insensitive).
 * - `key == literal` / `key != literal` — plain string comparison of the
 *   output value at `key` against `literal`. A literal is a bare token or a
 *   double-quoted string; quotes are never part of the compared value, and
 *   the operator must be whitespace-separated from its operands.
 *
 * A condition key is a **bare** key of the source node's output map — the
 * `SOURCE_NODE.outputKey` form that [MasteryNode.inputMapping] uses on the
 * very same edge is not valid here. A dotted key would tokenize as a single
 * key token but never match an output key at runtime, so it is treated as
 * malformed: rejected when the definition is created, and failing closed
 * if one ever reaches the evaluator anyway.
 *
 * A bare-key condition reading an output key literally named `true` or
 * `false` is interpreted as the boolean literal; the `key == "true"`
 * comparison form is the unambiguous way to compare against those strings.
 *
 * ## Fail-closed semantics
 *
 * A null or blank condition is unconditional (always followed — the
 * pre-existing behaviour). Any malformed or unsupported expression — unknown
 * operators (`&&`, `=`, `>` …), compound forms, unterminated quotes, key
 * tokens containing operator or quote characters, dotted key tokens in the
 * `SOURCE_NODE.outputKey` form, or expressions longer than
 * [MAX_EXPRESSION_LENGTH] — fails CLOSED: it is reported as [Blocked] with a
 * human-readable reason, and [MasteryExecutor] skips the dependent node
 * instead of following the edge. Silently executing a node the author
 * explicitly guarded is the bug this closes (#1060), and guessing intent from
 * an unparsable expression is not acceptable. For the same reason, a
 * comparison against a key with no output value is never satisfied.
 */
object MasteryEdgeCondition {
    /** Evaluation outcome for one edge condition. */
    sealed interface Result

    /** The edge may be followed. */
    object Followed : Result

    /** The edge must not be followed; [reason] is surfaced in executor logs. */
    data class Blocked(
        val reason: String,
    ) : Result

    /** Conditions longer than this are rejected before parsing (fail closed). */
    private const val MAX_EXPRESSION_LENGTH = 256

    /** Values a bare-key condition treats as false, regardless of case. */
    private val FALSY = setOf("false", "0", "no", "off")

    /** Characters that never occur in an output key; a key token containing any is malformed. */
    private val OPERATOR_CHARS = charArrayOf('=', '!', '<', '>', '&', '|', '(', ')', '"', '\'')

    /** The two supported expression forms after parsing. */
    private sealed interface Parsed {
        data class Truthy(
            val key: String,
        ) : Parsed

        data class Comparison(
            val key: String,
            val negated: Boolean,
            val expected: String,
        ) : Parsed
    }

    /**
     * Why [condition] is not a well-formed expression, or null when it is
     * valid — null, blank, or parsing to the bounded grammar above.
     *
     * This is the creation-time counterpart of the runtime fail-closed
     * skip: definition validation rejects a malformed guard up front with
     * the same reason string the executor would report if one ever slipped
     * through, while the runtime skip stays in place as a backstop. Only
     * syntax is checked — whether the referenced output keys carry values
     * is a per-execution question and stays with [evaluate].
     */
    fun syntaxError(condition: String?): String? {
        val expression = condition?.trim().orEmpty()
        return when {
            expression.isEmpty() -> null
            expression.length > MAX_EXPRESSION_LENGTH -> oversized.reason
            parse(expression) == null -> malformed(expression).reason
            else -> null
        }
    }

    /**
     * Evaluates [condition] against [sourceOutput], the output map of the
     * edge's source node. Never throws: every unparsable or unsupported input
     * fails closed as [Blocked] instead.
     */
    fun evaluate(
        condition: String?,
        sourceOutput: Map<String, String>,
    ): Result {
        val expression = condition?.trim().orEmpty()
        return when {
            expression.isEmpty() -> Followed
            expression.length > MAX_EXPRESSION_LENGTH -> oversized
            else -> evaluateParsed(expression, parse(expression), sourceOutput)
        }
    }

    private fun evaluateParsed(
        expression: String,
        parsed: Parsed?,
        sourceOutput: Map<String, String>,
    ): Result =
        when (parsed) {
            null -> malformed(expression)
            is Parsed.Truthy -> evaluateTruthiness(parsed.key, sourceOutput)
            is Parsed.Comparison -> evaluateComparison(expression, parsed, sourceOutput)
        }

    private fun evaluateTruthiness(
        key: String,
        sourceOutput: Map<String, String>,
    ): Result {
        val value = sourceOutput[key]
        return when {
            key == "true" -> Followed
            key == "false" -> Blocked("condition '$key' evaluated false (boolean literal)")
            value == null -> Blocked("condition '$key' evaluated false (key has no output value)")
            value.isNotBlank() && value.trim().lowercase() !in FALSY -> Followed
            else -> Blocked("condition '$key' evaluated false ('$value' is falsy)")
        }
    }

    private fun evaluateComparison(
        expression: String,
        parsed: Parsed.Comparison,
        sourceOutput: Map<String, String>,
    ): Result {
        val actual = sourceOutput[parsed.key]
        return when {
            actual == null -> Blocked("key '${parsed.key}' has no output value")
            (actual == parsed.expected) != parsed.negated -> Followed
            else -> Blocked("condition '$expression' evaluated false")
        }
    }

    /**
     * Parses [expression] into one of the supported forms, or null when
     * malformed. The three-token comparison form is parsed inline rather
     * than through a helper so this object stays at ten functions.
     */
    private fun parse(expression: String): Parsed? {
        val tokens = tokenize(expression) ?: return null
        return when (tokens.size) {
            1 -> {
                if (isValidKey(tokens[0])) Parsed.Truthy(tokens[0]) else null
            }

            3 -> {
                val negated =
                    when (tokens[1]) {
                        "==" -> false
                        "!=" -> true
                        else -> null
                    }
                val expected = literalOf(tokens[2])
                if (negated == null || expected == null || !isValidKey(tokens[0])) {
                    null
                } else {
                    Parsed.Comparison(tokens[0], negated, expected)
                }
            }

            else -> {
                null
            }
        }
    }

    /**
     * Splits an expression into whitespace-separated tokens, keeping a
     * double-quoted run (spaces included) as a single token. Returns null
     * for unterminated quotes; the caller fails closed.
     */
    private fun tokenize(expression: String): List<String>? {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < expression.length) {
            val current = expression[index]
            when {
                current.isWhitespace() -> {
                    index++
                }

                current == '"' -> {
                    val closing = expression.indexOf('"', index + 1)
                    if (closing == -1) return null
                    tokens += expression.substring(index, closing + 1)
                    index = closing + 1
                }

                else -> {
                    val start = index
                    do {
                        index++
                    } while (index < expression.length && !expression[index].isWhitespace())
                    tokens += expression.substring(start, index)
                }
            }
        }
        return tokens
    }

    /**
     * The comparison literal carried by [token]: a double-quoted token yields
     * its content (quotes excluded), a bare token yields itself. Null marks
     * stray or unbalanced quotes — malformed, so the caller fails closed.
     */
    private fun literalOf(token: String): String? {
        val quoted =
            token.length >= 2 && token.first() == '"' &&
                token.indexOf('"', 1) == token.length - 1
        return when {
            quoted -> token.substring(1, token.length - 1)
            token.indexOf('"') == -1 -> token
            else -> null
        }
    }

    /**
     * Output keys are bare identifiers: operator or quote characters mark a
     * token malformed, and so does a `.`, because a condition reads a bare
     * key of the source node's output map. The `SOURCE_NODE.outputKey` form
     * used by [MasteryNode.inputMapping] looks like a key but never matches
     * one at runtime, so a dotted token is rejected up front instead of
     * failing closed silently at execution time.
     */
    private fun isValidKey(token: String): Boolean = token.none { it in OPERATOR_CHARS || it == '.' }

    private fun malformed(expression: String): Blocked =
        Blocked(
            "Malformed condition '$expression' (failing closed; supported forms: " +
                "'true', 'false', 'key', 'key == literal', 'key != literal'; a condition key " +
                "is a bare key of the source node's output map, not the SOURCE_NODE.outputKey " +
                "form used by inputMapping)",
        )

    private val oversized =
        Blocked(
            "Malformed condition: longer than $MAX_EXPRESSION_LENGTH characters (failing closed)",
        )
}
