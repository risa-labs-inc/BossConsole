package ai.rever.boss.mcp.secrets

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Seeded, hand-rolled property tests over the parser, the substitution and the scrubber.
 *
 * No property-testing library is on the classpath and none is added: the generators below are
 * a few dozen lines, the seeds are fixed so a failure reproduces, and every case they produce is
 * printed by the assertion that catches it. Corpus, per `docs/MCP_SECRET_REFERENCES.md`:
 * nested objects and arrays; unicode including surrogate pairs and combining marks; JSON-escaped
 * input; quotes and newlines; duplicate, adjacent and split references; values containing the
 * marker or braces; values that are prefixes of each other; empty and short values; long values;
 * binary-looking data; references inside keys.
 */
class SecretReferenceFuzzTest {
    private val ids =
        listOf(
            "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5",
            "00000000-0000-4000-8000-000000000001",
            "ffffffff-ffff-4fff-8fff-ffffffffffff",
        )

    // Control characters as code points: the formatter rewrites a \u escape in a string literal as
    // the raw byte, and a raw NUL in a source file is exactly what this repo's scanners reject.
    private val nul = 0x00.toChar().toString()
    private val unitSeparator = 0x1F.toChar().toString()

    private val alphabet =
        listOf(
            "a",
            "Z",
            "0",
            " ",
            "\"",
            "\\",
            "\n",
            "\t",
            "{",
            "}",
            "{{",
            "}}",
            "{{secret:",
            "/",
            "&",
            "=",
            "%",
            "+",
            "é",
            "é",
            "🔐",
            nul,
            unitSeparator,
            "‏",
            "秘密",
        )

    /**
     * Noise that cannot close a candidate: no `}` in it. A stray `{{secret:` in noise followed by
     * planted text is then never a candidate of its own, so the generator knows exactly what it
     * planted. Braces still appear in VALUES (see [value]), where they are data, not grammar.
     */
    private val noiseAlphabet = alphabet - setOf("}", "}}", "{{secret:")

    private fun Random.value(maxLen: Int = 24): String {
        val length = nextInt(0, maxLen)
        return buildString { repeat(length) { append(alphabet.random(this@value)) } }
    }

    private fun encode(root: JsonObject): String = McpArgumentSubstitution.encode(root)

    private fun Random.noise(maxLen: Int = 24): String =
        buildString { repeat(nextInt(0, maxLen)) { append(noiseAlphabet.random(this@noise)) } }

    /** Unique keys: `toMap()` keeps the last of two equal keys and would drop a planted subtree. */
    private fun Random.keys(n: Int): List<String> = List(n) { "k$it" + noise(4) }

    private fun Random.refLiteral(): String {
        val id = ids.random(this)
        val spelled = if (nextBoolean()) id else id.uppercase()
        return when (nextInt(4)) {
            0 -> "{{secret:$spelled}}"
            1 -> "{{secret:$spelled.password}}"
            2 -> "{{secret:$spelled.username}}"
            else -> "{{secret:$spelled.notes}}"
        }
    }

    /** A string that may contain references between fragments of noise. */
    private fun Random.stringWithRefs(refsOut: MutableSet<SecretReference>): String =
        buildString {
            repeat(nextInt(0, 4)) {
                append(noise())
                if (nextBoolean()) {
                    val literal = refLiteral()
                    append(literal)
                    val body = literal.removePrefix("{{secret:").removeSuffix("}}")
                    val id = body.substringBefore('.').lowercase()
                    val field =
                        if ('.' in body) SecretField.fromWireName(body.substringAfter('.'))!! else SecretField.PASSWORD
                    refsOut.add(SecretReference(id, field))
                }
            }
            append(noise())
        }

    private fun Random.tree(
        depth: Int,
        refsOut: MutableSet<SecretReference>,
    ): JsonElement =
        when {
            depth <= 0 -> leaf(refsOut)
            nextInt(3) == 0 -> JsonArray(List(nextInt(0, 4)) { tree(depth - 1, refsOut) })
            nextInt(2) == 0 -> JsonObject(keys(nextInt(0, 4)).associateWith { tree(depth - 1, refsOut) })
            else -> leaf(refsOut)
        }

    private fun Random.root(refsOut: MutableSet<SecretReference>): JsonObject =
        JsonObject(keys(nextInt(1, 5)).associateWith { tree(3, refsOut) })

    private fun Random.leaf(refsOut: MutableSet<SecretReference>): JsonElement =
        when (nextInt(6)) {
            0 -> JsonPrimitive(nextInt())
            1 -> JsonPrimitive(nextBoolean())
            2 -> JsonNull
            else -> JsonPrimitive(stringWithRefs(refsOut))
        }

