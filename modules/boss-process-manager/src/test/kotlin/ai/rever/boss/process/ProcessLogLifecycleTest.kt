package ai.rever.boss.process

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessLogLifecycleTest {
    @Test
    fun `overlapping generations share one writer until both idempotent leases close`() {
        val root = Files.createTempDirectory("log-overlap-").toRealPath()
        val before = ProcessLogStreams.activeWriterCount
        try {
            val first = ProcessLogStreams.acquire(root, "shared")
            val second = ProcessLogStreams.acquire(root, "shared")
            assertEquals(before + 1, ProcessLogStreams.activeWriterCount)
            first.close()
            first.close()
            assertEquals(before + 1, ProcessLogStreams.activeWriterCount)
            second.close()
            second.close()
            assertEquals(before, ProcessLogStreams.activeWriterCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parent exit releases both drains while inherited descendants remain alive`() {
        val root = Files.createTempDirectory("log-lifecycle-").toRealPath()
        try {
            for (mode in listOf("quiet", "writing", "quiet")) {
                generation(root, mode)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun generation(
        root: java.nio.file.Path,
        mode: String,
    ) {
        val fixture = Files.createTempDirectory(root, "generation-")
        val before = ProcessLogStreams.activeWriterCount
        val logs = ProcessLogStreams.acquire(root.resolve("logs"), "same-id")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classes =
            File(
                InheritedLogPipeProcess::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val process =
            ProcessBuilder(
                java,
                "-cp",
                classes.path,
                InheritedLogPipeProcess::class.java.name,
                "parent",
                fixture.toString(),
                mode,
            ).start()
        try {
            val completed = logs.attach(process)
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "parent must exit independently")
            completed.get(3, TimeUnit.SECONDS)
            assertEquals(before, ProcessLogStreams.activeWriterCount)
            val descendant = ProcessHandle.of(Files.readString(fixture.resolve("pid")).toLong()).orElseThrow()
            assertTrue(descendant.isAlive, "completion must not require killing the descendant")
            assertTrue(Files.readString(root.resolve("logs/same-id/stdout.log")).contains("parent-out"))
            assertTrue(Files.readString(root.resolve("logs/same-id/stderr.log")).contains("parent-err"))
            logs.close()
            logs.close()
        } finally {
            Files.writeString(fixture.resolve("stop"), "stop")
            if (Files.exists(fixture.resolve("pid"))) {
                ProcessHandle
                    .of(Files.readString(fixture.resolve("pid")).toLong())
                    .ifPresent { it.destroyForcibly() }
            }
            process.destroyForcibly()
            logs.close()
        }
    }
}
