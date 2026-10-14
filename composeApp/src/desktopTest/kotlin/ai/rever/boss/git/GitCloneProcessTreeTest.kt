package ai.rever.boss.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TREE_READY_PREFIX = "TREE_READY:"
private const val TREE_READY_TIMEOUT_SECONDS = 10L
private const val TREE_PROCESS_TIMEOUT_MILLIS = 5_000L
private const val TREE_CANCELLATION_TIMEOUT_MILLIS = 30_000L
private const val TREE_TEST_TIMEOUT_SECONDS = 60L

@Timeout(TREE_TEST_TIMEOUT_SECONDS)
class GitCloneProcessTreeTest {
    @Test
    fun `deadline still terminates the real tree after an advisory callback failure`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        val process = startProcessTree(tempDirectory)
        val helperPid = AtomicLong(-1L)
        val cleanupCalls = AtomicInteger()

        try {
            val failures = AtomicInteger()
            assertFailsWith<TimeoutCancellationException> {
                runCloneProcess(
                    process = process,
                    timeoutMillis = TREE_PROCESS_TIMEOUT_MILLIS,
                    onOutputLine = { line ->
                        captureHelperPid(line, helperPid)
                        if (line.startsWith(TREE_READY_PREFIX)) {
                            throw IOException("forced progress callback failure")
                        }
                    },
                    onCancellation = {
                        cleanupCalls.incrementAndGet()
                    },
                    outputLifecycle = CloneOutputLifecycle(onFailure = { failures.incrementAndGet() }),
                )
            }

            assertEquals(1, failures.get())
            assertEquals(1, cleanupCalls.get())
            assertTreeStopped(process, helperPid)
        } finally {
            killTestProcessTree(process, helperPid.get())
        }
    }

    @Test
    fun `timeout terminates the real parent and helper process`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        val process = startProcessTree(tempDirectory)
        val helperPid = AtomicLong(-1L)
        val cleanupCalls = AtomicInteger()

        try {
            assertFailsWith<TimeoutCancellationException> {
                runCloneProcess(
                    process = process,
                    timeoutMillis = TREE_PROCESS_TIMEOUT_MILLIS,
                    onOutputLine = { line ->
                        captureHelperPid(line, helperPid)
                    },
                    onCancellation = {
                        cleanupCalls.incrementAndGet()
                    },
                )
            }

            assertEquals(1, cleanupCalls.get())
            assertTreeStopped(process, helperPid)
        } finally {
            killTestProcessTree(process, helperPid.get())
        }
    }

    @Test
    fun `caller cancellation terminates the real parent and helper process`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        val process = startProcessTree(tempDirectory)
        val helperPid = AtomicLong(-1L)
        val treeReady = CountDownLatch(1)
        val cleanupCalls = AtomicInteger()

        try {
            val operation =
                async(Dispatchers.Default) {
                    runCloneProcess(
                        process = process,
                        timeoutMillis = TREE_CANCELLATION_TIMEOUT_MILLIS,
                        onOutputLine = { line ->
                            captureHelperPid(line, helperPid)
                            if (line.startsWith(TREE_READY_PREFIX)) {
                                treeReady.countDown()
                            }
                        },
                        onCancellation = {
                            cleanupCalls.incrementAndGet()
                        },
                    )
                }

            assertTrue(
                treeReady.await(TREE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the real process tree did not become ready",
            )

            operation.cancel()

            assertFailsWith<CancellationException> {
                operation.await()
            }

            assertEquals(1, cleanupCalls.get())
            assertTreeStopped(process, helperPid)
        } finally {
            killTestProcessTree(process, helperPid.get())
        }
    }

    private fun startProcessTree(tempDirectory: Path): Process {
        val classpath = currentTestClasspath()
        val launcherPrefix =
            "-cp\n${quoteLauncherArgument(classpath)}\n" +
                "${GitCloneProcessTreeProbe::class.java.name}\n"

        val helperArguments =
            Files.createTempFile(
                tempDirectory,
                "git-clone-helper-",
                ".args",
            )
        Files.writeString(
            helperArguments,
            "${launcherPrefix}helper\n",
        )

        val parentArguments =
            Files.createTempFile(
                tempDirectory,
                "git-clone-parent-",
                ".args",
            )
        Files.writeString(
            parentArguments,
            "${launcherPrefix}parent\n" +
                "${quoteLauncherArgument(helperArguments.toAbsolutePath().toString())}\n",
        )

        val java =
            File(
                System.getProperty("java.home"),
                "bin/java",
            ).absolutePath

        return ProcessBuilder(
            java,
            "@${parentArguments.toAbsolutePath()}",
        ).redirectErrorStream(true)
            .start()
    }

    private fun currentTestClasspath(): String {
        val urls =
            generateSequence(javaClass.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .flatMap { it.getURLs().asSequence() }
                .map { File(it.toURI()).path }
                .toList()

        return (urls + System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct()
            .joinToString(File.pathSeparator)
    }

    private fun quoteLauncherArgument(value: String): String {
        val escaped =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun captureHelperPid(
        line: String,
        helperPid: AtomicLong,
    ) {
        if (line.startsWith(TREE_READY_PREFIX)) {
            helperPid.compareAndSet(
                -1L,
                line.removePrefix(TREE_READY_PREFIX).trim().toLong(),
            )
        }
    }

    private fun assertTreeStopped(
        process: Process,
        helperPid: AtomicLong,
    ) {
        assertFalse(process.isAlive, "the real parent process must be terminated")

        val capturedPid = helperPid.get()
        assertTrue(capturedPid > 0L, "the helper process PID was not captured")

        val helper = ProcessHandle.of(capturedPid).orElse(null)
        assertTrue(
            helper == null || !helper.isAlive,
            "the real helper process must not survive cleanup",
        )
    }

    private fun killTestProcessTree(
        process: Process,
        helperPid: Long,
    ) {
        val descendants =
            runCatching {
                process
                    .toHandle()
                    .descendants()
                    .use { it.toList() }
            }.getOrDefault(emptyList())

        descendants.forEach { descendant ->
            runCatching {
                if (descendant.isAlive) {
                    descendant.destroyForcibly()
                }
            }
        }

        if (helperPid > 0L) {
            ProcessHandle.of(helperPid).ifPresent { helper ->
                runCatching {
                    if (helper.isAlive) {
                        helper.destroyForcibly()
                    }
                }
            }
        }

        runCatching {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
        runCatching {
            process.waitFor(5L, TimeUnit.SECONDS)
        }
    }
}

object GitCloneProcessTreeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "helper" -> stayAlive()
            "parent" -> runParent(requireNotNull(args.getOrNull(1)))
            else -> error("Unknown process-tree probe mode")
        }
    }

    private fun runParent(helperArgumentsFile: String) {
        val java =
            File(
                System.getProperty("java.home"),
                "bin/java",
            ).absolutePath
        val helper =
            ProcessBuilder(
                java,
                "@$helperArgumentsFile",
            ).inheritIO()
                .start()

        try {
            println("$TREE_READY_PREFIX${helper.pid()}")
            System.out.flush()
            stayAlive()
        } finally {
            if (helper.isAlive) {
                helper.destroyForcibly()
            }
        }
    }

    private fun stayAlive(): Nothing {
        while (true) {
            Thread.sleep(TimeUnit.MINUTES.toMillis(1L))
        }
    }
}
