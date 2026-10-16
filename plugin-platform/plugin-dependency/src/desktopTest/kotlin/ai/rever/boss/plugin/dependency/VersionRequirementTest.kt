package ai.rever.boss.plugin.dependency

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionRequirementTest {
    @Test
    fun `minimum includes the floor and excludes older versions and its prerelease`() {
        val range = assertNotNull(VersionRequirement.parse(">=1.1.1"))
        for (version in listOf("1.1.1", "1.1.2", "2.0.0", "1.1.1+build.5")) assertTrue(range.matches(version), version)
        for (version in listOf("1.0.172", "1.1.0", "1.1.1-rc.1", "unknown")) {
            assertFalse(range.matches(version), version)
        }
    }

    @Test
    fun `every comparison operator respects its boundary`() {
        val cases =
            listOf(
                Triple(">1.1.1", "1.1.1", false),
                Triple(">1.1.1", "1.1.2", true),
                Triple("<=1.1.1", "1.1.1", true),
                Triple("<=1.1.1", "1.1.2", false),
                Triple("<1.1.1", "1.1.0", true),
                Triple("<1.1.1", "1.1.1", false),
                Triple("=1.1.1", "1.1.1", true),
                Triple("=1.1.1", "1.1.2", false),
            )
        for ((spec, version, expected) in cases) {
            val range = assertNotNull(VersionRequirement.parse(spec))
            if (expected) {
                assertTrue(
                    range.matches(version),
                    "$spec / $version",
                )
            } else {
                assertFalse(range.matches(version), "$spec / $version")
            }
        }
    }

    @Test
    fun `compound bounds are all enforced and whitespace around operators is accepted`() {
        val range = assertNotNull(VersionRequirement.parse("  >= 1.1.1   < 2.0.0  "))
        assertTrue(range.matches("1.1.1"))
        assertTrue(range.matches("1.9.9"))
        assertFalse(range.matches("1.1.0"))
        assertFalse(range.matches("2.0.0"))
    }

    @Test
    fun `exact wildcard and short versions retain their meanings`() {
        assertTrue(assertNotNull(VersionRequirement.parse("*")).matches("unknown"))
        assertTrue(assertNotNull(VersionRequirement.parse("")).matches("unknown"))
        val exact = assertNotNull(VersionRequirement.parse("v1.2"))
        assertTrue(exact.matches("1.2.0+build.1"))
        assertFalse(exact.matches("1.2.1"))
        assertTrue(assertNotNull(VersionRequirement.parse(">=1.0.0-rc.9")).matches("1.0.0-rc.10"))
    }

    @Test
    fun `malformed and unsupported ranges fail closed`() {
        for (bad in listOf(
            "latest",
            ">=",
            ">=oops",
            ">=1..1",
            "1.2.3.4",
            ">=1.0.0 garbage",
            "1.0.0-",
            "1.0.0+",
            "^1.0.0",
            ">=1.0.0 || <0.9.0",
        )) {
            assertNull(VersionRequirement.parse(bad), bad)
        }
    }
}
