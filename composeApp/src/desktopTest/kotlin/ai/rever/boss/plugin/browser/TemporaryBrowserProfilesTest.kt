package ai.rever.boss.plugin.browser

import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporaryBrowserProfilesTest {
    @Test
    fun `new fallback names use a namespace named profiles cannot enter`() {
        assertEquals(
            "browser-temporary-profile-1720000000000",
            TemporaryBrowserProfiles.newName(1_720_000_000_000),
        )
    }

    @Test
    fun `negative timestamps are rejected instead of producing an uncleanable name`() {
        assertFailsWith<IllegalArgumentException> { TemporaryBrowserProfiles.newName(-1) }
    }

    @Test
    fun `startup cleanup removes a new fallback even when it is recent`() =
        withRoot { root ->
            val fallback = directory(root, TemporaryBrowserProfiles.newName(1_720_000_000_000))

            val result = cleanup(root)

            assertFalse(fallback.exists())
            assertEquals(TemporaryBrowserProfiles.CleanupResult(deleted = 1, failed = 0), result)
        }

    @Test
    fun `aged cleanup preserves a recent fallback`() =
        withRoot { root ->
            val fallback = directory(root, TemporaryBrowserProfiles.newName(1_720_000_000_000), modified = 2_000)

            val result = cleanup(root, olderThan = 2_000)

            assertTrue(fallback.exists())
            assertEquals(0, result.deleted)
        }

    @Test
    fun `aged cleanup removes only a fallback strictly older than its cutoff`() =
        withRoot { root ->
            val old = directory(root, TemporaryBrowserProfiles.newName(1), modified = 1_999)
            val atCutoff = directory(root, TemporaryBrowserProfiles.newName(2), modified = 2_000)

            val result = cleanup(root, olderThan = 2_000)

            assertFalse(old.exists())
            assertTrue(atCutoff.exists())
            assertEquals(1, result.deleted)
        }

    @Test
    fun `named profile is never selected by the temporary namespace`() =
        withRoot { root ->
            val work = directory(root, "browser-profile-work")

            val result = cleanup(root)

            assertTrue(work.exists())
            assertEquals(0, result.deleted)
        }

    @Test
    fun `registered timestamp-shaped legacy profile is protected`() =
        withRoot { root ->
            val numericName = "browser-profile-1720000000000"
            val numeric = directory(root, numericName)

            val result = cleanup(root, registered = setOf("browser-profile", numericName))

            assertTrue(numeric.exists())
            assertEquals(0, result.deleted)
        }

    @Test
    fun `current timestamp-shaped profile is protected even when settings list drifted`() =
        withRoot { root ->
            val currentName = "browser-profile-1720000000000"
            val current = directory(root, currentName)

            val result = cleanup(root, current = currentName)

            assertTrue(current.exists())
            assertEquals(0, result.deleted)
        }

    @Test
    fun `unregistered legacy fallback remains migration-cleanable`() =
        withRoot { root ->
            val legacy = directory(root, "browser-profile-1720000000000")

            val result = cleanup(root)

            assertFalse(legacy.exists())
            assertEquals(1, result.deleted)
        }

    @Test
    fun `short numeric named profile is not mistaken for a legacy fallback`() =
        withRoot { root ->
            val numeric = directory(root, "browser-profile-1234")

            cleanup(root)

            assertTrue(numeric.exists())
        }

    @Test
    fun `default profile and similarly prefixed directories survive`() =
        withRoot { root ->
            val default = directory(root, "browser-profile")
            val malformedTemporary = directory(root, "browser-temporary-profile-work")
            val suffix = directory(root, "browser-profile-1720000000000-copy")

            val result = cleanup(root)

            assertTrue(default.exists())
            assertTrue(malformedTemporary.exists())
            assertTrue(suffix.exists())
            assertEquals(0, result.deleted)
        }

    @Test
    fun `a regular file in the temporary namespace is not deleted`() =
        withRoot { root ->
            val file = Files.createFile(root.resolve(TemporaryBrowserProfiles.newName(5)))

            cleanup(root)

            assertTrue(file.exists())
        }

    @Test
    fun `a temporary-looking root symlink is not traversed or deleted`() =
        withRoot { root ->
            val outside = createTempDirectory("boss-profile-outside-")
            try {
                val marker = Files.writeString(outside.resolve("keep.txt"), "keep")
                val link = root.resolve(TemporaryBrowserProfiles.newName(6))
                createSymbolicLinkOrSkip(link, outside)

                val result = cleanup(root)

                assertTrue(link.exists())
                assertTrue(marker.exists())
                assertEquals(0, result.deleted)
            } finally {
                outside.toFile().deleteRecursively()
            }
        }

    @Test
    fun `nested symlink target survives deletion of its temporary parent`() =
        withRoot { root ->
            val outside = createTempDirectory("boss-profile-outside-")
            try {
                val marker = Files.writeString(outside.resolve("keep.txt"), "keep")
                val fallback = directory(root, TemporaryBrowserProfiles.newName(7))
                createSymbolicLinkOrSkip(fallback.resolve("outside"), outside)

                val result = cleanup(root)

                assertFalse(fallback.exists())
                assertTrue(marker.exists())
                assertEquals(1, result.deleted)
            } finally {
                outside.toFile().deleteRecursively()
            }
        }

    private fun cleanup(
        root: Path,
        registered: Set<String> = setOf("browser-profile"),
        current: String = "browser-profile",
        olderThan: Long? = null,
    ): TemporaryBrowserProfiles.CleanupResult =
        TemporaryBrowserProfiles.cleanup(
            root = root,
            registeredProfiles = registered,
            currentProfile = current,
            olderThanMillis = olderThan,
        )

    private fun directory(
        root: Path,
        name: String,
        modified: Long? = null,
    ): Path =
        Files.createDirectory(root.resolve(name)).also { directory ->
            if (modified != null) Files.setLastModifiedTime(directory, FileTime.fromMillis(modified))
        }

    private fun createSymbolicLinkOrSkip(
        link: Path,
        target: Path,
    ) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: IOException) {
            assumeNoException("Symbolic links are unavailable on this runner", e)
        } catch (e: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable on this runner", e)
        } catch (e: SecurityException) {
            assumeNoException("Symbolic links are unavailable on this runner", e)
        }
    }

    private fun withRoot(block: (Path) -> Unit) {
        val root = createTempDirectory("boss-profile-cleanup-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
