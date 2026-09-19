package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserProfileSecurityTest {
    private val tempDir =
        File(
            System.getProperty("user.home"),
            "boss-profile-sec-test-${System.currentTimeMillis()}",
        )
    private val originalSettingsFile = BrowserSettingsManager.settingsFile

    @BeforeTest
    fun setUp() {
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        BrowserSettingsManager.settingsFile = originalSettingsFile
        tempDir.deleteRecursively()
    }

    @Test
    fun testSanitizeProfileIdentifier() {
        val sanitizedSlash = BossDirectories.sanitizeProfileIdentifier("../escaped")
        assertFalse(sanitizedSlash.contains("/"))
        assertFalse(sanitizedSlash.contains(".."))

        val sanitizedBackslash = BossDirectories.sanitizeProfileIdentifier("C:\\Windows\\System32")
        assertFalse(sanitizedBackslash.contains("\\"))
        assertFalse(sanitizedBackslash.contains(":"))

        val sanitizedNormal = BossDirectories.sanitizeProfileIdentifier("Work / Personal")
        assertEquals("browser-profile-work-personal", sanitizedNormal)

        val sanitizedEmpty = BossDirectories.sanitizeProfileIdentifier("   ")
        assertEquals("browser-profile", sanitizedEmpty)
    }

    @Test
    fun testIsValidProfileIdentifier() {
        assertTrue(BossDirectories.isValidProfileIdentifier("browser-profile"))
        assertTrue(BossDirectories.isValidProfileIdentifier("browser-profile-work"))
        assertTrue(BossDirectories.isValidProfileIdentifier("browser-profile-123"))

        assertFalse(BossDirectories.isValidProfileIdentifier("../escaped"))
        assertFalse(BossDirectories.isValidProfileIdentifier("C:\\Windows"))
        assertFalse(BossDirectories.isValidProfileIdentifier("../../etc/passwd"))
        assertFalse(BossDirectories.isValidProfileIdentifier(""))
        assertFalse(BossDirectories.isValidProfileIdentifier("."))
        assertFalse(BossDirectories.isValidProfileIdentifier("foo/bar"))
        assertFalse(BossDirectories.isValidProfileIdentifier("foo\\bar"))
    }

    @Test
    fun testPersistedInvalidValuesFallback() {
        val maliciousSettingsFile = File(tempDir, "browser-settings.json")
        maliciousSettingsFile.writeText(
            """
            {
                "currentProfile": "../../etc/passwd",
                "availableProfiles": ["../../etc/passwd", "C:\\Windows\\System32"]
            }
            """.trimIndent(),
        )

        BrowserSettingsManager.settingsFile = maliciousSettingsFile
        BrowserSettingsManager.reloadForTest()

        assertEquals("browser-profile", BrowserSettings.currentProfile)
        assertEquals(listOf("browser-profile"), BrowserSettings.availableProfiles)
    }

    @Test
    fun testNormalProfileCreationAndResolution() {
        val profileId = BossDirectories.sanitizeProfileIdentifier("Development Profile")
        assertEquals("browser-profile-development-profile", profileId)
        assertTrue(BossDirectories.isValidProfileIdentifier(profileId))

        val resolved = BossDirectories.resolveContained(profileId)
        val canonicalRoot = BossDirectories.rootDir.canonicalFile
        assertEquals(canonicalRoot, resolved.canonicalFile.parentFile)
    }

    @Test
    @Suppress("SwallowedException")
    fun testNestedLinkDeletionDoesNotFollowSymlinks() {
        val profileDir = File(tempDir, "test-profile-dir")
        profileDir.mkdirs()

        val normalFile = File(profileDir, "data.txt")
        normalFile.writeText("profile data")

        val outsideDir = File(tempDir, "outside-secret-dir")
        outsideDir.mkdirs()
        val secretFile = File(outsideDir, "secret.txt")
        secretFile.writeText("do not delete")

        val symlinkFile = File(profileDir, "nested-link")
        try {
            Files.createSymbolicLink(symlinkFile.toPath(), outsideDir.toPath())
        } catch (e: FileSystemException) {
            // Windows environment without Developer Mode / admin privilege cannot create symbolic links
            return
        }

        assertTrue(profileDir.exists())
        assertTrue(outsideDir.exists())
        assertTrue(secretFile.exists())

        val deleted = BossDirectories.deleteSafelyWithoutFollowingLinks(profileDir)
        assertTrue(deleted)
        assertFalse(profileDir.exists())

        // Ensure outside target directory and secret file were NOT deleted
        assertTrue(outsideDir.exists(), "Outside target directory must remain intact")
        assertTrue(secretFile.exists(), "File inside outside directory must remain intact")
    }
}
