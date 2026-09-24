package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import com.google.protobuf.ByteString
import io.grpc.Status
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

@Suppress("LongParameterList") // Owner identity stays immutable alongside the process and terminal dimensions.
internal class TerminalSession(
    val id: String,
    val workingDirectory: String,
    val command: List<String>,
    val process: Process,
    @Volatile var cols: Int,
    @Volatile var rows: Int,
    val ownerInstance: String = "",
) {
    val createdAt = System.currentTimeMillis()
    val output = TerminalOutputBuffer()
    private val inputLock = ReentrantLock()

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
                var exitCode = -1
                try {
                    val code = process.onExit().join().exitValue()
                    exitCode = code
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
                } catch (_: Throwable) {
                    // onExit().join() can throw if the process was destroyed before we
                    // reached the finally, or if a hook aborted. Either way the buffer
                    // MUST be marked finished so every subscriber's stream() can return -
                    // see issue #1314. close() is a no-op if a real exit chunk already
                    // landed, so a thrown onExit() leaves the buffer in the same state
                    // a clean exit would.
                } finally {
                    // Always mark the buffer finished. close() is a no-op when the real
                    // exit chunk already landed, so this is the only path that matters
                    // when onExit().join() threw out - otherwise stream() waits forever.
                    output.close(exitCode = exitCode)
                    active = false
                    onStopped()
                }
            }
        }, "terminal-output-$id").apply { isDaemon = true }.start()
    }

    fun send(bytes: ByteArray) {
        if (!inputLock.tryLock()) {
            throw Status.RESOURCE_EXHAUSTED.withDescription("Terminal input is busy").asRuntimeException()
        }
        try {
            if (!active || !process.isAlive) {
                throw Status.FAILED_PRECONDITION.withDescription("Terminal has exited").asRuntimeException()
            }
            writeInput(bytes)
        } finally {
            inputLock.unlock()
        }
    }

    private fun writeInput(bytes: ByteArray) {
        try {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        } catch (_: IOException) {
            throw Status.FAILED_PRECONDITION.withDescription("Terminal input pipe is closed").asRuntimeException()
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
            val builder = ProcessBuilder(command).directory(File(directory)).redirectErrorStream(true)
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
