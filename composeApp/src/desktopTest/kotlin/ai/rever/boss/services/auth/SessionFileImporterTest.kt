package ai.rever.boss.services.auth

import ai.rever.boss.services.auth.SessionFileImporter.Outcome
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The import file is acted on only while signed out, only when it is a 0600 regular file owned by
 * this process's UID that has stopped changing, and at most once: it is gone before the token
 * reaches the network. A refused file is left where it is.
 */
class SessionFileImporterTest {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
    private val dir =
        createTempDirectory("session-import").also {
            if (posix) Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }
    private val file = dir.resolve("session-import.json")
    private val uid = if (posix) Files.getAttribute(dir, "unix:uid") as Int else null

    private var signedIn = false
    private val adopted = mutableListOf<String>()
    private var fileExistedAtAdopt: Boolean? = null
    private var adoptFailure: Exception? = null

    private fun importer(
        currentUid: Int? = uid,
        currentUser: String? = System.getProperty("user.name"),
    ) = SessionFileImporter(
        path = file,
        isSignedIn = { signedIn },
        adopt = { token ->
            fileExistedAtAdopt = file.exists()
            adoptFailure?.let { throw it }
            adopted += token
        },
        currentUid = currentUid,
        currentUser = currentUser,
        pollInterval = 10.milliseconds,
    )

    // A file is read only once it is unchanged across two polls.
    private suspend fun SessionFileImporter.settle(): Outcome {
        repeat(3) { importOnce().let { if (it != Outcome.PENDING) return it } }
        return Outcome.PENDING
    }

    private fun write(
        content: String = """{"refresh_token":"rt-123"}""",
        mode: String = "rw-------",
        at: Path = file,
    ) {
        Files.deleteIfExists(at)
        at.writeText(content)
        Files.setPosixFilePermissions(at, PosixFilePermissions.fromString(mode))
    }

    private fun assumePosix() = Assumptions.assumeTrue(posix, "needs POSIX file attributes")

