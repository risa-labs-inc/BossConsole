package ai.rever.boss.components.plugin.providers

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemDataProviderDeleteTest {
    @Test
    fun `delete refuses the user home directory itself`() {
        val home = createTempDirectory("filesystem-provider-home").toFile()
        try {
            val canary = File(home, "keep.txt").apply { writeText("keep") }

            val result = deleteUserPath(home, home)

            assertIs<SecurityException>(result.exceptionOrNull())
            assertEquals("keep", canary.readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `recursive delete removes an ordinary descendant tree`() {
        val home = createTempDirectory("filesystem-provider-home").toFile()
        try {
            val target = File(home, "workspace").apply { mkdirs() }
            File(target, "nested/file.txt").apply {
                parentFile.mkdirs()
                writeText("delete")
            }

            assertTrue(deleteUserPath(target, home).isSuccess)
            assertFalse(target.exists())
            assertTrue(home.isDirectory)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `recursive delete removes a nested symlink without touching its target`() {
        val root = createTempDirectory("filesystem-provider-symlink").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val target = File(home, "workspace").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canary = File(outside, "keep.txt").apply { writeText("keep") }
            val link = File(target, "external")
            assumeTrue(
                runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            assertTrue(deleteUserPath(target, home).isSuccess)
            assertFalse(target.exists())
            assertEquals("keep", canary.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `delete through a symlink to outside home is refused on every platform`() {
        // Pins fluck-boss's #1119 follow-up: `home/link/somefile` where `link` points at a
        // directory outside home. On every platform this path stays an escape after lexical
        // normalization (Windows does NOT cancel the link via `..` here, because there is no
        // `..`), so the refusal is expected everywhere and the mutation check is shared.
        //
        // With the fix the containment check resolves `home/link` through the symlink first,
        // sees `outside`, refuses the call, and the target file survives. Revert the fix and
        // the walk follows the link and erases the target on both POSIX and Windows - this
        // test's `outsideTarget.exists()` assertion is the one that goes red.
        val root = createTempDirectory("filesystem-provider-linkaccess").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val outsideTarget = File(outside, "kept.txt").apply { writeText("untouched") }
            val link = File(home, "link-to-outside")
            assumeTrue(
                runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            val result = deleteUserPath(File(home, "link-to-outside/kept.txt"), home)

            assertTrue(
                result.isFailure,
                "a delete that traverses a symlink out of home must be refused; got $result",
            )
            assertIs<SecurityException>(result.exceptionOrNull())
            assertTrue(
                outsideTarget.exists(),
                "target file at $outsideTarget must survive a refused link-traversal",
            )
            assertEquals(
                "untouched",
                outsideTarget.readText(),
                "target file at $outsideTarget must keep its contents after a refused link-traversal",
            )
            // The link itself stays put - the refusal is at the containment check, not an
            // unlink. Removing the link as a side effect would be a separate containment
            // violation: the link is inside home, but its target is not, and a refused call
            // should leave the user's filesystem exactly as it found it.
            assertTrue(link.exists(), "refusing a delete must not remove the link itself")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a relative symlink target that escapes home is refused on every platform`() {
        // Pins fluck-boss's #1119 second review: `home/link -> ../outside` (relative target
        // with `..`) opens a containment hole in the previous `resolveSymlinksFirst` because
        // a relative target was returned without re-walking its components. A request for
        // `home/link/sub` produced `<root>/home/../outside/sub`; the name-by-name containment
        // check admitted it as inside home and the walk erased `outside/sub`.
        //
        // The recursive walk now re-resolves the link's target through the same component
        // walker, so `../outside` becomes `<root>/outside` and the resulting path is
        // unambiguously outside home. The test fails on revert on both POSIX and Windows.
        val root = createTempDirectory("filesystem-provider-relsym").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val outsideTarget = File(outside, "sub.txt").apply { writeText("untouched") }
            val link = File(home, "link")
            assumeTrue(
                runCatching { Files.createSymbolicLink(link.toPath(), Path.of("../outside")) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            val result = deleteUserPath(File(home, "link/sub.txt"), home)

            assertTrue(
                result.isFailure,
                "a relative symlink that escapes home must be refused; got $result",
            )
            assertIs<SecurityException>(result.exceptionOrNull())
            assertTrue(
                outsideTarget.exists(),
                "target at $outsideTarget must survive a refused relative-link traversal",
            )
            assertEquals(
                "untouched",
                outsideTarget.readText(),
                "target at $outsideTarget must keep its contents after a refused relative-link traversal",
            )
            assertTrue(link.exists(), "refusing a delete must not remove the link itself")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a chained symlink that escapes home is refused on every platform`() {
        // Pins fluck-boss's #1119 second review, second case: a link whose target is itself
        // a link. `home/a -> b`, `home/b -> ../outside` - the previous `resolveSymlinksFirst`
        // followed `a` to `b` and stopped, leaving the walker pointing at `b` (a link).
        // The recursive walk now follows `b` too, lands at `outside`, and refuses.
        val root = createTempDirectory("filesystem-provider-chained").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val outsideTarget = File(outside, "sub.txt").apply { writeText("untouched") }
            val b = File(home, "b")
            val a = File(home, "a")
            assumeTrue(
                runCatching { Files.createSymbolicLink(b.toPath(), Path.of("../outside")) }.isSuccess,
                "symlink creation unavailable on this platform",
            )
            assumeTrue(
                runCatching { Files.createSymbolicLink(a.toPath(), Path.of("b")) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            val result = deleteUserPath(File(home, "a/sub.txt"), home)

            assertTrue(
                result.isFailure,
                "a chained symlink that escapes home must be refused; got $result",
            )
            assertIs<SecurityException>(result.exceptionOrNull())
            assertTrue(
                outsideTarget.exists(),
                "target at $outsideTarget must survive a refused chained-link traversal",
            )
            assertEquals(
                "untouched",
                outsideTarget.readText(),
                "target at $outsideTarget must keep its contents after a refused chained-link traversal",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a symlink cycle refuses without hanging`() {
        // Pins fluck-boss's #1119 second review, third case: `home/a -> b`, `home/b -> a`.
        // The recursive walk has a hop limit (MAX_SYMLINK_HOPS = 40) so a cycle is detected
        // and refused as SecurityException, not left to loop forever.
        val root = createTempDirectory("filesystem-provider-cycle").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val a = File(home, "a")
            val b = File(home, "b")
            assumeTrue(
                runCatching { Files.createSymbolicLink(a.toPath(), Path.of("b")) }.isSuccess,
                "symlink creation unavailable on this platform",
            )
            assumeTrue(
                runCatching { Files.createSymbolicLink(b.toPath(), Path.of("a")) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            val result = deleteUserPath(a, home)

            assertTrue(
                result.isFailure,
                "a symlink cycle must be refused (not hung); got $result",
            )
            assertIs<SecurityException>(result.exceptionOrNull())
            // Both links are still there - the refusal is at the containment check, not an
            // unlink of either side.
            assertTrue(a.exists(), "first cycle link must not be removed by the refused call")
            assertTrue(b.exists(), "second cycle link must not be removed by the refused call")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `link-then-dotdot traversal preserves the safety invariant on every platform`() {
        // Pins the review finding on the #1118 PR: a request like `home/link/../<sibling>` has
        // an OS-resolved target that lives outside home (the link points to `outside`, so
        // `outside/..` is `outside`'s parent, not `home`), but the lexically-normalized form
        // cancels `link/..` back to home and LOOKS in scope. The containment check and the
        // walk have to resolve paths the same way, or the walk escapes even when the check
        // sees a benign-looking path.
        //
        // Shape: a temp root, a `home` directory under it, a symlink `home/link -> outside`
        // (also under root), a canary in a sibling of `outside` (so `home/link/../<canary>`
        // OS-resolves to that canary on POSIX), AND a real file at the lexically-resolved
        // path (`home/canary`).
        //
        // The home/canary file is the mutation check: on Windows the pre-fix code lexically
        // resolves `home/link/../canary` to `home/canary` (a real file with this in place),
        // the containment check admits it as in-scope, and the walk deletes it. The fix
        // makes the containment check resolve the symlink first, see the escape, refuse the
        // call, and the file - and every other thing outside home - is left intact. The
        // POSIX side has always refused (symlink-first-then-lexical gives the same OS-resolved
        // path either way), so the assertion `home/canary survives` would be the one that
        // turns red when the fix is reverted on Windows.
        //
        // The exact failure mode differs:
        //   - POSIX: the OS walks `link` first, so `link/..` lands at `outside`'s parent
        //     (`root`), the containment check sees an escape, and the call refuses with
        //     SecurityException.
        //   - Windows: `..` is resolved lexically BEFORE the link is followed, so the
        //     traversal path canonicalizes to `home/canary`. Without the fix the containment
        //     check sees something inside home and admits it; the walk then erases the
        //     `home/canary` decoy. With the fix the containment check sees the escape and
        //     refuses; the walk never runs.
        val root = createTempDirectory("filesystem-provider-linkdot").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canaryName = "fsd-linkdot-canary"
            // The canary lives in `outside`'s parent, which is exactly where `home/link/..`
            // OS-lands on POSIX.
            val siblingCanary = File(root, canaryName).apply { writeText("keep") }
            // Decoy: a real file at the path Windows' lexical cancellation produces. Its
            // survival under a reverted fix is the regression signal.
            val lexicalDecoy = File(home, canaryName).apply { writeText("decoy") }
            val link = File(home, "link")
            assumeTrue(
                runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            // The traversal path: `home/link/../<canary>` - on POSIX the OS walks `link`
            // (the symlink) then `..` from the symlink's TARGET (NOT from `home`), landing
            // at `outside`'s parent (= `root`), then at `<canary>`. On Windows the `..` is
            // resolved lexically first and the path becomes `home/canary` instead.
            val traversalPath = File(home, "link/../$canaryName")

            // Exercise the API; the specific Result outcome is platform-specific.
            deleteUserPath(traversalPath, home)

            // Safety property 1: the canary outside home survives untouched.
            assertTrue(
                siblingCanary.exists(),
                "canary at $siblingCanary must NOT be erased by a link-then-dotdot traversal",
            )
            assertEquals(
                "keep",
                siblingCanary.readText(),
                "canary at $siblingCanary must NOT be erased by a link-then-dotdot traversal",
            )
            // Safety property 2: nothing under `outside` was touched either - that's
            // where a walk that escaped home would land.
            assertTrue(
                outside.isDirectory,
                "the outside directory must remain intact after a link-then-dotdot traversal",
            )
            // Safety property 3 (mutation check): the decoy at the lexically-resolved path
            // survives too. Without the fix on Windows the walk reaches `home/canary` and
            // erases it - this assertion turns red. With the fix the containment check
            // refuses the call and the walk never runs.
            assertTrue(
                lexicalDecoy.exists(),
                "decoy at $lexicalDecoy must NOT be erased " +
                    "(Windows lexical-cancellation walk would reach it without the fix)",
            )
            assertEquals(
                "decoy",
                lexicalDecoy.readText(),
                "decoy at $lexicalDecoy must keep its contents " +
                    "(Windows lexical-cancellation walk would delete it without the fix)",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `link-then-dotdot traversal is refused on POSIX`() {
        // The "the walk must refuse with SecurityException" outcome is POSIX-specific:
        // the OS-resolved `link/..` lands at `outside`'s parent, the containment check
        // sees the escape and throws. Windows lexically normalizes `link/..` to home
        // before following the link, so the call takes a different path there - the
        // safety property test above is the assertion that holds on every platform.
        assumeFalse(
            System.getProperty("os.name").lowercase().contains("windows"),
            "POSIX-only refusal assertion; the safety property test covers Windows",
        )
        val root = createTempDirectory("filesystem-provider-linkdot").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val canaryName = "fsd-linkdot-canary"
            val siblingCanary = File(root, canaryName).apply { writeText("keep") }
            val link = File(home, "link")
            assumeTrue(
                runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            val traversalPath = File(home, "link/../$canaryName")

            val result = deleteUserPath(traversalPath, home)

            assertTrue(
                result.isFailure,
                "link-then-dotdot traversal that escapes home must be refused; got $result",
            )
            assertIs<SecurityException>(result.exceptionOrNull())
            assertEquals(
                "keep",
                siblingCanary.readText(),
                "canary at $siblingCanary must NOT be erased when the link-then-dotdot traversal is refused",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `delete unlinks a symlink at the walk root without following it`() {
        // Regression guard for the `toRealPath()`-follows-symlinks trap: passing a
        // symlink to a directory as the delete target must remove the link itself and
        // leave the target tree intact. The containment check resolves through the
        // link, so without a separate `Files.isSymbolicLink` guard the walk would
        // erase the target's contents instead of unlinking the link.
        //
        // The target directory lives OUTSIDE home so that, even if a bug let the
        // walk follow the link, the deletion would not also have to worry about
        // crossing the containment boundary - the test fails on the simpler claim
        // "the link is gone, the target's contents are not".
        val root = createTempDirectory("filesystem-provider-symlinkroot").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            // Target sits OUTSIDE home so the test isolates the "follow vs unlink"
            // question from the "in-scope vs out-of-scope" question.
            val targetDir = File(root, "target-tree").apply { mkdirs() }
            val targetCanary = File(targetDir, "would-be-deleted.txt").apply { writeText("untouched") }
            val targetNested =
                File(targetDir, "nested/file.txt").apply {
                    parentFile.mkdirs()
                    writeText("also-untouched")
                }
            val link = File(home, "link-to-target")
            assumeTrue(
                runCatching { Files.createSymbolicLink(link.toPath(), targetDir.toPath()) }.isSuccess,
                "symlink creation unavailable on this platform",
            )

            assertTrue(deleteUserPath(link, home).isSuccess, "deleting a symlink at the root must succeed")

            assertFalse(link.exists(), "the symlink itself must be removed")
            assertFalse(
                Files.isSymbolicLink(link.toPath()),
                "no link should remain at $link after the delete",
            )
            // The target tree survives intact: the walk must not have followed the link.
            assertTrue(
                targetDir.isDirectory,
                "the symlink target directory must NOT be deleted by an unlink-only operation",
            )
            assertTrue(
                targetCanary.exists(),
                "files inside the symlink target must NOT be erased by an unlink-only operation",
            )
            assertEquals(
                "untouched",
                targetCanary.readText(),
                "the canary inside the symlink target must keep its contents",
            )
            assertTrue(
                targetNested.exists(),
                "nested files inside the symlink target must NOT be erased by an unlink-only operation",
            )
            assertEquals(
                "also-untouched",
                targetNested.readText(),
                "nested files inside the symlink target must keep their contents",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
