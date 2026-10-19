package ai.rever.boss.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ai.rever.boss.plugin.git.GitOperationResult.Error as GitError
import ai.rever.boss.plugin.git.GitOperationResult.Success as GitSuccess

private const val PUBLIC_CLONE_TIMEOUT_MILLIS = 5_000L
private const val PUBLIC_CLONE_LONG_TIMEOUT_MILLIS = 30_000L
private const val PUBLIC_CLONE_WAIT_SECONDS = 10L
private const val PUBLIC_CLONE_CONNECT_SECONDS = 20L
private const val PUBLIC_CLONE_TEST_TIMEOUT_SECONDS = 45L
private const val REMOTE_HELPER_WARM_UP_SECONDS = 30L

/**
 * Runs git's HTTP transport once per JVM, against a port nothing listens on, before any test
 * times a clone through it.
 *
 * `git clone http://...` is two processes: `git` itself, and `git-remote-http` with libcurl and
 * the TLS and compression libraries behind it, loaded on first use. On the Windows runner that
 * first use is what the two timed tests below were waiting on, and on a slow day it does not
 * fit their budget: in runs 35326476015 and 35394973890 every git process start took 1-2 s
 * instead of 0.15 s (the whole desktopTest task ran 2.5-3x its usual time), the first HTTP
 * clone of the JVM never connected within its 10 s budget, and in one of the two runs the very
 * next HTTP clone connected within 5 s, once the helper was warm. Nothing about the clone
 * lifecycle was wrong; the assertion that failed was the precondition "git has connected",
 * and the cold start of the transport was inside the window it measured.
 *
 * The connection is refused immediately, so the only cost is the process start this exists
 * to pay early. The exit code is deliberately ignored: a git that cannot run at all is what
 * the ordinary clone tests report, with a better message than this could.
 */
