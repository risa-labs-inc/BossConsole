package ai.rever.boss.utils

import java.io.File
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversion from a code-source URL to a file.
 *
 * Written against literal URLs rather than wherever the test classes happen to
 * live, so the network-share case can be asserted at all - no CI runner loads
 * BOSS off a UNC share.
 *
 * Deliberately free of "this is what Windows returns" literals. The defect is
 * that the authority silently disappeared; the assertion is that it no longer
 * does, stated so it holds on a host that has no UNC concept and answers null
 * instead. Pinning `\nas01\...` would pass only on Windows and fail the other
 * two CI legs.
 */
class CodeSourceLocationTest {
    @Test
    fun `a real file round-trips through its own URL`() {
        val file = File.createTempFile("code-source", ".jar")
        try {
            val resolved =
                assertNotNull(
                    CodeSourceLocation.fileOf(file.toURI().toURL()),
                    "a plain file URL names a file on every platform",
                )
            assertEquals(file.canonicalFile, resolved.canonicalFile)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a path with spaces survives percent-decoding`() {
        val directory = File(System.getProperty("java.io.tmpdir"), "boss code source ${System.nanoTime()}")
        directory.mkdirs()
        val file = File(directory, "BOSS.jar")
        file.writeText("")
        try {
            val url = file.toURI().toURL()
            assertTrue(url.toString().contains("%20"), "the URL under test must actually be encoded")
            assertEquals(file.canonicalFile, CodeSourceLocation.fileOf(url)?.canonicalFile)
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test
    fun `a network-share URL never resolves to a path that dropped its server`() {
        val url = URL("file://nas01/software/BOSS/app/BOSS.jar")
        val resolved = CodeSourceLocation.fileOf(url)

        // Either the host can address a UNC path and the server is still in it, or
        // it cannot and says so. What must never happen again is the third answer:
        // a silently rebased local path like C:\software\BOSS\app\BOSS.jar, which
        // `File(url.toURI().path)` produced and which no check downstream catches.
        if (resolved != null) {
            assertTrue(
                resolved.path.contains("nas01"),
                "resolved to ${resolved.path}, which no longer names the server",
            )
        }
    }

    @Test
    fun `the dropped-authority path is not what we resolve to`() {
        val url = URL("file://nas01/software/BOSS/app/BOSS.jar")
        val droppedAuthority = File(url.toURI().path)

        assertEquals(
            "/software/BOSS/app/BOSS.jar",
            url.toURI().path,
            "if this changes, the defect this guards has changed shape",
        )
        // Compared as absolute paths, not canonical ones: canonicalising an
        // unreachable UNC path asks Windows to contact the server and can throw.
        assertTrue(
            CodeSourceLocation.fileOf(url)?.absolutePath != droppedAuthority.absolutePath,
            "resolved to the authority-less path the old expression produced",
        )
    }

    /**
     * `file://localhost/x` is the local `/x`, and `URI.path` answered it correctly.
     * The Unix provider rejects every authority, `localhost` included, so passing it
     * through unchanged turned this into null on Linux and macOS.
     */
    @Test
    fun `a localhost authority names this machine`() {
        val file = File.createTempFile("code-source", ".jar")
        try {
            val url = URL("file://localhost" + file.toURI().rawPath)
            assertEquals("localhost", url.toURI().authority, "the URL under test must carry the authority")
            assertEquals(file.canonicalFile, CodeSourceLocation.fileOf(url)?.canonicalFile)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a nested archive URL names no single file`() {
        assertNull(CodeSourceLocation.fileOf(URL("jar:file:/C:/BOSS/app/BOSS.jar!/")))
    }

    @Test
    fun `a remote URL names no file`() {
        assertNull(CodeSourceLocation.fileOf(URL("https://example.invalid/BOSS.jar")))
    }

    @Test
    fun `a missing code source is not an error`() {
        assertNull(CodeSourceLocation.fileOf(null))
    }

    @Test
    fun `the running test class resolves to something that exists`() {
        val resolved =
            assertNotNull(
                CodeSourceLocation.fileFor(CodeSourceLocationTest::class.java),
                "the test classes are loaded from a plain file",
            )
        assertTrue(resolved.exists(), "resolved to ${resolved.path}, which does not exist")
    }
}
