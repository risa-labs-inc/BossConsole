package ai.rever.boss.mcp

import ai.rever.boss.plugin.logging.LogSanitizer
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Append-only persistent journal and real-time telemetry buffer for MCP tool executions.
 *
 * Saves records to `~/.boss/mcp-calls.jsonl` with size-based rotation (active file + up to 5 backups).
 * Arguments and error snippets are sanitized via [LogSanitizer] before persistence to prevent
 * credential leaks.
 *
 * Each persisted record also carries a [McpOperationRecord.hash] chained to the record before it, so
 * the durable file is tamper-evident and not merely append-only - see [McpLedgerChain] and
 * [verifyChain]. The chain is assigned on the writer thread at append time, so a record that never
 * reaches disk simply never enters the chain and cannot read as a broken link: [recentOperations]
 * holds the unhashed draft until the write lands and then swaps in the chained copy, while
 * [pendingWriteIds] and [droppedWrites] keep the queued and the lost distinguishable from the
 * persisted.
 *
 * Scope note: Tool discovery, RBAC permissions, and kill-switch blocks are enforced upstream
 * in tool resolution; unpermitted or unregistered tool calls are blocked before reaching
 * policy checks and the operation ledger.
 */
// Record, async writer, rotation, and read/verify form one audit boundary; the constructor
// mirrors the persisted-file knobs plus two test seams.
@Suppress("TooManyFunctions", "LongParameterList")
class McpOperationLedger(
    private val ledgerFile: File? = null,
    private val maxFileSizeBytes: Long = 10L * 1024 * 1024, // 10 MB
    private val maxBackupIndex: Int = 5,
    private val ringBufferCapacity: Int = 100,
    private val maxPendingWrites: Int = 8192,
    /**
     * Test seam: invoked once on the writer thread before it starts draining, so a test can
     * hold the writer while callers pile records into [writeQueue]. A constructor parameter
     * rather than a mutable property so it cannot be swapped in while the writer runs.
     */
    internal val writeGate: () -> Unit = {},
    /**
     * Test seam: invoked in [appendStaged] just before the bytes hit the file, so a test can
     * fail a batch deterministically and prove the on-disk chain stays contiguous.
     */
    internal val writeFailureProbe: (File) -> Unit = {},
) {
    /** The actual optional persistence destination, for operator-facing inspection. */
    val persistencePath: String? get() = ledgerFile?.absolutePath

    private val logger = BossLogger.forComponent("McpOperationLedger")

    /**
     * Serializes the queue offer against the telemetry updates, so write-queue order always
     * equals [recentOperations] order under concurrent callers. Nothing under this lock
     * touches the filesystem: hashing moved to the writer thread, so record() never blocks
     * a caller on disk at all.
     */
    private val writeLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = McpLedgerStore(ledgerFile)

    /**
     * Work waiting for the writer thread. Bounded on purpose: a flood of invokes must drop
     * pending audit writes (counted in [droppedWrites] and logged, rate-limited) rather than
     * queue unbounded work or serialize the invoking coroutine behind the filesystem.
     */
    private val writeQueue = ArrayBlockingQueue<LedgerWork>(maxPendingWrites)

    private val _droppedWrites = MutableStateFlow(0L)

    /**
     * Audit records that never reached disk: refused by a full [writeQueue], or staged in a
     * batch whose append failed. Public because the activity dialog is where an operator
     * finds out the journal lost entries.
     */
    val droppedWrites: StateFlow<Long> = _droppedWrites.asStateFlow()

    /** Pending records discarded because [writeQueue] was full or their batch failed to append. */
    internal val droppedWriteCount: Long
        get() = _droppedWrites.value

    /**
     * Ids of records handed to the writer but not yet confirmed on disk. A record in
     * [recentOperations] with `hash == null` is either here (still queued) or lost
     * (already counted in [droppedWrites]) - the distinction the activity dialog renders.
     */
    private val _pendingWriteIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingWriteIds: StateFlow<Set<String>> = _pendingWriteIds.asStateFlow()

    /** Rate-limit state for the queue-full warning: a flood must not flood the log. */
    private val lastDropWarningAtMs = AtomicLong(Long.MIN_VALUE)

    @Volatile
    private var writerThread: Thread? = null

    /** The [writeGate] runs once per ledger, not once per writer thread start. */
    private val writeGateConsumed = AtomicBoolean(false)

    /**
     * The hash the next appended record chains to, owned by the writer thread. `null` means
     * "recover from the disk tail": the first batch of the process, and every batch after a
     * failed append, re-reads the newest record actually on disk via
     * [McpLedgerStore.lastChainHead]. That is what makes contiguity hold by construction -
     * a staged record whose write fails can never become the missing parent of a later one.
     */
    private var writerChainHead: String? = null

    private val _recentOperations = MutableStateFlow<List<McpOperationRecord>>(emptyList())
    val recentOperations: StateFlow<List<McpOperationRecord>> = _recentOperations.asStateFlow()

    private val _totalCalls = MutableStateFlow(0L)
    val totalCalls: StateFlow<Long> = _totalCalls.asStateFlow()

    private val _totalErrors = MutableStateFlow(0L)
    val totalErrors: StateFlow<Long> = _totalErrors.asStateFlow()

    /**
     * Record a tool execution, rejection, or timeout.
     * Never throws - I/O failures are logged without disrupting tool return.
     *
     * Returns the pending draft (`hash == null`): persistence is asynchronous and the chain
     * link is assigned on the writer thread, so no hash exists to return yet. The chained
     * copy replaces the draft in [recentOperations] once the record is on disk; until then
     * its id sits in [pendingWriteIds].
     */
    @Suppress("LongParameterList") // One complete audit record, matching the persisted schema.
    fun record(
        toolName: String,
        providerId: String,
        policyApplied: McpPolicyAction,
        approvalDisposition: McpApprovalDisposition,
        durationMs: Long,
        isError: Boolean,
        rawArgs: Map<String, Any?>,
        errorSnippet: String? = null,
        // False for a governance event (YOLO mode switched on or off): it belongs in the
        // hash-chained ledger an audit reads, but it is not a tool call and must not inflate the
        // call and error counters the activity log summarises.
        countsAsCall: Boolean = true,
        secretRefs: List<String> = emptyList(),
    ): McpOperationRecord {
        val sanitized = sanitizeArguments(rawArgs)
        // Bound regex work before sanitizing. Omit oversized input entirely so cutting through
        // a quoted credential cannot turn its prefix into apparently harmless plaintext.
        val sanitizedErrorSnippet =
            errorSnippet?.let {
                if (it.length > 8192) {
                    "[OMITTED: error too large]"
                } else {
                    McpArgumentSanitizer.sanitizeMessage(it).take(4096)
                }
            }
        val draft =
            McpOperationRecord(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                toolName = toolName,
                providerId = providerId,
                policyApplied = policyApplied,
                approvalDisposition = approvalDisposition,
                durationMs = durationMs,
                isError = isError,
                sanitizedArgs = sanitized,
                errorSnippet = sanitizedErrorSnippet,
                secretRefs = secretRefs,
            )

        // Under one lock so queue order always equals ring-buffer order. What does NOT
        // happen here is hashing: the chain is assigned on the writer thread at append
        // time, so the returned draft carries no hash and a write that never lands can
        // never become a missing link in the on-disk chain. The pending id goes in BEFORE
        // the offer - the writer can only see the record through the queue, so it can
        // never mark an id persisted before it was marked pending.
        return synchronized(writeLock) {
            // The draft enters the ring buffer before the queue offer so the writer can
            // never persist a record whose draft is not yet visible - markPersisted swaps
            // by id, and a swap that ran before the draft landed would leave the unhashed
            // draft behind as a phantom queued row.
            _recentOperations.update { current ->
                (listOf(draft) + current).take(ringBufferCapacity)
            }

            if (ledgerFile != null) {
                ensureWriter()
                _pendingWriteIds.update { it + draft.id }
                if (!writeQueue.offer(LedgerWork.PendingRecord(draft))) {
                    _pendingWriteIds.update { it - draft.id }
                    noteQueueDrop()
                }
            }
            if (countsAsCall) {
                _totalCalls.update { it + 1 }
                if (isError) {
                    _totalErrors.update { it + 1 }
                }
            }

            draft
        }
    }

    /**
     * The ledger files read back, oldest first: the highest-numbered rotated backup through to the
     * active file, which is the order they were written in.
     */
    internal fun ledgerFilesOldestFirst(): List<File> = store.existingFilesOldestFirst()

    /** Every record on disk in write order. Throws [McpLedgerReadException] on an unreadable file. */
    internal fun readEntries(): List<McpLedgerEntry> = store.readEntries()

    /** Walks the hash chain across the active file and its rotations. See [McpLedgerVerification]. */
    internal fun verifyChain(): McpLedgerVerification = store.verify()

    /** Rotated backups absent while an older one is present, so history has a hole in it. */
    internal fun coverageGaps(): List<String> = store.coverageGaps()

    /**
     * Block until every record queued so far has been written to [ledgerFile]. A test seam:
     * persistence is asynchronous, so tests asserting on file contents must wait for the
     * writer rather than race it. Returns false on timeout.
     */
    internal fun awaitIdle(timeoutMs: Long = 5_000): Boolean {
        if (ledgerFile == null) return true
        // Start the writer even when nothing has been recorded yet: a flush must drain,
        // not burn its timeout waiting for a take() nobody will serve.
        ensureWriter()
        val flush = LedgerWork.FlushMarker()
        return try {
            writeQueue.offer(flush, timeoutMs, TimeUnit.MILLISECONDS) &&
                flush.done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            false
        }
    }

    /**
     * [awaitIdle] for process-shutdown paths: drains the queue with a bound and warns when
     * the writer cannot keep up, rather than letting the JVM exit with queued audit records
     * still in memory. Called before `BossLogger.shutdown()` so the timeout warning still
     * has a log to land in.
     */
    fun flush(timeoutMs: Long = 2_000): Boolean =
        awaitIdle(timeoutMs).also { drained ->
            if (!drained) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "MCP operation ledger did not drain in time - queued records may be lost",
                    mapOf("timeoutMs" to timeoutMs),
                )
            }
        }

    /**
     * One work item for the writer thread. Sealed so a third kind added later is a compile
     * error in [writeBatch]'s `when`, not a record silently skipped by an `is` check.
     */
    private sealed interface LedgerWork {
        /** A sanitized record awaiting its chain hash, which is assigned at append time. */
        class PendingRecord(
            val draft: McpOperationRecord,
        ) : LedgerWork

        /** Marker queued behind pending records so [awaitIdle] can tell when they are written. */
        class FlushMarker : LedgerWork {
            val done = CountDownLatch(1)
        }
    }

    /**
     * A record refused by a full [writeQueue]. Counted so the operator can see the loss, and
     * warned about - rate-limited, because the flood that filled the queue would otherwise
     * produce a warning per record.
     */
    private fun noteQueueDrop() {
        val total = _droppedWrites.updateAndGet { it + 1 }
        val now = System.currentTimeMillis()
        val last = lastDropWarningAtMs.get()
        if (now - last >= DROP_WARNING_INTERVAL_MS && lastDropWarningAtMs.compareAndSet(last, now)) {
            logger.warn(
                LogCategory.SYSTEM,
                "MCP operation ledger queue is full - audit records are being dropped",
                mapOf("droppedTotal" to total, "queueCapacity" to maxPendingWrites),
            )
        }
    }

    /** Start the writer thread if it is not already running. Daemon: parked on the queue. */
    private fun ensureWriter() {
        if (writerThread?.isAlive == true) return
        synchronized(this) {
            if (writerThread?.isAlive == true) return
            writerThread =
                thread(isDaemon = true, name = "mcp-ledger-writer") {
                    writeLoop()
                }
        }
    }

    /**
     * Drain [writeQueue] in batches so a burst becomes one encode pass and one append rather
     * than one syscall sequence per record. Runs alone, so the file needs no lock.
     */
    private fun writeLoop() {
        if (writeGateConsumed.compareAndSet(false, true)) {
            writeGate()
        }
        // A restarted writer inherits no head: recover it from the disk tail so it cannot
        // chain to a hash whose record the previous writer may never have landed.
        writerChainHead = null
        val batch = ArrayList<LedgerWork>(MAX_WRITE_BATCH)
        while (true) {
            val first =
                try {
                    writeQueue.take()
                } catch (interrupted: InterruptedException) {
                    return
                }
            batch.add(first)
            writeQueue.drainTo(batch, MAX_WRITE_BATCH - 1)
            writeBatch(batch)
            batch.clear()
        }
    }

    // Audit failure must not change the already-completed tool result; the batch loop nests
    // because rotation is checked per record.
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    private fun writeBatch(batch: List<LedgerWork>) {
        val file = ledgerFile ?: return releaseFlushes(batch)
        val pendingChunk = ByteArrayOutputStream()
        val staged = ArrayList<McpOperationRecord>(batch.size)
        try {
            file.parentFile?.mkdirs()
            // One stat per batch: the tracked length stands in for the per-record
            // exists()+length() checks the old synchronous writer did, so a batch that
            // crosses the size cap still rotates at the same record boundary it would have.
            var currentLength = if (file.exists()) file.length() else 0L
            for (item in batch) {
                when (item) {
                    is LedgerWork.FlushMarker -> {
                        Unit
                    }

                    is LedgerWork.PendingRecord -> {
                        // The hash link is computed here, at append time, chained to the tail
                        // that is actually on disk - never on the caller, ahead of the queue.
                        // A record whose write then fails simply never enters the chain, so a
                        // drop is lost coverage (counted in droppedWrites) rather than a
                        // LINK_BROKEN verdict that reads like tampering and stops verification.
                        val parent = writerChainHead ?: store.lastChainHead().also { writerChainHead = it }
                        val chained =
                            item.draft.copy(
                                parentHash = parent,
                                hash = McpLedgerChain.linkHash(parent, item.draft),
                            )
                        writerChainHead = chained.hash
                        val line = (json.encodeToString(chained) + "\n").toByteArray(Charsets.UTF_8)
                        if (currentLength >= maxFileSizeBytes) {
                            appendStaged(file, pendingChunk, staged)
                            rotateIfNeeded(file)
                            // Re-stat rather than reset to 0: a failed rename leaves the
                            // oversized file in place, and the next record must retry
                            // rotation instead of growing it past the cap all batch.
                            currentLength = if (file.exists()) file.length() else 0L
                        }
                        pendingChunk.write(line)
                        staged += chained
                        currentLength += line.size
                    }
                }
            }
            appendStaged(file, pendingChunk, staged)
        } catch (t: Exception) {
            // Whatever never reached disk - staged, mid-encode, or not yet processed -
            // forget the in-memory head so the next batch re-chains from the real disk
            // tail, and count the loss.
            writerChainHead = null
            val lost = noteBatchLost(batch)
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to append records to MCP operation ledger",
                mapOf(
                    "path" to file.path,
                    "recordsLost" to lost,
                    "error" to (t.message ?: t::class.simpleName),
                ),
            )
        } finally {
            releaseFlushes(batch)
        }
    }

    /**
     * One append for up to a whole batch of records. Correct under the single-writer
     * contract; a second [McpOperationLedger] pointed at the same path (the CLI constructs
     * its own instances) could interleave a torn chunk, where per-line writes would have
     * interleaved whole lines instead - neither is supported, but the chunk makes the
     * assumption visible.
     */
    private fun appendStaged(
        file: File,
        pendingChunk: ByteArrayOutputStream,
        staged: MutableList<McpOperationRecord>,
    ) {
        if (pendingChunk.size() == 0) return
        createOrRestrictToOwner(file)
        writeFailureProbe(file)
        file.appendBytes(pendingChunk.toByteArray())
        pendingChunk.reset()
        markPersisted(staged)
        staged.clear()
    }

    /**
     * Swap each written draft in [recentOperations] for the chained copy now on disk, so the
     * ring buffer mirrors exactly what was persisted rather than pre-hash drafts. Clearing
     * the ids out of [pendingWriteIds] is what flips a dialog row from queued to persisted.
     */
    private fun markPersisted(staged: List<McpOperationRecord>) {
        if (staged.isEmpty()) return
        val byId = staged.associateBy { it.id }
        _recentOperations.update { current -> current.map { byId[it.id] ?: it } }
        _pendingWriteIds.update { it - byId.keys }
    }

    /**
     * Batch records whose append failed - staged, mid-encode, or never reached by the loop:
     * anything still in [pendingWriteIds] is lost by definition, because items already
     * persisted were removed by [markPersisted]. They leave the pending set and join the
     * dropped count, so a lost audit entry is a visible number, not a silent gap.
     */
    private fun noteBatchLost(batch: List<LedgerWork>): Int {
        val batchIds = batch.filterIsInstance<LedgerWork.PendingRecord>().mapTo(mutableSetOf()) { it.draft.id }
        val lost = _pendingWriteIds.value intersect batchIds
        if (lost.isEmpty()) return 0
        _pendingWriteIds.update { it - lost }
        _droppedWrites.update { it + lost.size }
        return lost.size
    }

    private fun releaseFlushes(batch: List<LedgerWork>) {
        batch.filterIsInstance<LedgerWork.FlushMarker>().forEach { it.done.countDown() }
    }

    /**
     * Creates a new ledger with owner-only permissions, or repairs a legacy ledger's mode.
     *
     * The persisted rows carry sanitized-but-still-private operator data (file paths,
     * URLs, commands passed to operator agents), so the file must never be readable by
     * other local accounts. A plain `appendText` create inherits the process umask,
     * which is typically 0644 - world-readable - on Linux. Creating the file via
     * `Files.createFile` with an explicit rw------- mode attribute makes the ledger
     * owner-only from its first byte, with no transient window between creation and a
     * later chmod.
     *
     * Existing ledgers are tightened before every append so an older 0644 file cannot retain that
     * mode forever or carry it into a rotated backup. On filesystems without a POSIX attribute
     * view (notably Windows), profile-directory ACLs remain the protection. Permission repair is
     * best-effort and logged because a hardening failure must never cost the audit record itself.
     */
    private fun createOrRestrictToOwner(file: File) {
        val path = file.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) return
        val ownerOnly = PosixFilePermissions.fromString("rw-------")
        runCatching {
            if (file.exists()) {
                Files.setPosixFilePermissions(path, ownerOnly)
            } else {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerOnly))
            }
        }.onFailure { failure ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not restrict MCP operation ledger to its owner",
                mapOf("path" to file.path, "error" to (failure.message ?: failure::class.simpleName)),
            )
        }
    }

    // Rotation walks numbered backups; called only from the writer thread, which is the
    // single owner of the file.
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < maxFileSizeBytes) return

        try {
            val parent = file.parentFile ?: return
            // Shift older backups: .4 -> .5, .3 -> .4, etc.
            for (i in maxBackupIndex - 1 downTo 1) {
                val src = File(parent, "${file.name}.$i")
                val dst = File(parent, "${file.name}.${i + 1}")
                if (src.exists()) {
                    if (dst.exists()) dst.delete()
                    val renamed = src.renameTo(dst)
                    if (!renamed) {
                        logger.debug(
                            LogCategory.SYSTEM,
                            "Could not rename rotated ledger backup file",
                            mapOf("src" to src.name, "dst" to dst.name),
                        )
                    }
                }
            }

            // Move active file to .1
            val backup1 = File(parent, "${file.name}.1")
            if (backup1.exists()) backup1.delete()
            val renamed = file.renameTo(backup1)
            if (!renamed) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not rename active ledger to .1 backup",
                    mapOf("file" to file.name),
                )
            }

            logger.info(
                LogCategory.SYSTEM,
                "Rotated MCP operation ledger file",
                mapOf("path" to file.path),
            )
        } catch (t: Exception) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to rotate MCP operation ledger file",
                mapOf("error" to (t.message ?: t::class.simpleName)),
            )
        }
    }

    /**
     * Sanitizes map arguments using [McpArgumentSanitizer].
     * Avoids blind length-based string masking so that legitimate arguments
     * like long file paths, URLs, and shell commands are preserved for auditing.
     */
    private fun sanitizeArguments(rawArgs: Map<String, Any?>) = McpArgumentSanitizer.sanitize(rawArgs)

    private companion object {
        /** Records encoded and appended per writer pass, so bursts coalesce into one write. */
        const val MAX_WRITE_BATCH = 256

        /** Minimum gap between queue-full warnings; drops in between are counted, not logged. */
        const val DROP_WARNING_INTERVAL_MS = 30_000L
    }
}

