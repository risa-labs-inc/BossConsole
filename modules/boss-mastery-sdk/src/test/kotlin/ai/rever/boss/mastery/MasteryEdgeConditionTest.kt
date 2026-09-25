package ai.rever.boss.mastery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MasteryEdgeConditionTest {
    @Test
    fun `null and blank conditions are unconditional`() {
        val source = mapOf("flag" to "false")
        assertEquals(MasteryEdgeCondition.Followed, MasteryEdgeCondition.evaluate(null, source))
        assertEquals(MasteryEdgeCondition.Followed, MasteryEdgeCondition.evaluate("", source))
        assertEquals(MasteryEdgeCondition.Followed, MasteryEdgeCondition.evaluate("   ", source))
    }

    @Test
    fun `boolean literals evaluate to themselves`() {
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("true", emptyMap()),
        )
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate(" true ", emptyMap()),
        )
        assertIs<MasteryEdgeCondition.Blocked>(MasteryEdgeCondition.evaluate("false", emptyMap()))
    }

    @Test
    fun `bare key condition follows canonical truthiness`() {
        val truthy =
            mapOf(
                "a" to "yes",
                "b" to "1",
                "c" to "clean",
                "d" to "TRUE",
            )
        truthy.keys.forEach { key ->
            assertEquals(MasteryEdgeCondition.Followed, MasteryEdgeCondition.evaluate(key, truthy))
        }
        val falsy =
            mapOf(
                "a" to "false",
                "b" to "0",
                "c" to "no",
                "d" to "off",
                "e" to "",
                "f" to "  ",
            )
        falsy.keys.forEach { key ->
            assertIs<MasteryEdgeCondition.Blocked>(MasteryEdgeCondition.evaluate(key, falsy))
        }
        assertIs<MasteryEdgeCondition.Blocked>(MasteryEdgeCondition.evaluate("absent", falsy))
    }

    @Test
    fun `equality compares the literal as a plain string`() {
        val source =
            mapOf(
                "status" to "ok",
                "count" to "200",
                "clean" to "true",
                "padded" to "  ok  ",
            )
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("status == ok", source),
        )
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("status == \"ok\"", source),
        )
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("count == 200", source),
        )
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("clean == true", source),
        )
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("empty == \"\"", mapOf("empty" to "")),
        )
        assertIs<MasteryEdgeCondition.Blocked>(
            MasteryEdgeCondition.evaluate("status == bad", source),
        )
        // Plain string comparison: neither side is trimmed.
        assertIs<MasteryEdgeCondition.Blocked>(
            MasteryEdgeCondition.evaluate("padded == ok", source),
        )
    }

    @Test
    fun `inequality follows only when the key holds a different value`() {
        val source = mapOf("status" to "ok")
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("status != failed", source),
        )
        assertIs<MasteryEdgeCondition.Blocked>(
            MasteryEdgeCondition.evaluate("status != ok", source),
        )
        val blocked = MasteryEdgeCondition.evaluate("absent != ok", source)
        assertIs<MasteryEdgeCondition.Blocked>(blocked)
        assertTrue(blocked.reason.contains("no output value"), blocked.reason)
    }

    @Test
    fun `quoted literals may contain spaces and are compared verbatim`() {
        val source = mapOf("message" to "hello world")
        assertEquals(
            MasteryEdgeCondition.Followed,
            MasteryEdgeCondition.evaluate("message == \"hello world\"", source),
        )
        assertIs<MasteryEdgeCondition.Blocked>(
            MasteryEdgeCondition.evaluate("message == \"hello\"", source),
        )
    }

    @Test
    fun `malformed expressions fail closed with a clear reason`() {
        val source = mapOf("key" to "value", "a" to "1", "b" to "2", "x" to "value")
        val malformed =
            listOf(
                "key ==",
                "== literal",
                "a == b == c",
                "a && b",
                "a || b",
                "a = b",
                "key > 1",
                "key >= 1",
                "key =! true",
                "a==b",
                "!key",
                "!key == value",
                "\"quoted\" == x",
                "key == \"unterminated",
                "key == x\"y",
                "scan.scan_clean == true",
                "scan.scan_clean",
            )
        malformed.forEach { expression ->
            val verdict = MasteryEdgeCondition.evaluate(expression, source)
            assertIs<MasteryEdgeCondition.Blocked>(verdict, expression)
            assertTrue(verdict.reason.contains("Malformed condition"), verdict.reason)
        }
    }

    @Test
    fun `oversized expressions fail closed before parsing`() {
        val verdict = MasteryEdgeCondition.evaluate("k".repeat(300), emptyMap())
        assertIs<MasteryEdgeCondition.Blocked>(verdict)
        assertTrue(verdict.reason.contains("256"), verdict.reason)
    }

    @Test
    fun `missing keys never satisfy a comparison`() {
        val verdict = MasteryEdgeCondition.evaluate("absent == ok", mapOf("present" to "ok"))
        assertIs<MasteryEdgeCondition.Blocked>(verdict)
        assertTrue(verdict.reason.contains("no output value"), verdict.reason)
    }

    @Test
    fun `dotted input-mapping-style keys are malformed with the bare-key rule`() {
        // `scan.scan_clean == true` is the form [MasteryNode.inputMapping]
        // uses on the same edge, but a condition reads a bare output key:
        // the dotted token parses as one key token, never matches an output
        // key at runtime, and must be rejected up front with a reason that
        // says so — not fail closed silently at execution time.
        listOf("scan.scan_clean == true", "scan.scan_clean", "INPUT.flag == true").forEach { expression ->
            val verdict = MasteryEdgeCondition.evaluate(expression, mapOf("scan_clean" to "true"))
            assertIs<MasteryEdgeCondition.Blocked>(verdict, expression)
            assertTrue(verdict.reason.contains("Malformed condition"), verdict.reason)
            assertTrue(verdict.reason.contains("'$expression'"), verdict.reason)
            assertTrue(verdict.reason.contains("bare key"), verdict.reason)
            assertTrue(verdict.reason.contains("SOURCE_NODE.outputKey"), verdict.reason)
        }
    }

    @Test
    fun `syntaxError accepts unconditional and well-formed conditions`() {
        assertNull(MasteryEdgeCondition.syntaxError(null))
        assertNull(MasteryEdgeCondition.syntaxError(""))
        assertNull(MasteryEdgeCondition.syntaxError("   "))
        assertNull(MasteryEdgeCondition.syntaxError("true"))
        assertNull(MasteryEdgeCondition.syntaxError("key"))
        assertNull(MasteryEdgeCondition.syntaxError("key == literal"))
        assertNull(MasteryEdgeCondition.syntaxError("key != \"quoted value\""))
    }

    @Test
    fun `syntaxError reports the same reason the runtime fail-closed skip would`() {
        val expression = "scan_clean == true && confirmed == true"
        assertEquals(
            "Malformed condition '$expression' (failing closed; supported forms: " +
                "'true', 'false', 'key', 'key == literal', 'key != literal'; a condition key " +
                "is a bare key of the source node's output map, not the SOURCE_NODE.outputKey " +
                "form used by inputMapping)",
            MasteryEdgeCondition.syntaxError(expression),
        )
        assertEquals(
            "Malformed condition: longer than 256 characters (failing closed)",
            MasteryEdgeCondition.syntaxError("k".repeat(300)),
        )
        // The dotted input-mapping form is rejected at creation time with
        // the same reason the runtime skip would report, naming the rule.
        val dotted = MasteryEdgeCondition.syntaxError("scan.scan_clean == true")!!
        assertTrue(dotted.startsWith("Malformed condition 'scan.scan_clean == true'"), dotted)
        assertTrue(dotted.contains("bare key"), dotted)
        listOf("key >", "a || b", "!key", "key == \"unterminated").forEach { expression ->
            assertTrue(MasteryEdgeCondition.syntaxError(expression)!!.startsWith("Malformed condition"))
        }
    }
}
