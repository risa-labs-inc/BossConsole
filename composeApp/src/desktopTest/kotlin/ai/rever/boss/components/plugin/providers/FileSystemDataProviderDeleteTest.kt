package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNoException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the `FileSystemDataProvider.delete` boundary against #1118.
 *
 * The pre-fix delete walked with `File.deleteRecursively()`, which:
 *   - accepted a request for `System.getProperty("user.home")` itself and erased the profile the
 *     guard was meant to protect, and
 *   - followed directory symlinks under the validated root, so a permitted directory under
 *     `user.home` could remove entries outside the home directory via a nested symlink.
 *
 * These tests run against a synthetic `user.home` the Gradle test task creates fresh per run
 * (see `composeApp/build.gradle.kts`), so no real profile is ever at risk even if a guard fails.
 */
class FileSystemDataProviderDeleteTest {
    private lateinit var homeDir: Path
    private lateinit var provider: FileSystemDataProviderImpl

    @BeforeTest
    fun setUp() {
        // `user.home` is redirected per test task; this reads the SAME path so the test never
        // touches a real profile.
        val redirected = System.getProperty("user.home")
        assertNotNull(redirected, "user.home must be set by the Gradle test task")
        homeDir = Path.of(redirected).toAbsolutePath().normalize()
        Files.createDirectories(homeDir)
        provider = FileSystemDataProviderImpl()
    }