/**
 * The hash-chain primitives behind [McpOperationRecord.hash]: a record's integrity value is a
 * SHA-256 over its own canonical form chained to its predecessor's, so editing, reordering,
 * inserting or dropping a record from inside retained history invalidates the hash of every record
 * after it. Truncating the newest tail cannot be detected without an external signed checkpoint.
 *
 * This is tamper *evidence*, not tamper *proof*. There is no key and no signature, so anyone who
 * can write the ledger can recompute the chain. What it catches is the realistic set of quiet
 * edits - a record changed in place, a middle call removed, two records swapped - without the
 * writer also rewriting everything that follows. A chain rewritten end to end or shortened at its
 * tail is indistinguishable from valid retained history, so this is a local audit aid and not a
 * notary.
 */
internal object McpLedgerChain {
    /** The parent a chain starts from, used when there is no predecessor to continue from. */
    val GENESIS_HASH: String = "0".repeat(64)

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * The value a record stores: its own canonical form chained to [parentHash]. The parent is
     * always fixed-length hex, so the separator is for readability rather than to disambiguate.
     *
     * [McpOperationRecord.hash] and [McpOperationRecord.parentHash] are excluded from the canonical
     * form, so this recomputes from a record read back off disk exactly as the writer computed it.
     */
    fun linkHash(
        parentHash: String,
        record: McpOperationRecord,
    ): String = sha256Hex("$parentHash:${record.canonicalFormForHashing()}")

