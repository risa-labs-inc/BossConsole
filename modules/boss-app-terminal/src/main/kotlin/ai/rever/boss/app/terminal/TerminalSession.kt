package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import com.google.protobuf.ByteString
import com.google.protobuf.Duration
import com.google.rpc.RetryInfo
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.google.protobuf.Any as ProtoAny

@Suppress("LongParameterList") // Owner identity stays immutable alongside the process and terminal dimensions.
internal class TerminalSession(
    val id: String,
    val workingDirectory: String,
    val command: List<String>,
    val process: Process,
    @Volatile var cols: Int,
    @Volatile var rows: Int,
    val ownerInstance: String = "",
    private val inputWriteTimeoutMillis: Long = 5_000,
    private val inputQueueTimeoutMillis: Long = INPUT_QUEUE_TIMEOUT_MILLIS,
) {
    init {
        require(inputQueueTimeoutMillis > 0) { "inputQueueTimeoutMillis must be positive" }
    }

    val createdAt = System.currentTimeMillis()
    val output = TerminalOutputBuffer()

    // Queued writers suspend on the mutex instead of parking a Dispatchers.IO
    // thread, so a stalled pipe cannot pin the pool, and a cancelled caller
    // abandons its queued write instead of sending it after the caller gave up.
    private val inputMutex = Mutex()

    // Set once a stdin write fails or stalls; later sends fail fast on a closed pipe.
    @Volatile private var inputClosed = false

    // Set when a caller delivered EOF through [closeStdin]. Kept apart from [inputClosed] so a
    // send after a deliberate close says so, rather than reading like a broken pipe.
    @Volatile private var inputClosedByCaller = false

    @Volatile var active = true
        private set

    fun startPump(onStopped: () -> Unit) {
        Thread({
            val input = process.inputStream
            try {
                run {
                    val buffer = ByteArray(4096)
                    // Never block on EOF: a reparented descendant may still own the pipe's write end.
                    // Only this thread reads, so reading at most available bytes cannot wait for more.
                    while (process.isAlive) {
                        val available = input.available()
                        if (available == 0) {
                            Thread.sleep(10)
                        } else {
                            val count = input.read(buffer, 0, minOf(available, buffer.size))
                            if (count > 0) output.append(chunk(ByteString.copyFrom(buffer, 0, count)))
                        }
                    }
                    // Drain a bounded snapshot after process death, even if a descendant keeps writing.
                    var remaining = minOf(input.available(), 65_536)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size))
                        if (count <= 0) break
                        output.append(chunk(ByteString.copyFrom(buffer, 0, count)))
                        remaining -= count
                    }
                }
            } catch (_: IOException) {
                // Windows reports an already closed output pipe as an IOException from available().
                // Pipe closure is not permission to kill a still-running process; retain its slot until exit.
                output.append(chunk("\r\n[Terminal output pipe closed]\r\n"))
            } finally {
                // Capacity includes the pump and process lifetime, including cancellation cleanup.
                try {
                    val code = process.onExit().join().exitValue()
                    // Keep the read end open until exit: closing on a read fault can SIGPIPE a live child.
                    try {
                        input.close()
                    } catch (_: IOException) {
                        // The pipe may already be broken; process exit still releases admission.
                    }
                    output.append(
                        chunk("\r\n[Process exited with code $code]\r\n")
                            .toBuilder()
                            .setIsExit(true)
                            .setExitCode(code)
                            .build(),
                    )
                } finally {
                    active = false
                    onStopped()
                }
            }
        }, "terminal-output-$id").apply { isDaemon = true }.start()
    }

    suspend fun send(bytes: ByteArray) {
        // Queue on the mutex up to the bound. Failing instantly turns every concurrent
        // caller into a retrying spin exactly when the pipe is busiest; callers that
        // outwait the bound are shed with a retry-after instead.
        val ticket = Any()
        if (!acquireInputLock(ticket)) {
            throw inputBusy(inputQueueTimeoutMillis)
        }
        try {
            currentCoroutineContext().ensureActive()
            requireUsableInput()
            writeInput(bytes)
        } finally {
            inputMutex.unlock(ticket)
        }
    }

    private suspend fun acquireInputLock(ticket: Any): Boolean {
        val acquired =
            withTimeoutOrNull(inputQueueTimeoutMillis) {
                inputMutex.lock(ticket)
                true
            } == true
        if (!acquired && inputMutex.holdsLock(ticket)) {
            // The grant can still land on a waiter the timeout already shed; hand
            // it back instead of leaving the queue locked behind a dead caller.
            inputMutex.unlock(ticket)
        }
        return acquired
    }

    private fun requireUsableInput() {
        val refusal =
            when {
                !active || !process.isAlive -> "Terminal has exited"
                inputClosedByCaller -> "Terminal input has been closed"
                inputClosed -> "Terminal input pipe is closed"
                else -> null
            }
        if (refusal != null) {
            throw Status.FAILED_PRECONDITION.withDescription(refusal).asRuntimeException()
        }
    }

    private fun writeInput(bytes: ByteArray) {
        val output = process.outputStream
        val succeeded = AtomicBoolean(false)
        // A dead child, or a reparented descendant that keeps the pipe's read end without
        // reading, leaves this write blocked forever once the pipe fills. Race the write
        // against a deadline so the RPC cannot hang on stdin, and close the pipe when it loses.
        val writer =
            Thread {
                try {
                    output.write(bytes)
                    output.flush()
                    succeeded.set(true)
                } catch (_: IOException) {
                    // A dead reader reports a broken pipe; the input path is closed for good.
                }
            }
        writer.name = "terminal-input-$id"
        writer.isDaemon = true
        writer.start()
        val finished =
            try {
                writer.join(inputWriteTimeoutMillis)
                !writer.isAlive
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!finished) {
            discardInput(output)
            throw Status.ABORTED.withDescription("Terminal input write timed out").asRuntimeException()
        }
        if (!succeeded.get()) {
            discardInput(output)
            throw Status.FAILED_PRECONDITION.withDescription("Terminal input pipe is closed").asRuntimeException()
        }
    }

    private fun discardInput(output: OutputStream) {
        // ProcessPipeOutputStream.close() can wait on the monitor held by a stalled write.
        // Never do that on the RPC thread (or while holding up subsequent input requests).
        // At most one cleanup worker is created per session: inputClosed fences later sends.
        // These daemon workers may live until the child/reader exits; a timeout is not
        // permission to terminate the user's process, nor proof that no bytes were delivered.
        inputClosed = true
        Thread({
            try {
                output.close()
            } catch (_: IOException) {
                // The pipe may already be broken; the input path is closed either way.
            }
        }, "terminal-input-close-$id").apply { isDaemon = true }.start()
    }

    /**
     * Deliver EOF on stdin without touching the process otherwise - the one thing [terminate]
     * cannot be asked to do, since it forcibly kills the child rather than letting it notice
     * input has ended. A stdin-consuming one-shot command (`sort`, `grep`, `cat` with no args, a
     * pipeline ending in one of those) blocks on read() forever without this: nothing else in
     * this class closes the write end while the child is healthy, so the process can never reach
     * the exit chunk [CreateSessionRequest]'s `run_command` schema advertises.
     *
     * Takes the same input mutex as [send], so a close never races a write on the same fd, and
     * waits for it on the same bounded queue: a caller behind a stalled writer is answered
     * RESOURCE_EXHAUSTED "Terminal input is busy" with a retry-after, exactly as a queued send is.
     *
     * Idempotent and safe after exit: a second call, or one after the process has died or a
     * failed write already closed the pipe, finds nothing left to close rather than throwing.
     */
    suspend fun closeStdin() {
        val ticket = Any()
        if (!acquireInputLock(ticket)) {
            throw inputBusy(inputQueueTimeoutMillis)
        }
        try {
            if (inputClosed) return
            inputClosedByCaller = true
            inputClosed = true
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // Already broken (process gone, pipe torn) - EOF is the state we wanted anyway.
            }
        } finally {
            inputMutex.unlock(ticket)
        }
    }

    fun terminate() {
        // This terminates the currently observable tree, not an OS sandbox for detached descendants.
        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    private fun chunk(text: String) = chunk(ByteString.copyFromUtf8(text))

    private fun chunk(data: ByteString) =
        TerminalOutputChunk
            .newBuilder()
            .setSessionId(id)
            .setData(data)
            .setTimestamp(System.currentTimeMillis())
            .build()

    companion object {
        private const val INPUT_QUEUE_TIMEOUT_MILLIS = 5_000L

        private fun validateLaunchInput(request: CreateSessionRequest) {
            // Validate before ProcessBuilder: native environment validation differs between OS/JDK implementations.
            require(request.commandList.firstOrNull()?.isNotEmpty() != false)
            require(request.commandList.all { '\u0000' !in it })
            require(
                request.environmentMap.all { (name, value) ->
                    name.isNotEmpty() && '=' !in name && '\u0000' !in name && '\u0000' !in value
                },
            )
        }

        fun launch(
            request: CreateSessionRequest,
            ownerInstance: String,
        ): TerminalSession {
            validateLaunchInput(request)
            val directory = request.workingDirectory.ifBlank { System.getProperty("user.home") }
            val workingDirectory = File(directory)
            // A missing path fails fast as INVALID_ARGUMENT instead of an opaque spawn failure.
            // Resolving reachability can still stall the caller on network paths (dead UNC share,
            // stale NFS mount), so createSession keeps this probe and the spawn off the service
            // lock and inside the requesting caller only.
            if (!workingDirectory.isDirectory) {
                throw Status.INVALID_ARGUMENT
                    .withDescription("Terminal working directory does not exist or is not a directory")
                    .asRuntimeException()
            }
            val command =
                request.commandList.ifEmpty {
                    val defaultShell = if (System.getProperty("os.name").startsWith("Windows")) "cmd.exe" else "/bin/sh"
                    listOf(System.getenv("SHELL") ?: defaultShell)
                }
            val cols = request.cols.takeIf { it > 0 } ?: 80
            val rows = request.rows.takeIf { it > 0 } ?: 24
            if (cols > 1000 || rows > 1000) {
                throw Status.INVALID_ARGUMENT
                    .withDescription("Terminal dimensions exceed the limit")
                    .asRuntimeException()
            }
            val builder = ProcessBuilder(command).directory(workingDirectory).redirectErrorStream(true)
            builder.environment().apply {
                put("TERM", "xterm-256color")
                put("COLUMNS", cols.toString())
                put("LINES", rows.toString())
                putAll(request.environmentMap)
                // Strip after overrides so a launch request cannot reintroduce the parent's authority.
                IpcEnvironment.removeCredentials(this)
            }
            IpcCall.requireOwner(ownerInstance)
            return TerminalSession(
                UUID.randomUUID().toString(),
                directory,
                command,
                builder.start(),
                cols,
                rows,
                ownerInstance,
            )
        }
    }
}

/**
 * RESOURCE_EXHAUSTED "Terminal input is busy", with a retry-after of [retryAfterMillis]: the answer
 * for a caller that outwaited the input queue. File scope because [TerminalSession] is at detekt's
 * `TooManyFunctions` ceiling.
 */
private fun inputBusy(retryAfterMillis: Long): StatusRuntimeException =
    StatusProto.toStatusRuntimeException(
        com.google.rpc.Status
            .newBuilder()
            .setCode(Status.Code.RESOURCE_EXHAUSTED.value())
            .setMessage("Terminal input is busy")
            .addDetails(
                ProtoAny.pack(
                    RetryInfo
                        .newBuilder()
                        .setRetryDelay(
                            Duration
                                .newBuilder()
                                .setSeconds(retryAfterMillis / 1_000)
                                .setNanos(((retryAfterMillis % 1_000) * 1_000_000).toInt()),
                        ).build(),
                ),
            ).build(),
    )
