package ai.rever.boss.process

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessLogLimitsTest {
    private val root = Files.createTempDirectory("process-log-limits-").toRealPath()

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `invalid process identifiers are rejected before log directories exist`() {
        val logs = root.resolve("logs")
        val spawner = ProcessSpawner("tcp://127.0.0.1:1", logs.toFile())
        for (id in listOf("..", ".", "../escaped", "bad/name", "x".repeat(201))) {
            assertFailsWith<IllegalArgumentException> {
                spawner.spawn(ProcessConfig(id, ProcessType.SERVICE, id, "unused"))
            }
        }
        assertFalse(Files.exists(logs))
        assertFalse(Files.exists(root.resolve("escaped")))
    }

    @Test
    fun `real child output is drained and rotated at the configured production limit`() {
        val logs = ProcessLogStreams.acquire(root, "child")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes =
            File(
                LogTestProcess::class.java.protectionDomain.codeSource.location
                    .toURI(),
            ).absolutePath
        val process = ProcessBuilder(java, "-cp", classes, LogTestProcess::class.java.name).start()
        try {
            val drained = logs.attach(process)
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            drained.get(10, TimeUnit.SECONDS)
            assertEquals(0, process.exitValue())
            val directory = root.resolve("child")
            val files = Files.list(directory).use { it.toList() }
            val stdout = files.filter { it.fileName.toString().startsWith("stdout") }
            assertEquals(5, stdout.size)
            assertTrue(stdout.all { Files.size(it) <= 10L * 1024 * 1024 })
            assertTrue(Files.readString(directory.resolve("stdout.log")).endsWith("END"))
            assertEquals("error-stream", Files.readString(directory.resolve("stderr.log")))
            assertFalse(files.any { it.fileName.toString().endsWith(".part") })
        } finally {
            process.destroyForcibly()
            logs.close()
        }
    }

    @Test
    fun `rotation never appends through a preexisting log link`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val target = Files.writeString(root.resolve("sentinel"), "untouched")
        val safeDirectory = ProcessLogDirectory.open(root.resolve("logs"), "child")
        val directory = safeDirectory.path
        Files.createSymbolicLink(directory.resolve("stdout.log"), target)
        safeDirectory.use { safe ->
            RotatingProcessLog(safe, "stdout", maximumBytes = 32, fileCount = 3).use { log ->
                val bytes = "data".repeat(40).toByteArray()
                log.append(bytes, bytes.size)
            }
        }
        assertEquals("untouched", Files.readString(target))
        assertEquals(3L, Files.list(directory).use { it.count() })
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(directory.resolve("stdout.log")),
        )
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
    }

    @Test
    fun `linked process directory is refused without modifying its target`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val logs = Files.createDirectory(root.resolve("logs"))
        val target = Files.createDirectory(root.resolve("sentinel"))
        Files.createSymbolicLink(logs.resolve("child"), target)
        assertFailsWith<IOException> { ProcessLogStreams.acquire(logs, "child") }
        assertEquals(0L, Files.list(target).use { it.count() })
    }

    @Test
    fun `replacing the process directory cannot redirect later rotation`() {
        val logs = Files.createDirectory(root.resolve("logs"))
        val outside = Files.createDirectory(root.resolve("outside"))
        val sentinel = Files.writeString(outside.resolve("stdout.log"), "untouched")
        ProcessLogDirectory.open(logs, "child").use { directory ->
            val moved = logs.resolve("moved")
            Files.move(directory.path, moved)
            Files.createSymbolicLink(directory.path, outside)
            RotatingProcessLog(directory, "stdout", maximumBytes = 32, fileCount = 3).use { writer ->
                val bytes = "rotation".repeat(40).toByteArray()
                writer.append(bytes, bytes.size)
            }
            assertEquals("untouched", Files.readString(sentinel))
            assertEquals(1L, Files.list(outside).use { it.count() })
            assertEquals(3L, Files.list(moved).use { it.count() })
            assertTrue(Files.size(moved.resolve("stdout.log")) <= 32)
        }
    }

    @Test
    fun `linked log roots and ancestors are refused before outside writes`() {
        val outside = Files.createDirectory(root.resolve("outside"))
        val linked = Files.createSymbolicLink(root.resolve("linked"), outside)
        assertFailsWith<IOException> { ProcessLogDirectory.open(linked, "child") }
        assertFailsWith<IOException> { ProcessLogDirectory.open(linked.resolve("logs"), "child") }
        assertEquals(0L, Files.list(outside).use { it.count() })
    }

    @Test
    fun `macOS inherited ACLs cannot make new process logs readable by everyone`() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val inherited = "everyone allow read,execute,file_inherit,directory_inherit"
        val chmod = ProcessBuilder("/bin/chmod", "+a", inherited, root.toString()).start()
        assertEquals(0, chmod.waitFor())
        ProcessLogDirectory.open(root, "child").use { directory ->
            directory.create("stdout.log").use { it.write(java.nio.ByteBuffer.wrap("private".toByteArray())) }
            val listing =
                ProcessBuilder(
                    "/bin/ls",
                    "-led",
                    root.toString(),
                    directory.path.toString(),
                    directory.path.resolve("stdout.log").toString(),
                ).start()
            val output = listing.inputStream.bufferedReader().readText()
            assertEquals(0, listing.waitFor())
            assertFalse(Regex("(?m)^\\s*\\d+:").containsMatchIn(output), output)
            assertEquals("private", Files.readString(directory.path.resolve("stdout.log")))
        }
    }

    @Test
    fun `oversized old logs are not retained beyond the new limit`() {
        val safeDirectory = ProcessLogDirectory.open(root, "old")
        val directory = safeDirectory.path
        Files.write(directory.resolve("stdout.log"), ByteArray(1024))
        safeDirectory.use { safe ->
            RotatingProcessLog(safe, "stdout", maximumBytes = 32, fileCount = 3).use { log ->
                log.append("ok".toByteArray(), 2)
            }
        }
        val names = Files.list(directory).use { it.map(Path::getFileName).map(Path::toString).toList() }
        assertEquals(listOf("stdout.log"), names)
        assertEquals("ok", Files.readString(directory.resolve("stdout.log")))
    }
}
