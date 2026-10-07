package ai.rever.boss.components.plugin

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginUpdateLeaseTest {
    @Test
    fun `concurrent first acquisitions for different plugins share newly created lock directory`() {
        val directory = Files.createTempDirectory("plugin-update-directory-race").toFile()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val completed = ConcurrentHashMap.newKeySet<Int>()
        val workers =
            (0 until 8).map { index ->
                thread(name = "plugin-lease-directory-$index", isDaemon = true) {
                    ready.countDown()
                    val result =
                        runCatching {
                            check(start.await(10, TimeUnit.SECONDS)) { "Start barrier timed out" }
                            PluginUpdateLease.acquire(directory, "plugin-$index").getOrThrow().use {
                                completed += index
                            }
                        }
                    result.exceptionOrNull()?.let { failures += it }
                }
            }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not reach ready barrier")
            start.countDown()
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            assertTrue(workers.none { it.isAlive }, "Lease acquisition worker did not finish")
            assertTrue(failures.isEmpty(), "Concurrent acquisition failures: $failures")
            assertEquals((0 until 8).toSet(), completed)
            val locks = File(directory, ".plugin-update-locks").listFiles()!!.toList()
            assertEquals(8, locks.size)
            assertEquals(8, locks.map { it.name }.toSet().size)
            val prefix = File(directory, ".plugin-update-locks").canonicalPath + File.separator
            assertTrue(PluginUpdateProcessRegistry.owners().keys.none { it.startsWith(prefix) })
        } finally {
            start.countDown()
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            if (workers.none { it.isAlive }) directory.deleteRecursively()
        }
    }

    @Test
    fun `separate installers contend for the same disk lease and exceptions release it`() {
        val directory = Files.createTempDirectory("plugin-update-lease").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                PluginUpdateLease.acquire(directory, "plugin").getOrThrow().use {
                    assertTrue(PluginUpdateLease.acquire(directory, "plugin").isFailure)
                    PluginUpdateLease.acquire(directory, "other").getOrThrow().close()
                    error("Installer failed")
                }
            }
            PluginUpdateLease.acquire(directory, "plugin").getOrThrow().close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `same JVM rejection leaves the first lease locked against another process`() {
        val directory = Files.createTempDirectory("plugin-update-process-lease").toFile()
        try {
            val held = PluginUpdateLease.acquire(directory, "plugin").getOrThrow()
            try {
                assertIs<PluginUpdateLeaseBusyException>(
                    PluginUpdateLease.acquire(directory, "plugin").exceptionOrNull(),
                )
                val path = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalPath
                assertEquals(
                    3,
                    externalProbe(directory, path),
                    "Contender closed an FD and dropped the holder's OS lock",
                )
            } finally {
                held.close()
            }
            val path = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalPath
            assertEquals(0, externalProbe(directory, path), "Completed holder did not release its OS lock")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `process ownership uses bootstrap JDK values and releases only its own token`() {
        val directory = Files.createTempDirectory("plugin-update-jdk-gate").toFile()
        try {
            val held = PluginUpdateLease.acquire(directory, "plugin").getOrThrow()
            val registry = PluginUpdateProcessRegistry.owners()
            assertIs<ConcurrentHashMap<*, *>>(registry)
            val path = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalPath
            val token = assertNotNull(registry[path])
            assertEquals(Any::class.java, token.javaClass, "Gate value must not pin a plugin classloader")
            assertEquals(null, token.javaClass.classLoader)
            assertEquals(null, registry.javaClass.classLoader)
            assertTrue(PluginUpdateLease.acquire(directory.canonicalFile, "plugin").isFailure)
            assertTrue(registry[path] === token, "Busy acquisition removed the current owner's token")
            held.close()
            held.close()
            assertEquals(null, registry[path])
            PluginUpdateLease.acquire(directory, "plugin").getOrThrow().use {
                val replacement = assertNotNull(registry[path])
                held.close()
                assertTrue(registry[path] === replacement, "Previous owner released the replacement's process gate")
                assertTrue(PluginUpdateLease.acquire(directory, "plugin").isFailure)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed acquisition keeps its cause when real channel cleanup fails fatally`() {
        val directory = Files.createTempDirectory("plugin-update-cleanup-fault").toFile()
        val original = IOException("acquire")
        val closeFailure = IOException("close")
        val fatal = NoClassDefFoundError("diagnostics")
        lateinit var channel: FailingLeaseChannel
        try {
            val actual =
                assertFails {
                    PluginUpdateLease.acquire(
                        directory,
                        "plugin",
                        openChannel = { FailingLeaseChannel(original, closeFailure).also { channel = it } },
                        reportFailure = { _, _ -> throw fatal },
                    )
                }
            assertSame(fatal, actual)
            assertTrue(actual.suppressed.any { it === original })
            assertTrue(actual.suppressed.any { it === closeFailure })
            assertTrue(!channel.isOpen)
            assertIs<PluginUpdateLeaseBusyException>(
                PluginUpdateLease.acquire(directory, "plugin").exceptionOrNull(),
            )
        } finally {
            // Synthetic channel owns no OS descriptor; remove only this fixture's retained token.
            removeSyntheticFence(directory)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `repeated real lease close emits no warnings`() {
        val directory = Files.createTempDirectory("plugin-update-quiet-close").toFile()
        val reports = mutableListOf<String>()
        try {
            val lease =
                PluginUpdateLease
                    .acquire(
                        directory,
                        "plugin",
                        reportFailure = { phase, _ -> reports += phase },
                    ).getOrThrow()
            repeat(3) { lease.close() }
            assertTrue(reports.isEmpty(), "Repeated close reported $reports")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `external owner rejection unfences host so it can retry after release`() {
        val directory = Files.createTempDirectory("plugin-update-external-holder").toFile()
        try {
            PluginUpdateLease.acquire(directory, "plugin").getOrThrow().close()
            val path = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalPath
            val source = File(directory, "PluginUpdateLeaseProbe.java")
            javaClass.getResourceAsStream("/PluginUpdateLeaseProbe.java")!!.use {
                source.writeBytes(it.readBytes())
            }
            val ready = File(directory, "ready")
            val release = File(directory, "release")
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val child =
                ProcessBuilder(java, source.absolutePath, path, ready.path, release.path)
                    .redirectErrorStream(true)
                    .start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!ready.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(ready.exists(), "External holder failed to become ready")
                assertIs<PluginUpdateLeaseBusyException>(
                    PluginUpdateLease.acquire(directory, "plugin").exceptionOrNull(),
                )
                release.writeText("release")
                assertTrue(child.waitFor(15, TimeUnit.SECONDS), "External holder did not release")
                assertEquals(0, child.exitValue(), child.inputStream.bufferedReader().readText())
                PluginUpdateLease.acquire(directory, "plugin").getOrThrow().close()
            } finally {
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `ordinary and fatal native close failures keep the same fence across repeated close`() {
        for (failure in listOf(IOException("close"), OutOfMemoryError("close"))) {
            val directory = Files.createTempDirectory("plugin-update-sticky-close").toFile()
            val channel = FailingLeaseChannel(null, failure)
            val reports = mutableListOf<String>()
            try {
                val lease =
                    PluginUpdateLease
                        .acquire(
                            directory,
                            "plugin",
                            openChannel = { channel },
                            reportFailure = { phase, _ -> reports += phase },
                        ).getOrThrow()
                val owners = PluginUpdateProcessRegistry.owners()
                val prefix = File(directory, ".plugin-update-locks").canonicalPath + File.separator
                val path = owners.keys.single { it.startsWith(prefix) }
                val token = assertNotNull(owners[path])
                if (failure is Exception) {
                    assertEquals("committed", lease.use { "committed" })
                } else {
                    assertSame(failure, assertFails { lease.close() })
                }
                assertTrue(!channel.isOpen, "Java marks closed before native close throws")
                repeat(3) { lease.close() }
                assertSame(token, owners[path], "Repeated close must retain the original fence")
                assertIs<PluginUpdateLeaseBusyException>(
                    PluginUpdateLease.acquire(directory, "plugin").exceptionOrNull(),
                )
                assertEquals(1, channel.closeAttempts)
                assertEquals(if (failure is Exception) listOf("close") else emptyList(), reports)
            } finally {
                removeSyntheticFence(directory)
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `failed acquisition retains fence after ordinary or fatal native close failure`() {
        for (failure in listOf(IOException("close"), OutOfMemoryError("close"))) {
            val directory = Files.createTempDirectory("plugin-update-acquire-close").toFile()
            val original = IOException("acquire")
            val channel = FailingLeaseChannel(original, failure)
            try {
                val acquire = {
                    PluginUpdateLease.acquire(
                        directory,
                        "plugin",
                        openChannel = { channel },
                        reportFailure = { _, _ -> },
                    )
                }
                if (failure is Exception) {
                    assertSame(original, acquire().exceptionOrNull())
                } else {
                    val actual = assertFails { acquire() }
                    assertSame(failure, actual)
                    assertSame(original, actual.suppressed.single())
                }
                assertTrue(!channel.isOpen)
                assertIs<PluginUpdateLeaseBusyException>(
                    PluginUpdateLease.acquire(directory, "plugin").exceptionOrNull(),
                )
                assertEquals(1, channel.closeAttempts)
            } finally {
                removeSyntheticFence(directory)
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `release failures with confirmed channel close free the production process fence`() {
        for (failure in listOf(IOException("release"), OutOfMemoryError("release"))) {
            val directory = Files.createTempDirectory("plugin-update-release-close").toFile()
            val channel = FailingLeaseChannel(null, null, failure)
            val reports = mutableListOf<String>()
            try {
                val lease =
                    PluginUpdateLease
                        .acquire(
                            directory,
                            "plugin",
                            openChannel = { channel },
                            reportFailure = { phase, _ -> reports += phase },
                        ).getOrThrow()
                if (failure is Exception) {
                    assertEquals("committed", lease.use { "committed" })
                } else {
                    assertSame(failure, assertFails { lease.close() })
                }
                assertTrue(!channel.isOpen)
                assertEquals(1, channel.closeAttempts)
                assertEquals(if (failure is Exception) listOf("release") else emptyList(), reports)
                PluginUpdateLease.acquire(directory, "plugin").getOrThrow().close()
            } finally {
                removeSyntheticFence(directory)
                directory.deleteRecursively()
            }
        }
    }

    private fun removeSyntheticFence(directory: File) {
        val prefix = File(directory, ".plugin-update-locks").canonicalPath + File.separator
        val owners = PluginUpdateProcessRegistry.owners()
        owners.entries.filter { it.key.startsWith(prefix) }.forEach { owners.remove(it.key, it.value) }
    }

    private fun externalProbe(
        directory: File,
        path: String,
    ): Int {
        val source = File(directory, "PluginUpdateLeaseProbe.java")
        javaClass.getResourceAsStream("/PluginUpdateLeaseProbe.java")!!.use { source.writeBytes(it.readBytes()) }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val child = ProcessBuilder(java, source.absolutePath, path).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "External lock probe timed out")
            val output = child.inputStream.bufferedReader().readText()
            assertTrue(child.exitValue() in listOf(0, 3), "External lock probe failed: $output")
            return child.exitValue()
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }
}

/** Immutable per-acquisition fault fixture exercises production cleanup without global hooks. */
private class FailingLeaseChannel(
    private val acquisitionFailure: IOException?,
    private val closeFailure: Throwable?,
    private val lockReleaseFailure: Throwable? = null,
) : FileChannel() {
    var closeAttempts = 0
        private set

    override fun tryLock(
        position: Long,
        size: Long,
        shared: Boolean,
    ): FileLock {
        acquisitionFailure?.let { throw it }
        return object : FileLock(this, position, size, shared) {
            private var valid = true

            override fun isValid(): Boolean = valid && this@FailingLeaseChannel.isOpen

            override fun release() {
                lockReleaseFailure?.let { throw it }
                valid = false
            }
        }
    }

    override fun implCloseChannel() {
        closeAttempts++
        closeFailure?.let { throw it }
    }

    override fun read(dst: ByteBuffer): Int = error("unused")

    override fun read(
        dsts: Array<out ByteBuffer>,
        offset: Int,
        length: Int,
    ): Long = error("unused")

    override fun read(
        dst: ByteBuffer,
        position: Long,
    ): Int = error("unused")

    override fun write(src: ByteBuffer): Int = error("unused")

    override fun write(
        srcs: Array<out ByteBuffer>,
        offset: Int,
        length: Int,
    ): Long = error("unused")

    override fun write(
        src: ByteBuffer,
        position: Long,
    ): Int = error("unused")

    override fun position(): Long = error("unused")

    override fun position(newPosition: Long): FileChannel = error("unused")

    override fun size(): Long = error("unused")

    override fun truncate(size: Long): FileChannel = error("unused")

    override fun force(metaData: Boolean): Unit = error("unused")

    override fun transferTo(
        position: Long,
        count: Long,
        target: WritableByteChannel,
    ): Long = error("unused")

    override fun transferFrom(
        src: ReadableByteChannel,
        position: Long,
        count: Long,
    ): Long = error("unused")

    override fun map(
        mode: MapMode,
        position: Long,
        size: Long,
    ): MappedByteBuffer = error("unused")

    override fun lock(
        position: Long,
        size: Long,
        shared: Boolean,
    ): FileLock = error("unused")
}
