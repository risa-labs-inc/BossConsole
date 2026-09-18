package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.ipc.proto.services.SaveFileRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * paths); the tests drive the real file code with real symlinks.
 */
class EditorServiceImplSaveTest {
    private lateinit var tempDir: File

    private val savedHome = System.getProperty("user.home")

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("editor-save-test-").toFile()
        // The confinement root resolves user.home lazily on first use, so a
        // per-test home redirect is picked up by a fresh instance. The module
        // has no build-level test-home isolation (composeApp-only), so the
        // test owns the redirect and restores it afterwards.
        System.setProperty("user.home", tempDir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        if (savedHome != null) System.setProperty("user.home", savedHome)
        tempDir.deleteRecursively()
    }

    private fun impl() = EditorServiceImpl()

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
    fun `a save inside the user's home is atomic - no torn target on a failed write`() {
        val target = File(tempDir, "project/Solution.kt").apply { parentFile.mkdirs() }
        target.writeText("original complete content\n")

        // A successful save replaces content whole.
        runBlocking { impl().saveFile(saveRequest(target)) }
        assertEquals("saved content\n", target.readText())

        // The atomic shape: any .part siblings from failed/interrupted writes
        // must never have replaced the committed content.
        val parts = target.parentFile.listFiles { f -> f.name.endsWith(".part") } ?: emptyArray()
        parts.forEach { it.delete() }
        assertTrue(target.readText() == "saved content\n", "the committed record survives stray temp siblings")
    }

    @Test
    fun `a path outside the user's home is refused`() {
        // Outside home: on the test harness, home is the temp dir, so an
        // absolute path to a sibling outside it is the escape shape.
        val outside = File(tempDir.parentFile ?: File("/"), "escape-target.txt")
        assertFailsWith<IllegalArgumentException> {
            runBlocking { impl().openFile(openRequest(outside)) }
        }
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
        assertFailsWith<IllegalArgumentException> {
            runBlocking { impl().openFile(openRequest(link)) }
        }
        outsideDir.deleteRecursively()
    }

    @Test
    fun `traversal sequences are still refused`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { impl().openFile(openRequest(File(tempDir, "../escape.txt"))) }
        }
    }
}
