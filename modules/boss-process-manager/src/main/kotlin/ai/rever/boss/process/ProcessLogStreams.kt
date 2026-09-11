package ai.rever.boss.process

import ai.rever.boss.ipc.IpcAddressResolver
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Shares writers across overlapping restarts, and removes them after the last pipe has drained. */
internal class ProcessLogStreams private constructor(
    private val stdout: Lease,
    private val stderr: Lease,
) : AutoCloseable {
    fun attach(process: Process): CompletableFuture<Void> =
        CompletableFuture.allOf(startDrain(process.inputStream, stdout), startDrain(process.errorStream, stderr))

    private fun startDrain(
        input: InputStream,
        lease: Lease,
    ): CompletableFuture<Unit> {
        val drained = CompletableFuture<Unit>()
        Thread({
            try {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    var recording = true
                    var count = stream.read(buffer)
                    while (count >= 0) {
                        if (recording) {
                            try {
                                lease.append(buffer, count)
                            } catch (_: IOException) {
                                // A full or unavailable log disk must not block the child's stdout forever.
                                logger.warn("Process log writing failed; draining remaining output without recording")
                                recording = false
                            }
                        }
                        count = stream.read(buffer)
                    }
                }
            } catch (_: IOException) {
                logger.debug("Process output pipe closed")
            } finally {
                try {
                    lease.close()
                } finally {
                    drained.complete(Unit)
                }
            }
        }, "process-log-drain").apply { isDaemon = true }.start()
        return drained
    }

    override fun close() {
        stdout.close()
        stderr.close()
    }

    private class Shared(
        val directory: ProcessLogDirectory,
    ) : AutoCloseable {
        val stdout = RotatingProcessLog(directory, "stdout")
        val stderr = RotatingProcessLog(directory, "stderr")
        var references = 0

        override fun close() {
            try {
                stdout.close()
            } finally {
                try {
                    stderr.close()
                } finally {
                    directory.close()
                }
            }
        }
    }

    private class Lease(
        private val writer: RotatingProcessLog,
        private val shared: Shared,
    ) : AutoCloseable {
        private var closed = false

        @Synchronized
        fun append(
            bytes: ByteArray,
            count: Int,
        ) {
            if (!closed) writer.append(bytes, count)
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            synchronized(writers) {
                shared.references--
                if (shared.references == 0) {
                    writers.remove(shared.directory.identity, shared)
                    shared.close()
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProcessLogStreams::class.java)
        private val writers = mutableMapOf<String, Shared>()

        fun acquire(
            root: Path,
            processId: String,
        ): ProcessLogStreams {
            IpcAddressResolver.validateProcessIdentifier(processId)
            return synchronized(writers) {
                val directory = ProcessLogDirectory.open(root, processId)
                val existing = writers[directory.identity]
                val shared = existing ?: Shared(directory).also { writers[directory.identity] = it }
                if (existing != null) directory.close()
                shared.references += 2
                ProcessLogStreams(Lease(shared.stdout, shared), Lease(shared.stderr, shared))
            }
        }
    }
}
