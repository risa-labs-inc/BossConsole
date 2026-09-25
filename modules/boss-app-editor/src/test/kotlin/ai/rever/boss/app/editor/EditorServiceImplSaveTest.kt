package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.ipc.proto.services.SaveFileRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the editor service's save path (BossConsole#885).
 *
 * The out-of-process EditorService is the editor's durable-save surface. Before
 * #885 its path gate ran on the RAW string (a `..` literal plus a POSIX-only
 * `/etc`-style blocklist - Windows system paths passed untouched) and saveFile
 * truncated the target in place, so a crash mid-save tore the user's source
 * file. The fix canonicalizes BEFORE validating, confines everything to the
 * user's home, and saves atomically (temp sibling + atomic move).
 *
 * The impl is instantiated directly (the gRPC base is a no-op for these
 * paths); the tests drive the real file code with real symlinks. The
 * confinement root is injected per test, so no process-global user.home
 * mutation is needed.
 */
class EditorServiceImplSaveTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("editor-save-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun impl() = EditorServiceImpl(root = tempDir)

    private fun saveRequest(path: File): SaveFileRequest =
        SaveFileRequest
            .newBuilder()
            .setPath(path.absolutePath)
            .setContent("saved content\n")
            .build()

    private fun openRequest(path: File): OpenFileRequest =
        OpenFileRequest
            .newBuilder()
            .setPath(path.absolutePath)
            .build()

    @Test
    fun `a save of a read-only file succeeds and leaves no temp litter`() {
        // Pins the SUCCESS path end to end, including the case a plain writeText
        // cannot even do: replacing a 0444 target. writeText truncates in place,
        // so it fails on a read-only file and leaves the old content; the atomic
        // shape writes a temp and moves it over, governed by the directory
        // bits, and the mode is re-applied AFTER the temp write so a 0444
        // target does not make the temp unwritable. It doubles as a mutation
        // detector for the atomic shape (reverting atomicWrite to writeText
        // fails here), but it early-returns on Windows and degrades to a
        // tautology when the JVM runs as ROOT (root ignores the missing write
        // bit and writeText succeeds) - the portable torn-target detector is
        // `a failed save of an existing file leaves the previous content intact`.
        // (POSIX-only: setPosixFilePermissions throws on the Windows default
        // filesystem.)
        if (Files.getFileAttributeView(
                tempDir.toPath(),
                PosixFileAttributeView::class.java,
            ) == null
        ) {
            return
        }
        val target = File(tempDir, "locked/readonly.txt").apply { parentFile.mkdirs() }
        target.writeText("original complete content\n")
        Files.setPosixFilePermissions(
            target.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
        )
        runBlocking { impl().saveFile(saveRequest(target)) }
        assertEquals("saved content\n", target.readText())
        val parts = target.parentFile.listFiles { f -> f.name.endsWith(".part") } ?: emptyArray()
        assertTrue(parts.isEmpty(), "a successful save leaves no temp litter: ${parts.map { it.name }}")
        val perms = Files.getPosixFilePermissions(target.toPath())
        assertTrue(
            PosixFilePermission.OWNER_READ in perms,
            "the target's mode must survive the atomic replace, got $perms",
        )
    }

    @Test
    fun `a path outside the user's home is refused`() {
        // Outside home: on the test harness, home is the temp dir, so an
        // absolute path to a sibling outside it is the escape shape.
        val outside = File(tempDir.parentFile ?: File("/"), "escape-target.txt")
        val e =
            assertFailsWith<StatusRuntimeException> {
                runBlocking { impl().openFile(openRequest(outside)) }
            }
        assertEquals(Status.Code.PERMISSION_DENIED, e.status.code)
    }

    @Test
    fun `a symlink inside the home pointing at an outside target is refused after canonicalization`() {
        // createTempDirectory lands under the OS temp dir, which on Windows is
        // INSIDE the real user home (AppData\Local\Temp) - not an escape target.
        // The confinement home for THIS test is the per-test tempDir (redirected
        // user.home), so any sibling OUTSIDE it works; the user's own drive root
        // temp area next to it is what we use here.
        val outsideDir = Files.createTempDirectory("boss-editor-outside-").toFile()
        val outsideTarget = File(outsideDir, "secret.txt").apply { writeText("outside\n") }
        val link = File(tempDir, "innocent-link.txt")
        Files.createSymbolicLink(link.toPath(), outsideTarget.toPath())

        // The raw path contains no `..` and lives inside home; the canonical
        // target does not - the old raw-string gate passed this shape.
        val e =
            assertFailsWith<StatusRuntimeException> {
                runBlocking { impl().openFile(openRequest(link)) }
            }
        assertEquals(Status.Code.PERMISSION_DENIED, e.status.code)
        outsideDir.deleteRecursively()
    }

    @Test
    fun `traversal sequences are still refused`() {
        // The old raw-string `..` ban is gone: the refusal now comes from
        // normalization + confining the resolved result to the root.
        val outside = File(tempDir.parentFile ?: File("/"), "trav-${java.util.UUID.randomUUID()}/../escape.txt")
        val e =
            assertFailsWith<StatusRuntimeException> {
                runBlocking { impl().openFile(openRequest(outside)) }
            }
        assertEquals(Status.Code.PERMISSION_DENIED, e.status.code)
    }

    @Test
    fun `legitimate names containing dots are no longer false-refused`() {
        // The raw `..` substring check used to refuse these; resolution +
        // confinement is the real gate.
        val dotDot = File(tempDir, "notes..txt").apply { writeText("ok\n") }
        val res = runBlocking { impl().openFile(openRequest(dotDot)) }
        assertTrue(res.success, res.errorMessage)
    }

    @Test
    fun `a refused outside-home save creates no directories outside the confinement root`() {
        // A save whose target does not exist yet used to mkdirs its parent BEFORE
        // validating (the new-file case), so a refused outside-home path still
        // created directories outside the confinement root. The gate now resolves
        // the deepest existing ancestor first and refuses before any mkdirs.
        val escapeDir = File(tempDir.parentFile ?: File("/"), "escape-parent-${java.util.UUID.randomUUID()}")
        val outside = File(escapeDir, "newdir/secret.txt")
        val e =
            assertFailsWith<StatusRuntimeException> {
                runBlocking { impl().saveFile(saveRequest(outside)) }
            }
        assertEquals(Status.Code.PERMISSION_DENIED, e.status.code)
        assertFalse(escapeDir.exists(), "the gate must not create directories outside the confinement root")
    }

    @Test
    fun `a new-file save into a fresh subdirectory inside the home still succeeds`() {
        // No caller pre-creates the parent anymore: validatePath resolves the
        // deepest existing ancestor and re-appends the missing tail, and
        // atomicWrite mkdirs the validated parent itself.
        val target = File(tempDir, "fresh/deep/nested/NewFile.kt")
        runBlocking { impl().saveFile(saveRequest(target)) }
        assertEquals("saved content\n", target.readText())
    }

    @Test
    fun `an open whose parent directory is gone reports not-found, not an error`() {
        // A stale recent-files entry under a deleted folder: the gate's
        // NOT_FOUND (parent raced away) must map to the File-not-found
        // response, not an RPC error.
        val goneDir = File(tempDir, "vanished").apply { mkdirs() }
        val stale = File(goneDir, "Stale.kt").apply { writeText("x\n") }
        goneDir.deleteRecursively()
        val res = runBlocking { impl().openFile(openRequest(stale)) }
        assertFalse(res.success)
        assertEquals("File not found: ${stale.absolutePath}", res.errorMessage)
    }

    @Test
    fun `a failed save propagates on the wire instead of reading as success`() {
        // The old code caught every exception and returned the same Empty a
        // successful save returns; the client marks the buffer clean on Empty,
        // so a disk-full save lost the edit silently. A write failure must
        // surface as an INTERNAL error. Portable failure injection: a parent
        // path component that is a REGULAR FILE - mkdirs/createTempFile fail
        // with IOException on every platform (a read-only directory would not
        // stop createTempFile on Windows, where the DOS read-only bit does
        // not block file creation).
        val blocker = File(tempDir, "ro").apply { writeText("not a directory\n") }
        val target = File(blocker, "file.kt")
        val e =
            assertFailsWith<StatusRuntimeException> {
                runBlocking { impl().saveFile(saveRequest(target)) }
            }
        assertEquals(Status.Code.INTERNAL, e.status.code)
        assertEquals("not a directory\n", blocker.readText(), "the blocker must be untouched")
        assertFalse(target.exists(), "a failed save must not create the target")
    }

    @Test
    fun `a failed save of an existing file leaves the previous content intact`() {
        // The mutation detector for the atomic shape. The target pre-exists
        // with known bytes and the failure is injected where ONLY the atomic
        // shape fails: createTempFile in a read-only DIRECTORY (POSIX -
        // clearing the directory's write bit still lets a plain writeText
        // REOPEN the existing 644 target, so writeText fully succeeds and
        // assertFailsWith finds no exception - that is how this test catches
        // the revert, not via a torn target). The atomic path fails BEFORE
        // writing any byte, so the previous-content assertion is trivially
        // true here; the stronger property - a write that dies PARTWAY
        // leaves the previous complete version - needs a failure inside the
        // content write (a Writer seam or a size-limited tmpfs) and is not
        // asserted in this suite. (Running as ROOT degrades the injection
        // because root ignores the missing write bit - the probe below
        // detects that and skips instead of asserting the wrong status.)
        if (Files.getFileAttributeView(
                tempDir.toPath(),
                PosixFileAttributeView::class.java,
            ) == null
        ) {
            return
        }
        // Probe the injection itself: when the JVM runs as ROOT the read-only
        // bit does not block file creation, the save would succeed, and the
        // assertion that it fails would be wrong - skip instead of lying.
        val probeDir = File(tempDir, "probe-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        probeDir.setWritable(false)
        val injectionWorks =
            runCatching { File.createTempFile("probe", ".part", probeDir).delete() }.isFailure
        probeDir.setWritable(true)
        if (!injectionWorks) {
            probeDir.deleteRecursively()
            return
        }
        val target = File(tempDir, "protected/Existing.kt").apply { parentFile.mkdirs() }
        target.writeText("precious complete content\n")
        target.parentFile.setWritable(false)
        try {
            val e =
                assertFailsWith<StatusRuntimeException> {
                    runBlocking { impl().saveFile(saveRequest(target)) }
                }
            assertEquals(Status.Code.INTERNAL, e.status.code)
        } finally {
            target.parentFile.setWritable(true)
        }
        assertEquals(
            "precious complete content\n",
            target.readText(),
            "a failed save must leave the previous content byte-for-byte intact",
        )
    }

    @Test
    fun `a filesystem-root confinement root does not silently refuse everything`() {
        // A container that sets HOME=/ (or a drive root) used to break the
        // string-prefix check: root + separator yields `//`, which nothing
        // starts with, so every save was denied and the service silently
        // inert. Component-wise Path.startsWith handles the root root. The
        // root is derived from the fixture's OWN filesystem root rather than
        // hardcoding `/`: on Windows `File("/")` resolves against the CWD's
        // drive while tempDir comes from java.io.tmpdir, and a GitHub
        // windows-latest checkout (D:) with TEMP on C: would put the fixture
        // outside the root and flip this into a spurious refusal.
        val impl = EditorServiceImpl(root = tempDir.toPath().root.toFile())
        val target = File(tempDir, "under-root/Saved.kt")
        runBlocking { impl.saveFile(saveRequest(target)) }
        assertEquals("saved content\n", target.readText())
    }

    @Test
    fun `a save of a very long filename does not overflow the temp prefix`() {
        // createTempFile appends random digits plus the .part suffix; at the
        // usual 255-byte NAME_MAX a ~230-char name used to overflow and fail
        // EVERY save of that file. The prefix is capped at 64 chars.
        val longName = "L" + "o".repeat(230) + ".kt"
        val target = File(tempDir, longName)
        runBlocking { impl().saveFile(saveRequest(target)) }
        assertEquals("saved content\n", target.readText())
    }

    @Test
    fun `a stale out-of-root path whose parent is gone is refused, not reported not-found`() {
        // Deny before not-found: an out-of-root path must read as a
        // confinement refusal (PERMISSION_DENIED), never a NOT_FOUND the
        // caller can probe with to learn whether the parent once existed.
        val outsideDir = File(tempDir.parentFile ?: File("/"), "vanished-outside-${java.util.UUID.randomUUID()}")
        outsideDir.mkdirs()
        val stale = File(outsideDir, "gone/Gone.kt")
        try {
            val e =
                assertFailsWith<StatusRuntimeException> {
                    runBlocking { impl().openFile(openRequest(stale)) }
                }
            assertEquals(Status.Code.PERMISSION_DENIED, e.status.code)
        } finally {
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `a dangling symlink inside the root is refused, not saved through`() {
        // A broken link counts as present (NOFOLLOW_LINKS probe) so it is its
        // own anchor; toRealPath then fails and the gate refuses - fail-closed
        // on a link nobody can follow. Driven through saveFile (no NOT_FOUND
        // mapping) so the gate's own status and message are what land on the
        // wire; openFile maps the same condition to its not-found RESPONSE
        // (success=false) but keeps the gate's description, which names the
        // symlink condition. (Requires symlink support; CI platforms have it,
        // Windows needs Developer Mode.)
        if (Files.getFileAttributeView(
                tempDir.toPath(),
                PosixFileAttributeView::class.java,
            ) == null
        ) {
            return
        }
        val link = File(tempDir, "dangling.kt")
        Files.createSymbolicLink(link.toPath(), File(tempDir, "never-existed.kt").toPath())
        val e =
            assertFailsWith<StatusRuntimeException> {
                runBlocking { impl().saveFile(saveRequest(link)) }
            }
        assertEquals(Status.Code.NOT_FOUND, e.status.code)
        val description = e.status.description.orEmpty()
        assertTrue(
            description.contains("Symlink could not be resolved"),
            "the refusal must name the symlink condition, not read as a deleted parent: $description",
        )
    }

    @Test
    fun `a symlink whose target is behind an inaccessible directory is a permission fault, not not-found`() {
        // A link whose target path the kernel refuses to traverse (a
        // mode-000 directory) makes toRealPath throw AccessDeniedException,
        // not NoSuchFileException. openFile / detectMainFunctions both MAP
        // NOT_FOUND (File-not-found response / empty scan), so the access
        // fault must NOT read as NOT_FOUND - the user's problem is a
        // directory mode they can fix, not a vanished file. The gate must
        // surface PERMISSION_DENIED, and the wording must name the SYMLINK
        // condition so a bug report can tell it apart from a deleted
        // directory. (POSIX-only; skipped when the JVM can ignore the mode,
        // as root does.)
        if (Files.getFileAttributeView(
                tempDir.toPath(),
                PosixFileAttributeView::class.java,
            ) == null
        ) {
            return
        }
        val gate = File(tempDir, "noperm-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        File(gate, "secret.txt").writeText("secret\n")
        gate.setReadable(false)
        gate.setExecutable(false)
        gate.setWritable(false)
        val modeBites = runCatching { File.createTempFile("probe", ".t", gate).delete() }.isFailure
        if (!modeBites) {
            // Root (or equivalent): the mode does not block, so the
            // AccessDeniedException cannot be produced - skip.
            gate.setReadable(true)
            gate.setExecutable(true)
            gate.setWritable(true)
            gate.deleteRecursively()
            return
        }
        val link = File(tempDir, "link-${java.util.UUID.randomUUID()}.txt")
        Files.createSymbolicLink(link.toPath(), File(gate, "secret.txt").toPath())
        try {
            val e =
                assertFailsWith<StatusRuntimeException> {
                    runBlocking { impl().saveFile(saveRequest(link)) }
                }
            assertEquals(Status.Code.PERMISSION_DENIED, e.status.code)
            val description = e.status.description.orEmpty()
            assertTrue(
                description.contains("Symlink target is not accessible"),
                "the refusal must name the symlink access-fault condition: $description",
            )
        } finally {
            gate.setReadable(true)
            gate.setExecutable(true)
            gate.setWritable(true)
            link.delete()
            gate.deleteRecursively()
        }
    }

    @Test
    fun `a save preserves the target's existing posix mode`() {
        // The atomic replace moves a fresh umask-0644 temp over the target, so
        // without re-applying the target's attributes an executable script
        // would lose +x and a 0600 file would WIDEN on Ctrl-S.
        if (Files.getFileAttributeView(
                tempDir.toPath(),
                PosixFileAttributeView::class.java,
            ) == null
        ) {
            return
        }
        val target = File(tempDir, "script.sh").apply { writeText("#!/bin/sh\necho hi\n") }
        Files.setPosixFilePermissions(
            target.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        runBlocking { impl().saveFile(saveRequest(target)) }
        val perms = Files.getPosixFilePermissions(target.toPath())
        assertTrue(
            PosixFilePermission.OWNER_EXECUTE in perms,
            "the executable bit must survive an atomic re-save, got $perms",
        )
    }

    @Test
    fun `a new file keeps the process umask mode after the private temp write`() {
        if (Files.getFileAttributeView(
                tempDir.toPath(),
                PosixFileAttributeView::class.java,
            ) == null
        ) {
            return
        }
        val probe = File.createTempFile("editor-mode-probe-", ".tmp", tempDir)
        val inheritedMode = Files.getPosixFilePermissions(probe.toPath())
        probe.delete()

        val target = File(tempDir, "new-source.kt")
        runBlocking { impl().saveFile(saveRequest(target)) }
        assertEquals(inheritedMode, Files.getPosixFilePermissions(target.toPath()))
    }
}