    /** Lowercase hex SHA-256 of [text] as UTF-8, matching `MessageDigest` and `sha256sum`. */
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0xF])
        }
        return out.toString()
    }
}

/** One record as it exists on disk, with the file and 1-based line it was read from. */
internal data class McpLedgerEntry(
    val file: File,
    val lineNumber: Int,
    val record: McpOperationRecord,
)

/** Thrown when a ledger file exists but cannot be read as a sequence of records. */
internal class McpLedgerReadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Why a record did not agree with the chain around it. */
internal enum class McpLedgerBreakReason {
    /** The record's contents no longer hash to the value it stores. */
    RECORD_ALTERED,

    /** The record's contents are intact, but it chains to a different predecessor than the one before it. */
    LINK_BROKEN,
}

/** The first record that did not agree with the chain around it. */
internal data class McpLedgerBreak(
    val reason: McpLedgerBreakReason,
    val fileName: String,
    val lineNumber: Int,
    /** 1-based position of the record across every file, oldest first. */
    val recordIndex: Int,
    val recordId: String,
    val timestamp: Long,
    /**
     * What the chain requires: the hash recomputed from the record's own contents for
     * [McpLedgerBreakReason.RECORD_ALTERED], or the predecessor's hash for
     * [McpLedgerBreakReason.LINK_BROKEN].
     */
    val expectedHash: String,
    /** What the record says instead: its stored hash, or the parent it declares. */
    val foundHash: String,
)

