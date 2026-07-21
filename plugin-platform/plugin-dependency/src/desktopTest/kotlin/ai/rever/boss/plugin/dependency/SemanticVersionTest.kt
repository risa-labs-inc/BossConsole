package ai.rever.boss.plugin.dependency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticVersionTest {

    private fun v(s: String) = SemanticVersion.parse(s) ?: error("expected '$s' to parse")

    // ---- parse: valid ----

    @Test
    fun `parses full version`() {
        val sv = v("1.2.3")
        assertEquals(SemanticVersion(1, 2, 3), sv)
    }

    @Test
    fun `missing minor and patch default to zero`() {
        assertEquals(SemanticVersion(1, 0, 0), v("1"))
        assertEquals(SemanticVersion(1, 2, 0), v("1.2"))
    }

    @Test
    fun `parses prerelease and discards build metadata`() {
        assertEquals(SemanticVersion(1, 0, 0, "rc.1"), v("1.0.0-rc.1+build.7"))
        // Build metadata is not retained: differ only by build -> equal.
        assertEquals(v("1.0.0+a"), v("1.0.0+b"))
    }

    // ---- parse: invalid ----

    @Test
    fun `rejects malformed input`() {
        for (bad in listOf("", "   ", "x", "1.x", "1..2", "1.2.3.4", "1.0.0-", "1.0.0+", "1.0.0-a..b", "-1.0.0")) {
            assertNull(SemanticVersion.parse(bad), "expected '$bad' to be rejected")
        }
    }

    // ---- precedence ----

    @Test
    fun `core precedence`() {
        assertTrue(v("2.0.0") > v("1.9.9"))
        assertTrue(v("1.2.0") > v("1.1.9"))
        assertTrue(v("1.1.2") > v("1.1.1"))
        assertEquals(0, v("1.1.1").compareTo(v("1.1.1")))
    }

    @Test
    fun `release outranks its prerelease`() {
        assertTrue(v("1.0.0") > v("1.0.0-rc.1"))
    }

    @Test
    fun `numeric prerelease identifiers order numerically not lexically`() {
        // The bug the old lexical compare had: "rc.10" must outrank "rc.9".
        assertTrue(v("1.0.0-rc.10") > v("1.0.0-rc.9"))
    }

    @Test
    fun `fewer prerelease identifiers rank lower`() {
        assertTrue(v("1.0.0-alpha.1") > v("1.0.0-alpha"))
    }

    @Test
    fun `numeric identifier ranks below alphanumeric`() {
        assertTrue(v("1.0.0-alpha") > v("1.0.0-1"))
    }

    @Test
    fun `full semver precedence chain`() {
        // From the spec's example ordering.
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta",
            "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0"
        ).map { v(it) }
        for (i in 0 until ordered.size - 1) {
            assertTrue(ordered[i] < ordered[i + 1], "${ordered[i]} should sort below ${ordered[i + 1]}")
        }
    }

    // ---- equals/compareTo consistency ----

    @Test
    fun `equal versions compare zero and are equal`() {
        val a = v("1.2.3-rc.1")
        val b = v("1.2.3-rc.1")
        assertEquals(a, b)
        assertEquals(0, a.compareTo(b))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString round-trips core and prerelease`() {
        assertEquals("1.2.3", v("1.2.3").toString())
        assertEquals("1.2.3-rc.1", v("1.2.3-rc.1+meta").toString())
    }
}
