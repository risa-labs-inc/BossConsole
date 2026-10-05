package ai.rever.boss.services.auth

import ai.rever.boss.services.auth.SessionFileImporter.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The import file is acted on only while signed out, only when it is a 0600 regular file owned by
 * this user, and at most once: it is gone before the token reaches the network.
 */
class SessionFileImporterTest {
    private val dir = createTempDirectory("session-import")
    private val file = dir.resolve("session-import.json")
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    private var signedIn = false
    private val adopted = mutableListOf<String>()
    private var fileExistedAtAdopt: Boolean? = null
    private var adoptFailure: Exception? = null

    private fun importer(owner: String? = System.getProperty("user.name")) =
        SessionFileImporter(
            path = file,
            isSignedIn = { signedIn },
            adopt = { token ->
                fileExistedAtAdopt = file.exists()
                adoptFailure?.let { throw it }
                adopted += token
            },
            currentUser = owner,
            pollInterval = 10.milliseconds,
        )

    private fun write(
        content: String = """{"refresh_token":"rt-123"}""",
        mode: String = "rw-------",
        at: Path = file,
    ) {
        at.writeText(content)
        Files.setPosixFilePermissions(at, PosixFilePermissions.fromString(mode))
    }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a valid file is deleted before the token is adopted`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            write()
            assertEquals(Outcome.IMPORTED, importer().importOnce())
            assertEquals(listOf("rt-123"), adopted)
            assertEquals(false, fileExistedAtAdopt)
            assertFalse(file.exists())
        }

    @Test
    fun `no file is a silent no-op`(): Unit =
        runBlocking {
            assertEquals(Outcome.ABSENT, importer().importOnce())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `an existing session leaves the file alone`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            write()
            signedIn = true
            assertEquals(Outcome.SIGNED_IN, importer().importOnce())
            assertTrue(file.exists())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a mode other than 0600 is refused and removed`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            for (mode in listOf("rw-r--r--", "r--------", "rw-rw----", "rwx------")) {
                write(mode = mode)
                assertEquals(Outcome.REJECTED, importer().importOnce(), mode)
                assertFalse(file.exists(), mode)
            }
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a symlink is refused without touching its target`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            val target = dir.resolve("target.json")
            write(at = target)
            Files.createSymbolicLink(file, target)
            assertEquals(Outcome.REJECTED, importer().importOnce())
            assertTrue(target.exists())
            assertFalse(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a directory is refused`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            Files.createDirectory(file)
            assertEquals(Outcome.REJECTED, importer().importOnce())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a file owned by someone else is refused`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            write()
            assertEquals(Outcome.REJECTED, importer(owner = "someone-else").importOnce())
            write()
            assertEquals(Outcome.REJECTED, importer(owner = null).importOnce())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `anything but a single refresh_token string is refused after deletion`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            val bad =
                listOf(
                    "not json",
                    "[]",
                    """{"refresh_token":""}""",
                    """{"refresh_token":42}""",
                    """{"refresh_token":"rt","access_token":"at"}""",
                    """{"token":"rt"}""",
                )
            for (content in bad) {
                write(content = content)
                assertEquals(Outcome.MALFORMED, importer().importOnce(), content)
                assertFalse(file.exists(), content)
            }
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a failed adoption still consumed the file`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            write()
            adoptFailure = IllegalStateException("invalid refresh token")
            assertEquals(Outcome.FAILED, importer().importOnce())
            assertEquals(false, fileExistedAtAdopt)
            assertFalse(file.exists())
        }

    @Test
    fun `polling picks up a file that appears while signed out and stops once signed in`(): Unit =
        runBlocking {
            if (!posix) return@runBlocking
            val signedOut = MutableStateFlow(true)
            val done = CompletableDeferred<Unit>()
            val imp =
                SessionFileImporter(
                    path = file,
                    isSignedIn = { !signedOut.value },
                    adopt = { token ->
                        adopted += token
                        signedOut.value = false
                        done.complete(Unit)
                    },
                    pollInterval = 10.milliseconds,
                )
            val job = launch { imp.run(signedOut) }
            delay(50)
            write()
            withTimeout(5_000) { done.await() }
            delay(50)
            write(content = """{"refresh_token":"second"}""")
            delay(100)
            assertEquals(listOf("rt-123"), adopted)
            assertTrue(file.exists())
            job.cancel()
        }

    @Test
    fun `the hook is off unless the variable names an absolute path`() {
        assertNull(SessionFileImporter.pathFromEnvironment { null })
        assertNull(SessionFileImporter.pathFromEnvironment { "  " })
        assertNull(SessionFileImporter.pathFromEnvironment { "relative/session.json" })
        assertEquals(file, SessionFileImporter.pathFromEnvironment { file.toString() })
    }
}
