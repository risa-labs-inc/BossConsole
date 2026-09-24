package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import io.grpc.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.Semaphore

/** ProcessBuilder terminals with bounded active work, replay history, and completed-session retention. */
@Suppress("TooManyFunctions") // One function per RPC the gRPC service base class declares.
class TerminalServiceImpl(
    activeLimit: Int = 16,
    private val historyLimit: Int = 64,
) : TerminalServiceGrpcKt.TerminalServiceCoroutineImplBase(),
    AutoCloseable {
    private val logger = LoggerFactory.getLogger(TerminalServiceImpl::class.java)
    private val activeSlots = Semaphore(activeLimit)
    private val streamSlots = Semaphore(32)
    private val lock = Any()
    private val sessions = linkedMapOf<String, TerminalSession>()
    private var closed = false

    init {
        require(activeLimit > 0 && historyLimit >= activeLimit)
    }

    override suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        val ownerInstance = IpcCall.current().instanceId
        var session: TerminalSession? = null
        var admitted = false
        var pumping = false
        var delivered = false
        try {
            val response =
                withContext(Dispatchers.IO) {
                    if (request.serializedSize > 131_072) {
                        throw Status.INVALID_ARGUMENT
                            .withDescription("Terminal launch request exceeds 128 KiB")
                            .asRuntimeException()
                    }
                    currentCoroutineContext().ensureActive()
                    // Admission stays atomic with shutdown, but the spawn runs outside the
                    // service-global lock: the caller-controlled working directory can take the
                    // OS a long time to resolve (dead UNC share, stale NFS mount), and holding
                    // [lock] across it would stall every owner's RPC behind one bad launch.
                    synchronized(lock) {
                        reserveSlot()
                        admitted = true
                    }
                    val launched = TerminalSession.launch(request, ownerInstance)
                    session = launched
                    synchronized(lock) {
                        // A shutdown that raced the spawn must not overlook this launch either:
                        // reject the registration so the cleanup below reaps the orphaned process.
                        if (closed) {
                            throw Status.UNAVAILABLE
                                .withDescription("Terminal service is closed")
                                .asRuntimeException()
                        }
                        retain(launched)
                        launched.startPump { activeSlots.release() }
                        pumping = true
                    }
                    logger.info("Created terminal session: {}", launched.id)
                    CreateSessionResponse
                        .newBuilder()
                        .setSuccess(true)
                        .setSessionId(launched.id)
                        .build()
                }
            // The return dispatch can discard a withContext result on cancellation. Ownership stays
            // here until that dispatch succeeds, so a discarded response also terminates its process.
            delivered = true
            return response
        } catch (_: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT
                .withDescription("Invalid terminal command or environment")
                .asRuntimeException()
        } catch (failure: IOException) {
            logger.warn("Terminal launch failed: {}", failure.javaClass.simpleName)
            return CreateSessionResponse
                .newBuilder()
                .setSuccess(false)
                .setErrorMessage("Failed to start terminal process")
                .build()
        } finally {
            if (!delivered && admitted) {
                session?.terminate()
            }
            if (!delivered && admitted && !pumping) {
                session?.process?.onExit()?.join()
                synchronized(lock) { session?.id?.let(sessions::remove) }
                activeSlots.release()
            }
        }
    }

    /** Called while holding [lock], so shutdown and new launches cannot cross. */
    private fun reserveSlot() {
        if (closed) throw Status.UNAVAILABLE.withDescription("Terminal service is closed").asRuntimeException()
        if (!activeSlots.tryAcquire()) {
            throw Status.RESOURCE_EXHAUSTED.withDescription("Too many active terminals").asRuntimeException()
        }
    }

    private fun retain(session: TerminalSession) {
        val iterator = sessions.entries.iterator()
        while (sessions.size >= historyLimit && iterator.hasNext()) {
            if (!iterator.next().value.active) iterator.remove()
        }
        sessions[session.id] = session
    }

    override fun close() {
        val active =
            synchronized(lock) {
                closed = true
                sessions.values.filter { it.active }
            }
        active.forEach { it.terminate() }
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(5)
        active.forEach {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            if (!it.process.waitFor(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                logger.warn("Terminal process has not exited after shutdown deadline")
            }
        }
    }

    override suspend fun sendInput(request: SendInputRequest): Empty =
        withContext(Dispatchers.IO) {
            if (request.data.size() > 65_536) {
                throw Status.INVALID_ARGUMENT.withDescription("Terminal input exceeds 64 KiB").asRuntimeException()
            }
            session(request.sessionId).send(request.data.toByteArray())
            Empty.getDefaultInstance()
        }

    override suspend fun closeInput(request: CloseInputRequest): Empty =
        withContext(Dispatchers.IO) {
            session(request.sessionId).closeStdin()
            Empty.getDefaultInstance()
        }

    override fun streamOutput(request: StreamOutputRequest): Flow<TerminalOutputChunk> =
        flow {
            if (!streamSlots.tryAcquire()) {
                throw Status.RESOURCE_EXHAUSTED
                    .withDescription(
                        "Too many terminal output readers",
                    ).asRuntimeException()
            }
            try {
                val owned = session(request.sessionId)
                owned.output.stream().collect { chunk ->
                    IpcCall.requireOwner(owned.ownerInstance)
                    emit(chunk)
                }
            } finally {
                streamSlots.release()
            }
        }

    override suspend fun resize(request: ResizeRequest): Empty {
        if (request.cols !in 1..1000 || request.rows !in 1..1000) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid terminal dimensions").asRuntimeException()
        }
        session(request.sessionId).apply {
            cols = request.cols
            rows = request.rows
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun closeSession(request: CloseSessionRequest): Empty {
        val session = session(request.sessionId)
        if (session.active) session.terminate() else synchronized(lock) { sessions.remove(session.id) }
        return Empty.getDefaultInstance()
    }

    override suspend fun listSessions(request: Empty): ListSessionsResponse {
        val caller = IpcCall.current()
        val snapshot =
            synchronized(lock) {
                sessions.values.filter {
                    caller.authority == ProcessAuthority.HOST || it.ownerInstance == caller.instanceId
                }
            }
        return ListSessionsResponse
            .newBuilder()
            .addAllSessions(
                snapshot.map { session ->
                    TerminalSessionInfo
                        .newBuilder()
                        .setSessionId(session.id)
                        .setWorkingDirectory(session.workingDirectory)
                        .addAllCommand(session.command)
                        .setCreatedAt(session.createdAt)
                        .setIsAlive(session.process.isAlive)
                        .build()
                },
            ).build()
    }

    private fun session(id: String): TerminalSession {
        IpcCall.current()
        val found =
            synchronized(lock) { sessions[id] }
                ?: throw Status.NOT_FOUND.withDescription("Terminal session not found").asRuntimeException()
        IpcCall.requireOwner(found.ownerInstance)
        return found
    }
}
