package ai.rever.boss.mcp.secrets

import ai.rever.boss.mcp.MAX_MCP_RESULT_CHARS
import ai.rever.boss.mcp.parseMcpToolArgs
import ai.rever.boss.utils.logging.BossLogger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cost the pre-pass adds to calls, measured rather than asserted from reasoning.
 *
 * Two numbers matter and both are printed so a PR can quote them:
 * - what a call WITHOUT references pays: one substring scan of the raw arguments (INV5 says
 *   nothing else may happen), which has to be lost in the noise of parsing the arguments at all;
 * - what scrubbing a result at the host cap costs, since it runs on every secret-bearing call.
 *
 * The bounds are deliberately loose (an order of magnitude above what a laptop measures) so a
 * slow CI runner does not fail them; they exist to catch a regression to something quadratic,
 * not to pin a microsecond.
 */
class SecretReferenceOverheadTest {
    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val logger = BossLogger.forComponent("SecretReferenceOverheadTest")

    @Test
    fun `a call without references pays one substring scan`() {
        // ~2 KB of realistic arguments: a file write with a nested object and an array.
        val raw =
            buildString {
                append("""{"path":"/home/user/project/src/main/kotlin/App.kt","content":"""")
                repeat(40) { append("fun f$it() = println(\\\"hello $it\\\")\\n") }
                append("""","options":{"create":true,"mode":420},"tags":["a","b","c"]}""")
            }
        val iterations = 20_000
        // Warm up both paths.
        repeat(2_000) {
            SecretReferenceParser.mayContain(raw)
            parseMcpToolArgs(raw, logger)
        }
        val scanStart = System.nanoTime()
        var hits = 0
        repeat(iterations) { if (SecretReferenceParser.mayContain(raw)) hits++ }
        val scanNs = (System.nanoTime() - scanStart) / iterations
        val parseStart = System.nanoTime()
        repeat(iterations) { parseMcpToolArgs(raw, logger) }
        val parseNs = (System.nanoTime() - parseStart) / iterations
        println(
            "secret pre-pass, no references: scan=${scanNs}ns/call, " +
                "existing argument parse=${parseNs}ns/call (${raw.length} chars)",
        )
        assertTrue(hits == 0)
        assertTrue(scanNs < 50_000, "the marker scan took ${scanNs}ns per call; expected microseconds")
        assertTrue(scanNs < parseNs, "the scan (${scanNs}ns) should be cheaper than the parse (${parseNs}ns)")
    }

    @Test
    fun `scrubbing a result at the host cap is linear and fast`() {
        val values =
            mapOf(
                SecretReference(id, SecretField.PASSWORD) to "hunter2!\"quoted\" & spaced/\\slashed",
                SecretReference(id, SecretField.USERNAME) to "deploy-bot-account",
                SecretReference(id, SecretField.NOTES) to "rotate every ninety days, owner: platform",
            )
        val scrubber = McpResultScrubber(values)
        val chunk = "line of ordinary tool output with a token " + values.values.first() + " inside it\n"
        val text = buildString { while (length < MAX_MCP_RESULT_CHARS) append(chunk) }
        repeat(5) { scrubber.scrub(text) }
        val runs = 20
        val start = System.nanoTime()
        repeat(runs) { scrubber.scrub(text) }
        val perRunMs = (System.nanoTime() - start) / runs / 1_000_000.0
        println("scrub of ${text.length} chars with ${values.size} values: ${"%.2f".format(perRunMs)} ms/run")
        assertTrue(perRunMs < 500, "scrubbing a capped result took ${perRunMs}ms; expected tens of milliseconds")
        val half = text.substring(0, text.length / 2)
        val halfStart = System.nanoTime()
        repeat(runs) { scrubber.scrub(half) }
        val halfMs = (System.nanoTime() - halfStart) / runs / 1_000_000.0
        assertTrue(halfMs < perRunMs * 0.9 + 5, "half the input should take about half the time: $halfMs vs $perRunMs")
    }
}
