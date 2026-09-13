package ai.rever.boss.plugin.browser

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
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
    fun `every supported timestamp produces a cleanup-recognized name`() =
        withRoot { root ->
            val timestamps = listOf(0L, 1L, 1_720_000_000_000L, Long.MAX_VALUE)
            val fallbacks = timestamps.map { directory(root, TemporaryBrowserProfiles.newName(it)) }

            val result = cleanup(root)

            assertTrue(fallbacks.none { it.exists() })
            assertEquals(timestamps.size, result.deleted)
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
    fun `legacy fallback is preserved when persisted profile names are untrusted`() =
        withRoot { root ->
            val legacy = directory(root, "browser-profile-1720000000000")

            val result = cleanup(root, legacyTrusted = false)

            assertTrue(legacy.exists())
            assertEquals(0, result.deleted)
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

    @Test
    fun `cleanup follows the configured root symlink but not child symlinks`() {
        val parent = createTempDirectory("boss-profile-root-link-")
        try {
            val actualRoot = directory(parent, "actual-root")
            val rootLink = parent.resolve("configured-root")
            createSymbolicLinkOrSkip(rootLink, actualRoot)
            val fallback = directory(actualRoot, TemporaryBrowserProfiles.newName(8))

            val result = cleanup(rootLink)

            assertFalse(fallback.exists())
            assertEquals(1, result.deleted)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `active fallback remains protected even after the age cutoff`() =
        withRoot { root ->
            val name = TemporaryBrowserProfiles.newName(9)
            val active = directory(root, name, modified = 1_000)

            val result = cleanup(root, active = setOf(name), olderThan = 2_000)

            assertTrue(active.exists())
            assertEquals(0, result.deleted)
        }

    @Test
    fun `fallback owned through a Chromium lock survives cleanup`() =
        withRoot { root ->
            val fallback = directory(root, TemporaryBrowserProfiles.newName(90), modified = 1_000)
            val singletonLock = Files.createFile(fallback.resolve("SingletonLock"))

            FileChannel.open(singletonLock, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val result = cleanup(root, olderThan = 2_000)

                    assertTrue(fallback.exists())
                    assertEquals(0, result.deleted)
                }
            }
        }

    @Test
    fun `ownership check prevents deletion from being attempted`() =
        withRoot { root ->
            val fallback = directory(root, TemporaryBrowserProfiles.newName(91), modified = 1_000)
            var deletionAttempted = false

            val result =
                TemporaryBrowserProfiles.cleanup(
                    root = root,
                    protectedProfiles = setOf("browser-profile"),
                    legacyProfileNamesTrusted = true,
                    olderThanMillis = 2_000,
                    operations =
                        TemporaryProfileCleanupOperations(
                            isProfileInUse = { true },
                            deleteProfile = { deletionAttempted = true },
                        ),
                )

            assertTrue(fallback.exists())
            assertFalse(deletionAttempted)
            assertEquals(TemporaryBrowserProfiles.CleanupResult(deleted = 0, failed = 0), result)
        }

    @Test
    fun `a candidate removed by another cleaner is skipped`() =
        withRoot { root ->
            val fallback = directory(root, TemporaryBrowserProfiles.newName(10))

            val result =
                cleanup(root) { path ->
                    path.toFile().deleteRecursively()
                    throw NoSuchFileException(path.toString())
                }

            assertFalse(fallback.exists())
            assertEquals(TemporaryBrowserProfiles.CleanupResult(deleted = 0, failed = 0), result)
        }

    @Test
    fun `one deletion failure is reported without blocking another candidate`() =
        withRoot { root ->
            val blockedName = TemporaryBrowserProfiles.newName(11)
            val blocked = directory(root, blockedName)
            val removable = directory(root, TemporaryBrowserProfiles.newName(12))

            val result =
                cleanup(root) { path ->
                    if (path.fileName.toString() == blockedName) throw IOException("profile is locked")
                    path.toFile().deleteRecursively()
                }

            assertTrue(blocked.exists())
            assertFalse(removable.exists())
            assertEquals(1, result.deleted)
            assertEquals(1, result.failed)
            assertEquals(blockedName, result.firstFailure?.profile)
            assertEquals("profile is locked", result.firstFailure?.reason)
        }

    private fun cleanup(
        root: Path,
        registered: Set<String> = setOf("browser-profile"),
        current: String = "browser-profile",
        active: Set<String> = emptySet(),
        legacyTrusted: Boolean = true,
        olderThan: Long? = null,
        deleteProfile: ((Path) -> Unit)? = null,
    ): TemporaryBrowserProfiles.CleanupResult {
        val protected = registered + current + active
        return if (deleteProfile == null) {
            TemporaryBrowserProfiles.cleanup(
                root = root,
                protectedProfiles = protected,
                legacyProfileNamesTrusted = legacyTrusted,
                olderThanMillis = olderThan,
            )
        } else {
            TemporaryBrowserProfiles.cleanup(
                root = root,
                protectedProfiles = protected,
                legacyProfileNamesTrusted = legacyTrusted,
                olderThanMillis = olderThan,
                operations =
                    TemporaryProfileCleanupOperations(
                        isProfileInUse = { false },
                        deleteProfile = deleteProfile,
                    ),
            )
        }
    }

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
            assumeTrue(false, "Symbolic links are unavailable on this runner: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Symbolic links are unavailable on this runner: ${e.message}")
        } catch (e: SecurityException) {
            assumeTrue(false, "Symbolic links are unavailable on this runner: ${e.message}")
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