    @AfterTest
    fun tearDown() {
        // Best-effort cleanup; the task-level redirect deletes the test home wholesale, so this is
        // only here to keep each test's fixtures from leaking into siblings sharing the directory.
        runCatching {
            Files
                .walk(homeDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `deleting the home directory itself is refused`() {
        val canary = Files.createFile(homeDir.resolve("canary.txt"))

        val result = runBlocking { provider.delete(homeDir.toString()) }

        assertTrue(result.isFailure, "home dir delete should be refused")
        val failure = result.exceptionOrNull()
        assertTrue(failure is SecurityException, "expected SecurityException, got $failure")
        assertTrue(
            Files.exists(canary),
            "canary file at $canary must NOT be erased when the home-dir delete is refused",
        )
    }

    @Test
    fun `deleting a path that resolves to the home directory through a symlink is still refused`() {
        // The homeDir-itself check uses canonicalFile, so a symlink that resolves
        // to homeDir is refused for the same reason as the homeDir itself. The
        // user.home BOUNDARY was dropped, but the homeDir-itself guard remains.
        val alias = Files.createSymbolicLink(homeDir.resolve("alias"), homeDir)
        val canary = Files.createFile(homeDir.resolve("canary.txt"))

        val result = runBlocking { provider.delete(alias.toString()) }

        assertTrue(result.isFailure, "symlink-to-home delete should still be refused; got $result")
        assertTrue(
            Files.exists(canary),
            "canary at $canary must NOT be erased when the symlink-to-home delete is refused",
        )
    }

    @Test
    fun `deleting a path with parent traversal that escapes home is refused`() {
        // Place a sibling under the homeDir's parent so homeDir/../sibling resolves to a
        // real directory outside home. The component-aware home-containment check must
        // canonicalise the path and refuse the request, leaving the canary intact.
        val sibling = Files.createDirectory(homeDir.parent.resolve("fsd-provider-sibling-${System.nanoTime()}"))
        try {
            val siblingCanary = Files.createFile(sibling.resolve("canary.txt"))
            val traversalPath = homeDir.resolve("..").resolve(sibling.fileName).resolve("canary.txt")

            val result = runBlocking { provider.delete(traversalPath.toString()) }

            assertTrue(result.isFailure, "traversal that escapes home should be refused; got $result")
            val failure = result.exceptionOrNull()
            assertTrue(failure is SecurityException, "expected SecurityException, got $failure")
            assertTrue(
                Files.exists(siblingCanary),
                "sibling canary at $siblingCanary must NOT be erased when traversal is refused",
            )
        } finally {
            Files.walk(sibling).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `deleting a symlink whose target lives outside home is refused`() {
        // The link itself sits under homeDir, but its canonical target is outside. The
        // component-aware containment check runs on the canonical path, so the request is
        // refused and the external canary is left intact. The nested/chain tests below
        // pin the separate NOFOLLOW_LINKS guarantee for in-scope directories.
        //
        // `Files.createTempDirectory` lands under the system temp dir, which on Windows
        // is itself under `user.home`; that would put the canary IN-SCOPE and the delete
        // would correctly succeed. Place the outside directory next to homeDir so the
        // canonical resolution is unambiguously outside the home boundary.
        val outside = Files.createDirectory(homeDir.parent.resolve("fsd-provider-outside-${System.nanoTime()}"))
        try {
            val externalCanary = Files.createFile(outside.resolve("canary.txt"))
            val link =
                runCatching {
                    Files.createSymbolicLink(homeDir.resolve("outside-link"), outside)
                }
            assumeNoException("Symbolic links unavailable on this platform", link.exceptionOrNull())

            val result = runBlocking { provider.delete(link.getOrThrow().toString()) }

            assertTrue(result.isFailure, "symlink-to-outside should be refused; got $result")
            val failure = result.exceptionOrNull()
            assertTrue(failure is SecurityException, "expected SecurityException, got $failure")
            assertTrue(
                Files.exists(externalCanary),
                "external canary at $externalCanary must NOT be erased when the symlink-to-outside is refused",
            )
        } finally {
            Files.walk(outside).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `recursive delete does not follow a directory symlink nested under the target`() {
        // The permitted parent is `homeDir/work`. It contains a directory symlink to an external
        // directory holding a canary. Pre-fix, `deleteRecursively()` traversed the link and
        // removed the external canary; the fix must leave it intact.
        val work = Files.createDirectory(homeDir.resolve("work"))
        val outside = Files.createTempDirectory("fsd-provider-nested-")
        try {
            val externalCanary = Files.createFile(outside.resolve("canary.txt"))
            val linkResult =
                runCatching {
                    Files.createSymbolicLink(work.resolve("link"), outside)
                }
            assumeNoException("Symbolic links unavailable on this platform", linkResult.exceptionOrNull())

            val result = runBlocking { provider.delete(work.toString()) }

            assertTrue(result.isSuccess, "in-scope parent delete should succeed; got $result")
            assertFalse(Files.exists(work), "permitted parent dir should be gone")
            assertTrue(
                Files.exists(externalCanary),
                "canary at the nested symlink target must NOT be erased; #1118 escape",
            )
        } finally {
            Files.walk(outside).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `a chain of nested directory symlinks under home does not escape the boundary`() {
        // homeDir/a -> homeDir/b (also under home) -> outside. The top-level delete is in-scope
        // (homeDir/a is a real directory under home), so the request SUCCEEDS - but the
        // NOFOLLOW_LINKS walk must not cross the chain into `outside`. The pre-fix
        // `deleteRecursively()` walked through both hops and erased the canary at the end.
        val a = Files.createDirectory(homeDir.resolve("a"))
        val b = Files.createDirectory(homeDir.resolve("b"))
        val outside = Files.createTempDirectory("fsd-provider-chain-")
        try {
            val externalCanary = Files.createFile(outside.resolve("canary.txt"))
            val bToOutside = runCatching { Files.createSymbolicLink(b.resolve("escape"), outside) }
            assumeNoException("Symbolic links unavailable on this platform", bToOutside.exceptionOrNull())
            val aToB = runCatching { Files.createSymbolicLink(a.resolve("hop"), b) }
            assumeNoException("Symbolic links unavailable on this platform", aToB.exceptionOrNull())

            val result = runBlocking { provider.delete(a.toString()) }

            assertTrue(result.isSuccess, "in-scope top-level delete should succeed; got $result")
            assertFalse(Files.exists(a), "the permitted top-level dir should be gone")
            assertTrue(
                Files.exists(externalCanary),
                "canary beyond the nested symlink chain must NOT be erased; #1118 escape",
            )
        } finally {
            Files.walk(outside).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `a single legitimate file delete still works`() {
        val target = Files.createFile(homeDir.resolve("legit.txt"))
        Files.writeString(target, "ok")

        val result = runBlocking { provider.delete(target.toString()) }

        assertTrue(result.isSuccess, "in-scope file delete should succeed; got $result")
        assertFalse(Files.exists(target), "deleted file should be gone")
    }

    @Test
    fun `a legitimate recursive delete of an in-scope subtree still works`() {
        val root = Files.createDirectory(homeDir.resolve("subtree"))
        val child = Files.createDirectory(root.resolve("child"))
        val leaf = Files.createFile(child.resolve("leaf.txt"))
        Files.writeString(leaf, "ok")

        val result = runBlocking { provider.delete(root.toString()) }

        assertTrue(result.isSuccess, "in-scope subtree delete should succeed; got $result")
        assertFalse(Files.exists(root), "root should be gone")
        assertFalse(Files.exists(child), "child should be gone")
        assertFalse(Files.exists(leaf), "leaf should be gone")
    }

    @Test
    fun `deleting a missing file is treated as success to match the pre-fix contract`() {
        val missing = homeDir.resolve("never-existed.txt")

        val result = runBlocking { provider.delete(missing.toString()) }

        assertTrue(result.isSuccess, "missing file delete should succeed; got $result")
    }

    @Test
    fun `deleting a file symlink to an in-scope file removes the link, not the target`() {
        val target = Files.createFile(homeDir.resolve("target.txt"))
        Files.writeString(target, "keep-me")
        val linkResult =
            runCatching {
                Files.createSymbolicLink(homeDir.resolve("link.txt"), target)
            }
        assumeNoException("Symbolic links unavailable on this platform", linkResult.exceptionOrNull())

        val result = runBlocking { provider.delete(linkResult.getOrThrow().toString()) }

        assertTrue(result.isSuccess, "in-scope symlink delete should succeed; got $result")
        assertFalse(Files.exists(linkResult.getOrThrow()), "the symlink entry should be gone")
        assertTrue(
            Files.exists(target),
            "the file the symlink pointed at must remain; #1118 must not erase link targets",
        )
    }

    @Test
    fun `deleting a path that traverses a link with parent-dot is refused even when the OS resolves it out of home`() {
        // Pins the review finding on the #1118 PR: a request like `home/link/../<sibling>` has
        // an OS-resolved target that lives outside home (the link points to `outside`, so
        // `outside/..` is `outside`'s parent, not `home`), but the lexically-normalized form
        // cancels `link/..` back to home and LOOKS in scope. The containment check and the
        // walk have to resolve paths the same way, or the walk escapes even when the check
        // sees a benign-looking path.
        //
        // Shape: a temp home, a symlink `home/link -> outside`, a canary in a sibling of
        // `outside` (so `home/link/../<sibling>` OS-resolves to that canary), and a request
        // to delete it. The canary must remain and the call must be refused.
        val outside = Files.createDirectory(homeDir.parent.resolve("fsd-provider-linkdot-${System.nanoTime()}"))
        // The canary lives in `outside`'s parent, which is exactly where `home/link/..` lands.
        val canaryName = "fsd-linkdot-canary-${System.nanoTime()}"
        val siblingCanary = Files.createFile(outside.parent.resolve(canaryName))
        try {
            val linkResult =
                runCatching {
                    Files.createSymbolicLink(homeDir.resolve("link"), outside)
                }
            assumeNoException("Symbolic links unavailable on this platform", linkResult.exceptionOrNull())
            // The traversal path: `home/link/../<sibling>` - the OS walks `link` (the symlink),
            // then `..` from the symlink's TARGET (NOT from `home`), landing at `outside`'s
            // parent, then at `<sibling>`. Without the fix the walk used the unnormalized
            // input and reached this canary file.
            val traversalPath =
                homeDir
                    .resolve("link")
                    .resolve("..")
                    .resolve(siblingCanary.fileName)

            val result = runBlocking { provider.delete(traversalPath.toString()) }

            assertTrue(
                result.isFailure,
                "link-then-dotdot traversal that escapes home must be refused; got $result",
            )
            val failure = result.exceptionOrNull()
            assertTrue(failure is SecurityException, "expected SecurityException, got $failure")
            assertTrue(
                Files.exists(siblingCanary),
                "canary at $siblingCanary must NOT be erased when the link-then-dotdot traversal is refused",
            )
        } finally {
            // The canary lives in homeDir.parent, outside the redirected test home, so the
            // per-task redirect does not clean it up - delete it explicitly here.
            Files.deleteIfExists(siblingCanary)
            Files.walk(outside).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
