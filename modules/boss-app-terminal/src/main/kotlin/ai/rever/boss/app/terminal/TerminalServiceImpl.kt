package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of TerminalService.
 *
 * Manages terminal sessions using ProcessBuilder. Each session launches
 * a shell process with stdout/stderr pumped into a SharedFlow that clients
 * subscribe to via StreamOutput. SendInput writes to the process stdin.
 *
 * Full PTY4J integration (resize, raw mode, ANSI escape sequences) can be
 * plugged in by replacing the ProcessBuilder approach without changing the
 * gRPC interface.
 */
class TerminalServiceImpl : TerminalServiceGrpcKt.TerminalServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(TerminalServiceImpl::class.java)

    private data class TerminalSession(
        val id: String,
        val workingDirectory: String,
        val command: List<String>,
        @Volatile var cols: Int = 80,
        @Volatile var rows: Int = 24,
        val createdAt: Long = System.currentTimeMillis(),
        val outputFlow: MutableSharedFlow<TerminalOutputChunk> = MutableSharedFlow(
            extraBufferCapacity = 256,
        ),
        val process: Process,
    )

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    override suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        val sessionId = UUID.randomUUID().toString()
        val workDir = request.workingDirectory.ifBlank { System.getProperty("user.home") }
        val cmd = if (request.commandList.isNotEmpty()) {
            request.commandList
        } else {
            val shell = System.getenv("SHELL")
                ?: if (System.getProperty("os.name").lowercase().contains("win")) "cmd.exe" else "/bin/sh"
            listOf(shell)
        }

        logger.info("createSession: id={}, workdir={}, command={}", sessionId, workDir, cmd)

        return try {
            val pb = ProcessBuilder(cmd)
                .directory(java.io.File(workDir))
                .redirectErrorStream(true)

            val env = pb.environment()
            env["TERM"] = "xterm-256color"
            env["COLUMNS"] = (request.cols.takeIf { it > 0 } ?: 80).toString()
            env["LINES"] = (request.rows.takeIf { it > 0 } ?: 24).toString()
            request.environmentMap.forEach { (k, v) -> env[k] = v }

            val process = pb.start()
            val outputFlow = MutableSharedFlow<TerminalOutputChunk>(extraBufferCapacity = 256)
            val session = TerminalSession(
                id = sessionId,
                workingDirectory = workDir,
                command = cmd,
                cols = request.cols.takeIf { it > 0 } ?: 80,
                rows = request.rows.takeIf { it > 0 } ?: 24,
                outputFlow = outputFlow,
                process = process,
            )
            sessions[sessionId] = session

            // Pump stdout/stderr into the SharedFlow on a daemon thread
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val buf = CharArray(4096)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        outputFlow.tryEmit(
                            TerminalOutputChunk.newBuilder()
                                .setSessionId(sessionId)
                                .setData(ByteString.copyFromUtf8(String(buf, 0, n)))
                                .setTimestamp(System.currentTimeMillis())
                                .build()
                        )
                    }
                    // Emit exit notification
                    val exitCode = try { process.waitFor() } catch (_: Exception) { -1 }
                    outputFlow.tryEmit(
                        TerminalOutputChunk.newBuilder()
                            .setSessionId(sessionId)
                            .setData(ByteString.copyFromUtf8("\r\n[Process exited with code $exitCode]\r\n"))
                            .setTimestamp(System.currentTimeMillis())
                            .setIsExit(true)
                            .setExitCode(exitCode)
                            .build()
                    )
                } catch (_: Exception) {
                    // Process terminated or stream closed — pump ends cleanly
                }
            }.also { it.isDaemon = true }.start()

            CreateSessionResponse.newBuilder()
                .setSuccess(true)
                .setSessionId(sessionId)
                .build()
        } catch (e: Exception) {
            logger.error("Failed to create terminal session", e)
            CreateSessionResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage(e.message ?: "Failed to start process")
                .build()
        }
    }

    override suspend fun sendInput(request: SendInputRequest): Empty {
        val session = sessions[request.sessionId]
        if (session == null) {
            logger.warn("sendInput: session not found: {}", request.sessionId)
            return Empty.getDefaultInstance()
        }
        try {
            val out = session.process.outputStream
            out.write(request.data.toByteArray())
            out.flush()
        } catch (e: Exception) {
            logger.warn("sendInput error for session {}: {}", request.sessionId, e.message)
        }
        return Empty.getDefaultInstance()
    }

    override fun streamOutput(request: StreamOutputRequest): Flow<TerminalOutputChunk> = flow {
        val session = sessions[request.sessionId]
        if (session == null) {
            logger.warn("streamOutput: session not found: {}", request.sessionId)
            return@flow
        }
        session.outputFlow.collect { chunk -> emit(chunk) }
    }

    override suspend fun resize(request: ResizeRequest): Empty {
        val session = sessions[request.sessionId]
        if (session != null) {
            session.cols = request.cols
            session.rows = request.rows
            logger.debug("resize: session={}, {}x{}", request.sessionId, request.cols, request.rows)
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun closeSession(request: CloseSessionRequest): Empty {
        val session = sessions.remove(request.sessionId)
        if (session != null) {
            session.process.destroyForcibly()
            logger.info("closeSession: id={}", request.sessionId)
        } else {
            logger.warn("closeSession: not found: {}", request.sessionId)
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun listSessions(request: Empty): ListSessionsResponse {
        val infos = sessions.values.map { s ->
            TerminalSessionInfo.newBuilder()
                .setSessionId(s.id)
                .setWorkingDirectory(s.workingDirectory)
                .addAllCommand(s.command)
                .setCreatedAt(s.createdAt)
                .setIsAlive(s.process.isAlive)
                .build()
        }
        return ListSessionsResponse.newBuilder().addAllSessions(infos).build()
    }
}