    @Test
    fun `scan finds exactly the references the generator planted, in any nesting`() {
        val random = Random(20260917)
        repeat(400) { iteration ->
            val planted = LinkedHashSet<SecretReference>()
            val root = random.root(planted)
            val scan = McpArgumentSubstitution.scan(root)
            if (planted.isEmpty()) {
                // Noise may contain the marker but can never close a candidate.
                assertIs<SecretReferenceScan.None>(scan, "iteration $iteration: ${encode(root)}")
            } else {
                val found = assertIs<SecretReferenceScan.Found>(scan, "iteration $iteration: ${encode(root)}")
                assertEquals(planted, found.references, "iteration $iteration")
            }
        }
    }

    @Test
    fun `after substitution no reference literal survives and every planted value is present`() {
        val random = Random(42)
        repeat(400) { iteration ->
            val planted = LinkedHashSet<SecretReference>()
            val root = random.root(planted)
            if (planted.isEmpty()) return@repeat
            // Values built from noise cannot close a candidate, so after substitution the only
            // way a scan can find anything is a planted literal that survived.
            val values = planted.associateWith { "V<${it.ledgerName}>" + random.noise(12) }
            val out = McpArgumentSubstitution.substitute(root, values)
            val strings = McpArgumentSubstitution.stringValues(out)
            assertIs<SecretReferenceScan.None>(
                SecretReferenceParser.findIn(strings),
                "iteration $iteration: a literal survived in $strings",
            )
            // ...and every planted value is present somewhere.
            for (ref in planted) {
                val value = values.getValue(ref)
                assertTrue(strings.any { it.contains(value) }, "iteration $iteration: value for $ref missing")
            }
            // Round trip: encode then parse yields the same tree.
            val reparsed = McpArgumentSubstitution.parseObject(McpArgumentSubstitution.encode(out))
            assertEquals(out, reparsed, "iteration $iteration")
        }
    }

    @Test
    fun `substitution without references is the identity`() {
        val random = Random(7)
        repeat(200) {
            val planted = LinkedHashSet<SecretReference>()
            val root = random.root(planted)
            if (planted.isNotEmpty()) return@repeat
            assertEquals(root, McpArgumentSubstitution.substitute(root, emptyMap()))
        }
    }

    @Test
    fun `scrub removes every protected form of every value from any echo`() {
        val random = Random(99)
        repeat(400) { iteration ->
            val refs = ids.map { SecretReference(it, SecretField.entries.random(random)) }.distinct()
            val values = refs.associateWith { random.value(40) }
            val scrubber = McpResultScrubber(values)
            val echo =
                buildString {
                    values.values.forEach { v ->
                        append(random.value(8))
                        when (random.nextInt(4)) {
                            0 -> append(v)
                            1 -> append(McpResultScrubber.jsonEscape(v))
                            2 -> append(URLEncoder.encode(v, Charsets.UTF_8))
                            else -> append(URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20"))
                        }
                        append(random.value(8))
                    }
                }
            val out = scrubber.scrub(echo)
            for ((ref, v) in values) {
                if (v.length < McpResultScrubber.DEFAULT_MIN_LENGTH) continue
                for (form in McpResultScrubber.encodings(v)) {
                    // A short encoded form can legitimately reappear inside noise (e.g. "a");
                    // only forms at or above the floor are asserted absent.
                    if (form.length < McpResultScrubber.DEFAULT_MIN_LENGTH) continue
                    assertFalse(out.contains(form), "iteration $iteration: $ref form '$form' survived in '$out'")
                }
            }
        }
    }

    @Test
    fun `the parser is linear on adversarial input`() {
        // A long run of openers with no closer, and a long run of closers: no backtracking
        // blow-up, because the candidate body class excludes braces.
        val openers = "{{secret:".repeat(20_000)
        val closers = "}}".repeat(20_000)
        val started = System.nanoTime()
        SecretReferenceParser.findIn(listOf(openers, closers, openers + closers))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "parser took ${elapsedMs}ms on 360KB of adversarial input")
    }

    @Test
    fun `binary-looking and very long values round-trip through substitution`() {
        val random = Random(3)
        val ref = SecretReference(ids[0], SecretField.PASSWORD)
        val binary = String(CharArray(65_536) { random.nextInt(0x20, 0xD7FF).toChar() })
        val root = McpArgumentSubstitution.parseObject("""{"blob":"{{secret:${ids[0]}}}"}""")!!
        val out = McpArgumentSubstitution.substitute(root, mapOf(ref to binary))
        val back = McpArgumentSubstitution.parseObject(McpArgumentSubstitution.encode(out))!!
        assertEquals(binary, (back["blob"] as JsonPrimitive).content)
    }
}