/**
 * The outcome of walking the chain.
 *
 * [unverifiableRecords] counts records with no hash to check, which means they were written before
 * integrity tracking existed. A legacy prefix followed by hashed records is normal on an upgraded
 * install; a ledger containing only legacy records remains unverifiable until a new record anchors
 * the chain. A record that carries a hash is always checkable, even when the record before it has
 * been rotated away, because it stores the parent it chained to.
 */
internal data class McpLedgerVerification(
    val files: List<String>,
    val totalRecords: Int,
    val chainedRecords: Int,
    val unverifiableRecords: Int,
    val unverifiableSuffixRecords: Int,
    val oldestVerifiableFile: String?,
    val oldestVerifiableLine: Int?,
    val firstBreak: McpLedgerBreak?,
    val coverageGaps: List<String>,
) {
    /**
     * `intact`, `broken`, `incomplete`, or `unverifiable`. Never `intact` unless every record
     * carrying a hash was checked and agreed, so a partly missing or unreadable ledger cannot read
     * as healthy.
     */
    val verdict: String
        get() =
            when {
                firstBreak != null -> "broken"
                coverageGaps.isNotEmpty() -> "incomplete"
                totalRecords == 0 || chainedRecords == 0 || unverifiableSuffixRecords > 0 -> "unverifiable"
                else -> "intact"
            }
}