    @AfterTest
    fun cleanup() {
        if (posix) Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a valid file is deleted before the token is adopted`(): Unit =
        runBlocking {
            assumePosix()
            write()
            val imp = importer()
            assertEquals(Outcome.PENDING, imp.importOnce())
            assertTrue(file.exists())
            assertTrue(adopted.isEmpty())
            assertEquals(Outcome.IMPORTED, imp.importOnce())
            assertEquals(listOf("rt-123"), adopted)
            assertEquals(false, fileExistedAtAdopt)
            assertFalse(file.exists())
        }

    @Test
    fun `a file still being written is not read until it stops changing`(): Unit =
        runBlocking {
            assumePosix()
            write(content = """{"refresh_token":"rt""")
            val imp = importer()
            assertEquals(Outcome.PENDING, imp.importOnce())
            Files.write(file, """-123"}""".toByteArray(), StandardOpenOption.APPEND)
            assertEquals(Outcome.PENDING, imp.importOnce())
            assertTrue(file.exists())
            assertEquals(Outcome.IMPORTED, imp.importOnce())
            assertEquals(listOf("rt-123"), adopted)
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
            assumePosix()
            write()
            signedIn = true
            assertEquals(Outcome.SIGNED_IN, importer().settle())
            assertTrue(file.exists())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a mode other than 0600 is refused and left for the writer to fix`(): Unit =
        runBlocking {
            assumePosix()
            for (mode in listOf("rw-r--r--", "r--------", "rw-rw----", "rwx------")) {
                write(mode = mode)
                assertEquals(Outcome.REJECTED, importer().settle(), mode)
                assertTrue(file.exists(), mode)
            }
            assertTrue(adopted.isEmpty())
            // The chmod-after-create race: the same file is imported once its mode is right.
            val imp = importer()
            write(mode = "rw-r--r--")
            assertEquals(Outcome.REJECTED, imp.settle())
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            assertEquals(Outcome.IMPORTED, imp.settle())
            assertEquals(listOf("rt-123"), adopted)
        }

    @Test
    fun `a symlink is refused without touching it or its target`(): Unit =
        runBlocking {
            assumePosix()
            val target = dir.resolve("target.json")
            write(at = target)
            Files.createSymbolicLink(file, target)
            assertEquals(Outcome.REJECTED, importer().settle())
            assertTrue(target.exists())
            assertTrue(Files.isSymbolicLink(file))
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a file in a directory others can write to is refused`(): Unit =
        runBlocking {
            assumePosix()
            write()
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwx---"))
            assertEquals(Outcome.REJECTED, importer().settle())
            assertTrue(file.exists())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a directory is refused`(): Unit =
        runBlocking {
            assumePosix()
            Files.createDirectory(file)
            assertEquals(Outcome.REJECTED, importer().settle())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a file owned by another uid is refused and left in place`(): Unit =
        runBlocking {
            assumePosix()
            write()
            assertEquals(Outcome.REJECTED, importer(currentUid = uid!! + 1).settle())
            assertTrue(file.exists())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `without a process uid the owner name decides`(): Unit =
        runBlocking {
            assumePosix()
            write()
            assertEquals(Outcome.REJECTED, importer(currentUid = null, currentUser = "someone-else").settle())
            assertEquals(Outcome.REJECTED, importer(currentUid = null, currentUser = null).settle())
            assertTrue(adopted.isEmpty())
            assertEquals(Outcome.IMPORTED, importer(currentUid = null).settle())
        }

    @Test
    fun `a uid with no passwd entry still imports its own file`(): Unit =
        runBlocking {
            assumePosix()
            write()
            // What the JDK reports for user.name when the UID has no passwd entry.
            assertEquals(Outcome.IMPORTED, importer(currentUser = "?").settle())
            assertEquals(listOf("rt-123"), adopted)
        }

    @Test
    fun `a file that cannot be removed is never presented`(): Unit =
        runBlocking {
            assumePosix()
            write()
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
            Assumptions.assumeFalse(Files.isWritable(dir), "running as root, the file can always be removed")
            val imp = importer()
            assertEquals(Outcome.REJECTED, imp.settle())
            assertEquals(Outcome.REJECTED, imp.settle())
            assertTrue(file.exists())
            assertTrue(adopted.isEmpty())
            assertNull(fileExistedAtAdopt)
        }

    @Test
    fun `an oversized file is refused`(): Unit =
        runBlocking {
            assumePosix()
            write(content = """{"refresh_token":"${"x".repeat(SessionFileImporter.MAX_BYTES)}"}""")
            assertEquals(Outcome.REJECTED, importer().settle())
            assertTrue(file.exists())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `anything but a single refresh_token string is refused after deletion`(): Unit =
        runBlocking {
            assumePosix()
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
                assertEquals(Outcome.MALFORMED, importer().settle(), content)
                assertFalse(file.exists(), content)
            }
            file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            assertEquals(Outcome.MALFORMED, importer().settle(), "invalid UTF-8")
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun `a failed adoption still consumed the file`(): Unit =
        runBlocking {
            assumePosix()
            write()
            adoptFailure = IOException("invalid refresh token")
            assertEquals(Outcome.FAILED, importer().settle())
            assertEquals(false, fileExistedAtAdopt)
            assertFalse(file.exists())
        }

    @Test
    fun `polling imports while signed out, stops once signed in and restarts after a sign-out`(): Unit =
        runBlocking {
            assumePosix()
            val signedOut = MutableStateFlow(true)
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            val imp =
                SessionFileImporter(
                    path = file,
                    isSignedIn = { !signedOut.value },
                    adopt = { token ->
                        adopted += token
                        signedOut.value = false
                        if (!first.complete(Unit)) second.complete(Unit)
                    },
                    currentUid = uid,
                    pollInterval = 10.milliseconds,
                )
            val job = launch { imp.run(signedOut) }
            delay(50)
            write()
            withTimeout(5_000) { first.await() }
            delay(50)
            write(content = """{"refresh_token":"second"}""")
            delay(100)
            assertEquals(listOf("rt-123"), adopted)
            assertTrue(file.exists())
            signedOut.value = true
            withTimeout(5_000) { second.await() }
            assertEquals(listOf("rt-123", "second"), adopted)
            assertFalse(file.exists())
            job.cancel()
        }

    @Test
    fun `the hook is off unless the variable names an absolute path`() {
        assertNull(SessionFileImporter.pathFromEnvironment { null })
        assertNull(SessionFileImporter.pathFromEnvironment { "  " })
        assertNull(SessionFileImporter.pathFromEnvironment { "relative/session.json" })
        assertNull(SessionFileImporter.pathFromEnvironment { "/run/boss/a\u0000b" })
        assertEquals(file, SessionFileImporter.pathFromEnvironment { file.toString() })
    }

    @Test
    fun `the hook polls when signed out or stuck with an expired session`() {
        assertTrue(wantsSessionImport(SessionStatus.NotAuthenticated(isSignOut = false)))
        assertTrue(wantsSessionImport(SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(IOException()))))
        assertFalse(wantsSessionImport(SessionStatus.Initializing))

        val now = Clock.System.now()

        fun session(expiresAt: kotlin.time.Instant) =
            UserSession(
                accessToken = "a",
                refreshToken = "r",
                expiresIn = 3600,
                tokenType = "bearer",
                user = null,
                expiresAt = expiresAt,
            )
        assertFalse(hasLiveSession(null, now))
        assertFalse(hasLiveSession(session(now - 1.hours), now))
        assertTrue(hasLiveSession(session(now + 1.hours), now))
    }
}
