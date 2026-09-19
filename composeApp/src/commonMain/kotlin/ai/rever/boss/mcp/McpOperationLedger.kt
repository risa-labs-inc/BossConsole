package ai.rever.boss.mcp

import ai.rever.boss.plugin.logging.LogSanitizer
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/**
 * Append-only persistent journal and real-time telemetry buffer for MCP tool executions.
 *
 * Saves records to `~/.boss/mcp-calls.jsonl` with size-based rotation (active file + up to 5 backups).
 * Arguments and error snippets are sanitized via [LogSanitizer] before persistence to prevent
 * credential leaks.
 *
 * Each persisted record also carries a [McpOperationRecord.hash] chained to the record before it, so
 * the durable file is tamper-evident and not merely append-only - see [McpLedgerChain] and
 * [verifyChain]. [recentOperations] mirrors what was written, hashes included.
 *
 * Scope note: Tool discovery, RBAC permissions, and kill-switch blocks are enforced upstream
 * in tool resolution; unpermitted or unregistered tool calls are blocked before reaching
 * policy checks and the operation ledger.
 */
class McpOperationLedger(
    private val ledgerFile: File? = null,
    private val maxFileSizeBytes: Long = 10L * 1024 * 1024, // 10 MB
    private val maxBackupIndex: Int = 5,
    private val ringBufferCapacity: Int = 100,
) {
    /** The actual optional persistence destination, for operator-facing inspection. */
    val persistencePath: String? get() = ledgerFile?.absolutePath

    private val logger = BossLogger.forComponent("McpOperationLedger")
    private val writeLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = McpLedgerStore(ledgerFile)

    /**
     * The hash the next persisted record chains to, recovered from the newest record already on
     * disk the first time this process writes. Without that recovery a restart would begin a
     * second chain, and every record written after it would read as a break.
     */
    private var chainHead: String = McpLedgerChain.GENESIS_HASH
    private var chainHeadRecovered = false

    private val _recentOperations = MutableStateFlow<List<McpOperationRecord>>(emptyList())
    val recentOperations: StateFlow<List<McpOperationRecord>> = _recentOperations.asStateFlow()

    private val _totalCalls = MutableStateFlow(0L)
    val totalCalls: StateFlow<Long> = _totalCalls.asStateFlow()

    private val _totalErrors = MutableStateFlow(0L)
    val totalErrors: StateFlow<Long> = _totalErrors.asStateFlow()

    /**
     * Record a tool execution, rejection, or timeout.
     * Never throws - I/O failures are logged without disrupting tool return.
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
    ): McpOperationRecord {
        val sanitized = sanitizeArguments(rawArgs)
        val sanitizedErrorSnippet = errorSnippet?.let { McpArgumentSanitizer.sanitizeMessage(it).take(4096) }
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
            )

        // 1. Persist first, because persistence is what assigns the chain hash. What comes back is
        //    the record on disk, or the draft when there is no ledger file or the write failed, so
        //    the in-memory copy below mirrors exactly what was written rather than a pre-hash one.
        val record = persistRecord(draft)

        // 2. Update in-memory telemetry ring buffer
        _recentOperations.update { current ->
            (listOf(record) + current).take(ringBufferCapacity)
        }
        _totalCalls.update { it + 1 }
        if (isError) {
            _totalErrors.update { it + 1 }
        }

        return record
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
     * Appends [record] to the durable ledger chained to the previous record's hash, and returns the
     * record that was written. The input comes back unchanged when there is no ledger file or the
     * write failed, so a caller never sees a hash the ledger does not hold.
     *
     * Never throws: an audit failure must not change the already-completed tool result.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Audit failure must not alter the tool result.
    private fun persistRecord(record: McpOperationRecord): McpOperationRecord {
        val file = ledgerFile ?: return record
        synchronized(writeLock) {
            try {
                // Recovered before rotation: rotation renames the active file, and the record being
                // written chains to what that file held a moment ago either way.
                if (!chainHeadRecovered) {
                    chainHead = store.lastChainHead()
                    chainHeadRecovered = true
                }
                val chainedHash = McpLedgerChain.linkHash(chainHead, record)
                val chained = record.copy(hash = chainedHash, parentHash = chainHead)
                rotateIfNeeded(file)
                file.parentFile?.mkdirs()
                createOrRestrictToOwner(file)
                file.appendText(json.encodeToString(chained) + "\n")
                chainHead = chainedHash
                return chained
            } catch (t: Exception) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to append record to MCP operation ledger",
                    mapOf("path" to file.path, "error" to (t.message ?: t::class.simpleName)),
                )
            }
        }
        return record
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

    // Rotation walks numbered backups under a single write lock.
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
     * newest record cannot be read, the caller writes a record chained to genesis and [verify]
     * reports the unreadable or malformed file rather than passing it.
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