/**
 * Reads the durable ledger back and walks its hash chain.
 *
 * The write path in [McpOperationLedger] is best-effort by design. This is the read path, and it is
 * the opposite: a file that exists but cannot be read, or a line that is not a record, throws
 * rather than being skipped. A reader that quietly drops what it cannot parse is a reader that
 * cannot tell an operator the audit trail has a hole in it, which is the one thing it exists to do.
 *
 * Rotation is described by position: position 0 is the active file, position n is its `.<n>` backup,
 * and a higher n is older. Rotation always leaves a contiguous prefix (`.1` newest through `.k`
 * oldest), so a hole in that prefix means records that were once on disk are gone.
 */
@Suppress(
    "LoopWithTooManyJumpStatements",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
) // File layout, strict decoding, and verification form one audit boundary.
internal class McpLedgerStore(
    private val activeFile: File?,
) {
    private companion object {
        /**
         * How many rotated backups to look for. Deliberately higher than the ledger's own default
         * of 5: the reader does not know how the file it is handed was configured, and a bound
         * below the writer's would stop reading history at the wrong file without saying so.
         */
        const val BACKUP_PROBE_LIMIT = 32
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileAt(position: Int): File? {
        val active = activeFile ?: return null
        return if (position == 0) active else File(active.parentFile, "${active.name}.$position")
    }

    /**
     * Whether a file was written at [position]. Used to tell a chain that crosses a rotation from
     * one whose predecessor has been rolled off the end or deleted.
     */
    private fun existsAt(position: Int): Boolean = fileAt(position)?.let { it.exists() && it.isFile } == true

    /** Oldest first: the highest-numbered backup present, down to the active file. */
    // Keeping the pipeline visible is clearer than hiding its file predicate in a helper.
    @Suppress("MaxLineLength")
    fun existingFilesOldestFirst(): List<File> = (BACKUP_PROBE_LIMIT downTo 0).mapNotNull { fileAt(it) }.filter { it.exists() && it.isFile }

    /** Backups absent from a prefix that otherwise reaches an older one. */
    fun coverageGaps(): List<String> {
        val present = (1..BACKUP_PROBE_LIMIT).filter { existsAt(it) }
        val oldest = present.lastOrNull() ?: return emptyList()
        val missingBackups = (1..oldest).filterNot { present.contains(it) }.mapNotNull { fileAt(it)?.name }
        val missingActive = if (existsAt(0)) emptyList() else listOfNotNull(fileAt(0)?.name)
        return missingActive + missingBackups
    }

    private fun positionOf(file: File): Int? {
        val active = activeFile ?: return null
        if (file == active) return 0
        val prefix = "${active.name}."
        if (!file.name.startsWith(prefix)) return null
        return file.name.removePrefix(prefix).toIntOrNull()
    }

    /** Every record on disk, in write order. */
    fun readEntries(): List<McpLedgerEntry> {
        val entries = mutableListOf<McpLedgerEntry>()
        for (file in existingFilesOldestFirst()) {
            val lines =
                try {
                    file.readLines()
                } catch (t: Exception) {
                    throw McpLedgerReadException("Cannot read ledger file ${file.absolutePath}: ${t.message}", t)
                }
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                entries += McpLedgerEntry(file, index + 1, decode(file, index + 1, line))
            }
        }
        return entries
    }

    @Suppress("TooGenericExceptionCaught") // Every decode failure means the same thing: not a record.
    private fun decode(file: File, lineNumber: Int, line: String): McpOperationRecord =
        try {
            json.decodeFromString<McpOperationRecord>(line)
        } catch (t: Exception) {
            throw McpLedgerReadException(
                "Malformed record in ${file.absolutePath} at line $lineNumber: ${t.message}",
                t,
            )
        }

    /**
     * The hash the next record written should chain to: the `hash` of the newest record on disk, or
     * [McpLedgerChain.GENESIS_HASH] when there is none. A newest record whose own hash is `null`
     * also yields genesis, which is the same rule [verify] applies to the record after it, so a
     * writer and a verifier always agree on where a chain resumes.
     *
     * Best effort on purpose, because this runs on the write path, which must never throw. When the
     * newest record cannot be read, recovery probes older rotations and uses genesis only
     * when none can be read. [verify] still reports the unreadable file or broken adjacency.
     */
    fun lastChainHead(): String {
        for (position in 0..BACKUP_PROBE_LIMIT) {
            val file = fileAt(position)?.takeIf { it.exists() && it.isFile } ?: continue
            val last = lastRecordIn(file) ?: continue
            return last.hash ?: McpLedgerChain.GENESIS_HASH
        }
        return McpLedgerChain.GENESIS_HASH
    }

    // A swallowed failure here is the point: recovery is advisory, and verify() reports the real
    // problem with the file it could not read.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun lastRecordIn(file: File): McpOperationRecord? =
        try {
            file
                .useLines { lines -> lines.filter { it.isNotBlank() }.lastOrNull() }
                ?.let { json.decodeFromString<McpOperationRecord>(it) }
        } catch (t: Exception) {
            null
        }

    /**
     * The parent hash [entry] must declare, or null when the record before it is not on disk and
     * there is therefore nothing to compare it against.
     *
     * Two records are neighbours when they sit in the same file, or in files that are consecutive in
     * the rotation sequence. Two files with a gap between them are not neighbours: whatever held the
     * link between them is gone, so a mismatch there would say nothing.
     */
    private fun requiredParentOf(
        entry: McpLedgerEntry,
        previous: McpLedgerEntry?,
        previousPosition: Int?,
        position: Int?,
    ): String? {
        if (previous == null) return null
        if (previous.file != entry.file) {
            if (previousPosition == null || position == null || previousPosition != position + 1) return null
        }
        // A predecessor written before integrity tracking has no hash of its own, and the writer
        // that followed it chained to genesis. That is deterministic, so it is checked, not skipped.
        return previous.record.hash ?: McpLedgerChain.GENESIS_HASH
    }

    /**
     * Walks every record oldest-first and checks each one twice: that its contents hash to the value
     * it stores, and that it chains to the record that actually precedes it. The first failure stops
     * the walk, because that is the record an operator needs to look at.
     *
     * The two checks catch different edits. A record changed in place fails the first; a record
     * deleted, inserted or moved fails the second, on whichever record now sits where the removed
     * one used to be. Only a record with no hash at all is unverifiable, which is what a record
     * written before integrity tracking existed looks like - so an upgraded ledger reports those and
     * nothing else, rather than calling them tampering.
     */
    fun verify(): McpLedgerVerification {
        val entries = readEntries()
        var chained = 0
        var unverifiable = 0
        var unverifiableSuffix = 0
        var hasSeenChainedRecord = false
        var oldestVerifiable: McpLedgerEntry? = null
        var firstBreak: McpLedgerBreak? = null
        var previous: McpLedgerEntry? = null
        var previousPosition: Int? = null

        for ((index, entry) in entries.withIndex()) {
            val position = positionOf(entry.file)
            val stored = entry.record.hash
            if (stored == null) {
                unverifiable++
                if (hasSeenChainedRecord) unverifiableSuffix++
                previous = entry
                previousPosition = position
                continue
            }

            // 1. The record against its own contents. Exact even when its predecessor is gone,
            //    because the parent it chained to is stored on the record.
            val declaredParent = entry.record.parentHash ?: McpLedgerChain.GENESIS_HASH
            val recomputed = McpLedgerChain.linkHash(declaredParent, entry.record)
            if (recomputed != stored) {
                firstBreak = breakOf(entry, index, McpLedgerBreakReason.RECORD_ALTERED, recomputed, stored)
                break
            }

            // 2. The record against the one before it, where that record is still on disk.
            val requiredParent = requiredParentOf(entry, previous, previousPosition, position)
            if (requiredParent != null && declaredParent != requiredParent) {
                firstBreak =
                    breakOf(entry, index, McpLedgerBreakReason.LINK_BROKEN, requiredParent, declaredParent)
                break
            }

            chained++
            hasSeenChainedRecord = true
            if (oldestVerifiable == null) oldestVerifiable = entry
            previous = entry
            previousPosition = position
        }

        return McpLedgerVerification(
            files = existingFilesOldestFirst().map { it.name },
            totalRecords = entries.size,
            chainedRecords = chained,
            unverifiableRecords = unverifiable,
            unverifiableSuffixRecords = unverifiableSuffix,
            oldestVerifiableFile = oldestVerifiable?.file?.name,
            oldestVerifiableLine = oldestVerifiable?.lineNumber,
            firstBreak = firstBreak,
            coverageGaps = coverageGaps(),
        )
    }

    private fun breakOf(
        entry: McpLedgerEntry,
        index: Int,
        reason: McpLedgerBreakReason,
        expectedHash: String,
        foundHash: String,
    ) = McpLedgerBreak(
        reason = reason,
        fileName = entry.file.name,
        lineNumber = entry.lineNumber,
        recordIndex = index + 1,
        recordId = entry.record.id,
        timestamp = entry.record.timestamp,
        expectedHash = expectedHash,
        foundHash = foundHash,
    )
}
