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
import java.util.UUID

/**
 * Append-only persistent journal and real-time telemetry buffer for MCP tool executions.
 *
 * Saves records to `~/.boss/mcp-calls.jsonl` with size-based rotation.
 * All arguments are sanitized via [LogSanitizer] before persistence to prevent
 * credential leaks.
 */
class McpOperationLedger(
    private val ledgerFile: File? = null,
    private val maxFileSizeBytes: Long = 10L * 1024 * 1024, // 10 MB
    private val maxBackupIndex: Int = 5,
    private val ringBufferCapacity: Int = 100,
) {
    private val logger = BossLogger.forComponent("McpOperationLedger")
    private val writeLock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private val _recentOperations = MutableStateFlow<List<McpOperationRecord>>(emptyList())
    val recentOperations: StateFlow<List<McpOperationRecord>> = _recentOperations.asStateFlow()

    private val _totalCalls = MutableStateFlow(0L)
    val totalCalls: StateFlow<Long> = _totalCalls.asStateFlow()

    private val _totalErrors = MutableStateFlow(0L)
    val totalErrors: StateFlow<Long> = _totalErrors.asStateFlow()

    /**
     * Record a tool execution, rejection, or timeout.
     * Never throws — I/O failures are logged without disrupting tool return.
     */
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
        val record =
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
                errorSnippet = errorSnippet,
            )

        // 1. Update in-memory telemetry ring buffer
        _recentOperations.update { current ->
            (listOf(record) + current).take(ringBufferCapacity)
        }
        _totalCalls.update { it + 1 }
        if (isError) {
            _totalErrors.update { it + 1 }
        }

        // 2. Persist to file with rotation
        persistRecord(record)

        return record
    }

    private fun persistRecord(record: McpOperationRecord) {
        val file = ledgerFile ?: return
        synchronized(writeLock) {
            try {
                rotateIfNeeded(file)
                file.parentFile?.mkdirs()
                file.appendText(json.encodeToString(record) + "\n")
            } catch (t: Throwable) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to append record to MCP operation ledger",
                    mapOf("path" to file.path, "error" to (t.message ?: t::class.simpleName)),
                )
            }
        }
    }

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
                    src.renameTo(dst)
                }
            }

            // Move active file to .1
            val backup1 = File(parent, "${file.name}.1")
            if (backup1.exists()) backup1.delete()
            file.renameTo(backup1)

            logger.info(
                LogCategory.SYSTEM,
                "Rotated MCP operation ledger file",
                mapOf("path" to file.path),
            )
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to rotate MCP operation ledger file",
                mapOf("error" to (t.message ?: t::class.simpleName)),
            )
        }
    }

    private fun sanitizeArguments(rawArgs: Map<String, Any?>): Map<String, String> {
        val maskedMap = LogSanitizer.sanitizeMap(rawArgs)
        return maskedMap.mapValues { (_, value) ->
            val str = value?.toString() ?: "null"
            if (LogSanitizer.looksLikeSecret(str)) "[REDACTED]" else str
        }
    }
}
