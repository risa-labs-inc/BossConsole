package ai.rever.boss.mastery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
}
