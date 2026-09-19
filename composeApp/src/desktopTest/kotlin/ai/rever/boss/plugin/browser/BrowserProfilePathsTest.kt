package ai.rever.boss.plugin.browser

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserProfilePathsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `display names become bounded single-component identifiers`() {
        assertEquals("browser-profile-work-personal", BrowserProfilePaths.idForDisplayName(" Work / Personal "))
        assertEquals("browser-profile-windows-path", BrowserProfilePaths.idForDisplayName("Windows\\Path"))
        assertEquals(null, BrowserProfilePaths.idForDisplayName("../../"))

        val longId = requireNotNull(BrowserProfilePaths.idForDisplayName("a".repeat(500)))
        assertTrue(longId.length <= 96)
        assertTrue(BrowserProfilePaths.isValidId(longId))
    }

    @Test
    fun `only bounded browser profile identifiers are accepted`() {
        val valid =
            listOf(
                "browser-profile",
                "browser-profile-work",
                "browser-profile-work_2",
                "browser-profile-1723456789",
            )
        valid.forEach { assertTrue(BrowserProfilePaths.isValidId(it), it) }

        val invalid =
            listOf(
                "",
                ".",
                "..",
                "../../Documents",
                "..\\..\\Documents",
                "/tmp/profile",
                "C:\\Users\\profile",
                "browser-profile/other",
                "browser-profile\\other",
                "browser-profile-",
                "browser-profile-${"a".repeat(100)}",
            )
        invalid.forEach { assertFalse(BrowserProfilePaths.isValidId(it), it) }
    }

    @Test
    fun `temporary profile detection excludes named profiles`() {
        assertTrue(BrowserProfilePaths.isTemporaryId("browser-profile-1723456789"))
        assertFalse(BrowserProfilePaths.isTemporaryId("browser-profile"))
        assertFalse(BrowserProfilePaths.isTemporaryId("browser-profile-work"))
        assertFalse(BrowserProfilePaths.isTemporaryId("browser-profile-work-2"))
        assertFalse(BrowserProfilePaths.isTemporaryId("browser-profile-${"1".repeat(100)}"))
    }

    @Test
    fun `resolution stays one component beneath the supplied root`() {
        val resolved = BrowserProfilePaths.resolve("browser-profile-work", root)
        assertEquals(root.toAbsolutePath().normalize(), resolved.parent)
        assertEquals("browser-profile-work", resolved.fileName.toString())

        assertFailsWith<IllegalArgumentException> {
            BrowserProfilePaths.resolve("../../outside", root)
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserProfilePaths.resolve("..\\..\\outside", root)
        }
    }

    @Test
    fun `normalization filters corrupt persisted values and keeps a safe fallback`() {
        val normalized =
            BrowserProfilePaths.normalizeSelection(
                current = "../../Documents",
                available =
                    listOf(
                        "browser-profile-work",
                        "../../Documents",
                        "browser-profile-work",
                        "C:\\Users\\profile",
                    ),
            )

        assertEquals(BrowserProfilePaths.DEFAULT_PROFILE_ID, normalized.current)
        assertEquals(listOf("browser-profile", "browser-profile-work"), normalized.available)
    }

    @Test
    fun `normalization retains a valid current profile missing from the available list`() {
        val normalized =
            BrowserProfilePaths.normalizeSelection(
                current = "browser-profile-research",
                available = emptyList(),
            )

        assertEquals("browser-profile-research", normalized.current)
        assertEquals(listOf("browser-profile", "browser-profile-research"), normalized.available)
    }

    @Test
    fun `recursive deletion unlinks a nested symlink without deleting its target`() {
        val external = Files.createDirectory(root.resolve("external"))
        val canary = Files.writeString(external.resolve("KEEP_ME.txt"), "keep")
        val profile = Files.createDirectories(root.resolve("browser-profile-test/nested"))
        Files.writeString(profile.resolve("ordinary.txt"), "delete")
        val link = profile.resolve("outside-link")
        assumeTrue(createDirectoryLink(link, external), "directory links are not available on this test platform")

        assertTrue(BrowserProfilePaths.delete("browser-profile-test", root))
        assertFalse(Files.exists(root.resolve("browser-profile-test")))
        assertTrue(Files.exists(canary), "deletion followed the nested link outside the profile")
    }

    @Test
    fun `a profile path that is itself a symlink is unlinked rather than traversed`() {
        val external = Files.createDirectory(root.resolve("external-profile-target"))
        val canary = Files.writeString(external.resolve("KEEP_ME.txt"), "keep")
        val profileLink = root.resolve("browser-profile-linked")
        assumeTrue(createDirectoryLink(profileLink, external), "directory links are not available on this test platform")

        assertTrue(BrowserProfilePaths.delete("browser-profile-linked", root))
        assertFalse(Files.exists(profileLink))
        assertTrue(Files.exists(canary), "deletion followed the profile link outside the profile root")
    }

    /** Windows junctions do not require Developer Mode; other platforms use a directory symlink. */
    private fun createDirectoryLink(
        link: Path,
        target: Path,
    ): Boolean =
        runCatching {
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start()
                    .run {
                        inputStream.bufferedReader().use { it.readText() }
                        waitFor() == 0
                    }
            } else {
                Files.createSymbolicLink(link, target)
                true
            }
        }.getOrDefault(false)
}
