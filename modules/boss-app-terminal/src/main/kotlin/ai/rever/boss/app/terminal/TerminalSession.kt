package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.proto.services.CreateSessionRequest
import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import com.google.protobuf.ByteString
import io.grpc.Status
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

internal class TerminalSession(
    val ownerInstance: String,
    val workingDirectory: String,
    val command: List<String>,
    val process: Process,
    @Volatile var cols: Int,
    @Volatile var rows: Int,
) {
    val id = UUID.randomUUID().toString()
    val createdAt = System.currentTimeMillis()
    val output = TerminalOutputBuffer()
    private val inputLock = ReentrantLock()

    @Volatile var active = true
        private set

    fun startPump(onStopped: () -> Unit) {
        Thread({
            try {
                InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    var count = reader.read(buffer)
                    while (count >= 0) {
                        output.append(chunk(String(buffer, 0, count)))
                        count = reader.read(buffer)
                    }
                }
            } catch (_: IOException) {
                process.destroyForcibly()
            } finally {
                // Capacity includes the pump and process lifetime, including cancellation cleanup.
                try {
                    val code = process.onExit().join().exitValue()
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

    fun send(bytes: ByteArray) {
        if (!inputLock.tryLock()) {
            throw Status.RESOURCE_EXHAUSTED.withDescription("Terminal input is busy").asRuntimeException()
        }
        try {
            if (!active || !process.isAlive) {
                throw Status.FAILED_PRECONDITION.withDescription("Terminal has exited").asRuntimeException()
            }
            process.outputStream.write(bytes)
            process.outputStream.flush()
        } finally {
            inputLock.unlock()
        }
    }

    fun terminate() {
        // This terminates the currently observable tree, not an OS sandbox for detached descendants.
        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    private fun chunk(text: String) =
        TerminalOutputChunk
            .newBuilder()
            .setSessionId(id)
            .setData(ByteString.copyFromUtf8(text))
            .setTimestamp(System.currentTimeMillis())
            .build()

    companion object {
        fun launch(
            request: CreateSessionRequest,
            ownerInstance: String,
        ): TerminalSession {
            val directory = request.workingDirectory.ifBlank { System.getProperty("user.home") }
            val command =
                request.commandList.ifEmpty {
                    val defaultShell = if (System.getProperty("os.name").startsWith("Windows")) "cmd.exe" else "/bin/sh"
                    listOf(System.getenv("SHELL") ?: defaultShell)
                }
            val cols = request.cols.takeIf { it > 0 } ?: 80
            val rows = request.rows.takeIf { it > 0 } ?: 24
            require(cols <= 1000 && rows <= 1000) { "Terminal dimensions exceed the limit" }
            val builder = ProcessBuilder(command).directory(File(directory)).redirectErrorStream(true)
            builder.environment().apply {
                put("TERM", "xterm-256color")
                put("COLUMNS", cols.toString())
                put("LINES", rows.toString())
                putAll(request.environmentMap)
                // Strip after overrides so a launch request cannot reintroduce the parent's authority.
                IpcEnvironment.removeCredentials(this)
            }
            return TerminalSession(ownerInstance, directory, command, builder.start(), cols, rows)
        }
    }
}