private val gitRemoteHelperWarmedUp: Unit by lazy {
    val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val process =
        ProcessBuilder("git", "ls-remote", "http://127.0.0.1:$closedPort/warm-up.git")
            .redirectErrorStream(true)
            .start()
    process.inputStream.use { it.readBytes() }
    if (!process.waitFor(REMOTE_HELPER_WARM_UP_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
    }
}

@Timeout(PUBLIC_CLONE_TEST_TIMEOUT_SECONDS)
class GitCloneRepositoryLifecycleTest {
    @Test
    fun `public clone timeout removes its partial destination and permits retry`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        gitRemoteHelperWarmedUp
        val target = tempDirectory.resolve("timeout-target").toFile()

        val result =
            StalledHttpServer().use { server ->
                val operation =
                    async(Dispatchers.Default) {
                        GitService.cloneRepositoryWithTimeout(
                            repositoryUrl = server.repositoryUrl,
                            targetDirectory = target.absolutePath,
                            onProgress = {},
                            timeoutMillis = PUBLIC_CLONE_TIMEOUT_MILLIS,
                        )
                    }

                assertTrue(
                    server.awaitConnection(),
                    "Git did not connect to the stalled HTTP server",
                )
                assertTrue(
                    awaitTargetCreation(target),
                    "Git did not create its partial clone destination",
                )

                operation.await()
            }

        assertTrue(result is GitError, "timeout must return a Git error: $result")
        assertTrue(result.message.contains("timed out"), "timeout must use the timeout error mapping")
        assertFalse(target.exists(), "timeout must remove the partial destination")
        assertRetrySucceeds(tempDirectory, target)
    }

    @Test
    fun `public clone cancellation removes its partial destination and permits retry`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        gitRemoteHelperWarmedUp
        val target = tempDirectory.resolve("cancellation-target").toFile()

        StalledHttpServer().use { server ->
            val operation =
                async(Dispatchers.Default) {
                    GitService.cloneRepositoryWithTimeout(
                        repositoryUrl = server.repositoryUrl,
                        targetDirectory = target.absolutePath,
                        onProgress = {},
                        timeoutMillis = PUBLIC_CLONE_LONG_TIMEOUT_MILLIS,
                    )
                }

            assertTrue(
                server.awaitConnection(),
                "Git did not connect to the stalled HTTP server",
            )
            assertTrue(
                awaitTargetCreation(target),
                "Git did not create its partial clone destination",
            )

            operation.cancel()

            assertFailsWith<CancellationException> {
                operation.await()
            }
        }

        assertFalse(target.exists(), "cancellation must remove the partial destination")
        assertRetrySucceeds(tempDirectory, target)
    }

    // Adapted from Antriksh1984's independent #659 regression coverage.
    @Test
    fun `pre-existing target is refused without deleting its contents`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        val target = tempDirectory.resolve("existing").toFile().apply { mkdirs() }
        val marker = File(target, "keep.txt").apply { writeText("user data") }
        val result = GitService.cloneRepository("https://example.invalid/repo.git", target.absolutePath) {}
        assertTrue(result is GitError)
        assertEquals("user data", marker.readText())
    }

    // Adapted from Antriksh1984's independent #659 nonzero-exit regression.
    @Test
    fun `nonexistent source returns an ordinary clone error`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        val result =
            GitService.cloneRepositoryWithTimeout(
                tempDirectory.resolve("missing").toString(),
                tempDirectory.resolve("target").toString(),
                {},
                PUBLIC_CLONE_TIMEOUT_MILLIS,
            )
        assertTrue(result is GitError)
    }

    private suspend fun assertRetrySucceeds(
        tempDirectory: Path,
        target: File,
    ) {
        val source = createLocalRepository(tempDirectory.resolve("retry-source"))

        val progress = mutableListOf<String>()
        val result =
            GitService.cloneRepository(
                repositoryUrl = source.absolutePath,
                targetDirectory = target.absolutePath,
                onProgress = progress::add,
            )

        assertEquals("Clone completed successfully", progress.last())
        assertTrue(progress.contains("Cloning repository..."))
        assertTrue(result is GitSuccess, "retry must succeed after cleanup: $result")
        assertTrue(
            File(target, "README.md").isFile,
            "the successful retry did not produce the committed file",
        )
    }

    private fun createLocalRepository(path: Path): File {
        Files.createDirectories(path)
        val repository = path.toFile()

        runGit(repository, "init", "-q")
        File(repository, "README.md").writeText("clone retry fixture\n")
        runGit(repository, "add", "README.md")
        runGit(
            repository,
            "-c",
            "user.name=BOSS Test",
            "-c",
            "user.email=boss-test@example.invalid",
            "commit",
            "-q",
            "-m",
            "Initial fixture",
        )

        return repository
    }

    private fun runGit(
        directory: File,
        vararg arguments: String,
    ) {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory)
                .redirectErrorStream(true)
                .start()

        val completed = process.waitFor(20L, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }

        assertTrue(
            completed,
            "Git fixture command timed out: ${arguments.joinToString(" ")}",
        )

        val output =
            process.inputStream
                .bufferedReader()
                .use { it.readText() }

        assertEquals(
            0,
            process.exitValue(),
            "Git fixture command failed: ${arguments.joinToString(" ")}\n$output",
        )
    }

    private fun awaitTargetCreation(target: File): Boolean {
        val deadline =
            System.nanoTime() +
                TimeUnit.SECONDS.toNanos(PUBLIC_CLONE_WAIT_SECONDS)

        while (System.nanoTime() < deadline) {
            if (target.exists()) {
                return true
            }
            Thread.sleep(25L)
        }

        return target.exists()
    }
}

private class StalledHttpServer : Closeable {
    private val server =
        ServerSocket(
            0,
            1,
            InetAddress.getByName("127.0.0.1"),
        )
    private val connectionAccepted = CountDownLatch(1)
    private val releaseConnection = CountDownLatch(1)
    private val serverThread =
        Thread(
            {
                runCatching {
                    server.accept().use { _ ->
                        connectionAccepted.countDown()
                        releaseConnection.await()
                    }
                }
            },
            "boss-git-clone-stalled-http",
        ).apply {
            isDaemon = true
            start()
        }

    val repositoryUrl: String =
        "http://127.0.0.1:${server.localPort}/ordinary.git"

    /**
     * Twenty seconds rather than the ten the other waits use: this one covers two process
     * starts on whatever the runner is doing today, and the class budget of 45 s leaves room
     * for it beside the 5 s clone timeout and the retry clone that follow.
     */
    fun awaitConnection(): Boolean =
        connectionAccepted.await(
            PUBLIC_CLONE_CONNECT_SECONDS,
            TimeUnit.SECONDS,
        )

    override fun close() {
        releaseConnection.countDown()
        runCatching { server.close() }
        runCatching {
            serverThread.join(
                TimeUnit.SECONDS.toMillis(PUBLIC_CLONE_WAIT_SECONDS),
            )
        }
    }
}
