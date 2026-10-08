package ai.rever.boss.plugin.pathutils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BossDirectoriesProfileTest {
    @Test
    fun `no profile named means the main profile`() {
        assertNull(BossDirectories.resolveProfileId(null, null))
        assertNull(BossDirectories.resolveProfileId("", "  "))
    }

    @Test
    fun `the system property wins over the environment`() {
        assertEquals("work", BossDirectories.resolveProfileId("work", "personal"))
        assertEquals("personal", BossDirectories.resolveProfileId(null, "personal"))
        assertEquals("personal", BossDirectories.resolveProfileId(" ", "personal"))
    }

    @Test
    fun `ids are normalised to lowercase`() {
        assertEquals("work-1", BossDirectories.resolveProfileId("Work-1", null))
    }

    @Test
    fun `main and default name the main profile`() {
        assertNull(BossDirectories.resolveProfileId("main", null))
        assertNull(BossDirectories.resolveProfileId(null, "DEFAULT"))
    }

    @Test
    fun `an id that could escape the profiles directory is ignored, never used as a path`() {
        listOf("../x", "a/b", "a\\b", ".", "..", "-leading", "has space", "x".repeat(33), "é").forEach { raw ->
            assertNull(BossDirectories.resolveProfileId(raw, null), "'$raw' must not resolve")
            assertFalse(BossDirectories.isValidProfileId(raw), "'$raw' must not be valid")
        }
    }

    @Test
    fun `a profile root is a direct child of the profiles directory`() {
        val root = BossDirectories.profileRoot("work-1")
        assertEquals(File(BossDirectories.profilesDir(), "work-1"), root)
        assertEquals(File(BossDirectories.baseDir, "profiles"), BossDirectories.profilesDir())
    }

    @Test
    fun `profileRoot refuses an invalid id`() {
        assertThrows<IllegalArgumentException> { BossDirectories.profileRoot("../escape") }
        assertThrows<IllegalArgumentException> { BossDirectories.profileRoot("main") }
    }

    @Test
    fun `without a profile the root is the base directory, as before`() {
        // The test JVM sets neither boss.profile nor BOSS_PROFILE.
        if (BossDirectories.profileId == null) {
            assertEquals(BossDirectories.baseDir, BossDirectories.rootDir)
            assertFalse(BossDirectories.isProfile)
        } else {
            assertTrue(BossDirectories.rootDir.startsWith(BossDirectories.profilesDir()))
        }
    }

    @Test
    fun `an id no profile was created for is ignored, never materialised`() {
        assertNull(BossDirectories.registeredProfileId("worrk") { false })
        assertEquals("work", BossDirectories.registeredProfileId("work") { it == "work" })
        assertNull(BossDirectories.registeredProfileId(null) { true })
    }

    @Test
    fun `the profiles feature is off by default`() {
        // The test JVM opts in to nothing and runs as no profile.
        val optedIn =
            System.getenv(BossDirectories.PROFILES_ENABLED_ENV) != null ||
                System.getProperty(BossDirectories.PROFILES_ENABLED_PROPERTY) != null
        if (BossDirectories.profileId == null && !optedIn) {
            assertFalse(BossDirectories.profilesEnabled)
        }
    }
}
