package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Version class semantic versioning comparison logic.
 */
class VersionTest {

    @Test
    fun `version parsing works for standard versions`() {
        val version = Version.parse("8.16.28")
        assertEquals(8, version?.major)
        assertEquals(16, version?.minor)
        assertEquals(28, version?.patch)
        assertNull(version?.preRelease)
    }

    @Test
    fun `version parsing works with v prefix`() {
        val version = Version.parse("v8.16.28")
        assertEquals(8, version?.major)
        assertEquals(16, version?.minor)
        assertEquals(28, version?.patch)
    }

    @Test
    fun `version parsing works with prerelease suffix`() {
        val version = Version.parse("8.16.28-alpha.1")
        assertEquals(8, version?.major)
        assertEquals(16, version?.minor)
        assertEquals(28, version?.patch)
        assertEquals("alpha.1", version?.preRelease)
    }

    @Test
    fun `version parsing returns null for invalid versions`() {
        assertNull(Version.parse("invalid"))
        assertNull(Version.parse("8.16"))
        assertNull(Version.parse(""))
    }

    @Test
    fun `version validation allows equal versions`() {
        val current = Version.parse("8.16.28")!!
        val required = Version.parse("8.16.28")!!
        assertTrue(current >= required)
    }

    @Test
    fun `version validation allows newer major version`() {
        val current = Version.parse("9.0.0")!!
        val required = Version.parse("8.16.28")!!
        assertTrue(current >= required)
    }

    @Test
    fun `version validation allows newer minor version`() {
        val current = Version.parse("8.17.0")!!
        val required = Version.parse("8.16.28")!!
        assertTrue(current >= required)
    }

    @Test
    fun `version validation allows newer patch version`() {
        val current = Version.parse("8.16.29")!!
        val required = Version.parse("8.16.28")!!
        assertTrue(current >= required)
    }

    @Test
    fun `version validation rejects older major version`() {
        val current = Version.parse("7.20.0")!!
        val required = Version.parse("8.16.28")!!
        assertFalse(current >= required)
    }

    @Test
    fun `version validation rejects older minor version`() {
        val current = Version.parse("8.15.30")!!
        val required = Version.parse("8.16.28")!!
        assertFalse(current >= required)
    }

    @Test
    fun `version validation rejects older patch version`() {
        val current = Version.parse("8.16.27")!!
        val required = Version.parse("8.16.28")!!
        assertFalse(current >= required)
    }

    @Test
    fun `prerelease version is less than stable`() {
        val current = Version.parse("8.16.28-alpha.1")!!
        val required = Version.parse("8.16.28")!!
        assertFalse(current >= required)
    }

    @Test
    fun `stable version satisfies prerelease requirement`() {
        val current = Version.parse("8.16.28")!!
        val required = Version.parse("8.16.28-alpha.1")!!
        assertTrue(current >= required)
    }

    @Test
    fun `prerelease ordering alpha less than beta`() {
        val current = Version.parse("8.16.28-alpha.1")!!
        val required = Version.parse("8.16.28-beta.1")!!
        assertFalse(current >= required)
    }

    @Test
    fun `prerelease ordering beta less than rc`() {
        val current = Version.parse("8.16.28-beta.1")!!
        val required = Version.parse("8.16.28-rc.1")!!
        assertFalse(current >= required)
    }

    @Test
    fun `prerelease ordering alpha satisfies earlier alpha`() {
        val current = Version.parse("8.16.28-alpha.2")!!
        val required = Version.parse("8.16.28-alpha.1")!!
        assertTrue(current >= required)
    }

    @Test
    fun `prerelease ordering same alpha version is equal`() {
        val current = Version.parse("8.16.28-alpha.1")!!
        val required = Version.parse("8.16.28-alpha.1")!!
        assertTrue(current >= required)
        assertEquals(0, current.compareTo(required))
    }

    @Test
    fun `beta satisfies alpha requirement`() {
        val current = Version.parse("8.16.28-beta.1")!!
        val required = Version.parse("8.16.28-alpha.5")!!
        assertTrue(current >= required)
    }

    @Test
    fun `rc satisfies beta requirement`() {
        val current = Version.parse("8.16.28-rc.1")!!
        val required = Version.parse("8.16.28-beta.5")!!
        assertTrue(current >= required)
    }

    @Test
    fun `version toString formats correctly`() {
        assertEquals("8.16.28", Version(8, 16, 28).toString())
        assertEquals("8.16.28-alpha.1", Version(8, 16, 28, "alpha.1").toString())
    }

    @Test
    fun `isNewerThan works correctly`() {
        val v1 = Version.parse("8.16.29")!!
        val v2 = Version.parse("8.16.28")!!
        assertTrue(v1.isNewerThan(v2))
        assertFalse(v2.isNewerThan(v1))
        assertFalse(v2.isNewerThan(v2))
    }

    // Unknown prerelease type tests

    @Test
    fun `unknown prerelease type is greater than rc`() {
        val nightly = Version.parse("8.16.28-nightly.1")!!
        val rc = Version.parse("8.16.28-rc.1")!!
        assertTrue(nightly > rc)
    }

    @Test
    fun `unknown prerelease type is less than stable`() {
        val nightly = Version.parse("8.16.28-nightly.1")!!
        val stable = Version.parse("8.16.28")!!
        assertTrue(nightly < stable)
        assertFalse(nightly >= stable)
    }

    @Test
    fun `stable satisfies unknown prerelease requirement`() {
        val stable = Version.parse("8.16.28")!!
        val dev = Version.parse("8.16.28-dev.5")!!
        assertTrue(stable >= dev)
    }

    @Test
    fun `unknown prerelease types compared by ordinal then number`() {
        val nightly1 = Version.parse("8.16.28-nightly.1")!!
        val nightly2 = Version.parse("8.16.28-nightly.2")!!
        val snapshot1 = Version.parse("8.16.28-snapshot.1")!!

        // Same unknown type, different numbers
        assertTrue(nightly2 > nightly1)

        // Different unknown types have same ordinal (99), so compared equally by number
        assertEquals(0, nightly1.compareTo(snapshot1))
    }

    @Test
    fun `known prerelease satisfies unknown prerelease with lower patch`() {
        // Even though nightly > rc for same version, 8.16.29-rc.1 > 8.16.28-nightly.1
        val rc = Version.parse("8.16.29-rc.1")!!
        val nightly = Version.parse("8.16.28-nightly.1")!!
        assertTrue(rc > nightly)
    }
}
