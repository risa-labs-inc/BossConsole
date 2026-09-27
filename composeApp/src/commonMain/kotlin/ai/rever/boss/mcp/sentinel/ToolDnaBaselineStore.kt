package ai.rever.boss.mcp.sentinel

import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages persistent baselines for MCP tool definitions.
 *
 * Baseline key format: `providerId/toolName`.
 * Persisted as JSON array via `BossDirectories.resolve("mcp-tooldna-baseline.json")` (under `~/.boss/`).
 */
class ToolDnaBaselineStore(
    private val baselineFile: File,
    private val hmacKeyFile: File? = null,
) {
    private val logger = BossLogger.forComponent("ToolDnaBaselineStore")
    private val lock = Any()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val secretKeySpec: javax.crypto.spec.SecretKeySpec? by lazy {
        SentinelHmacHelper.initOrLoadHmacKey(baselineFile, hmacKeyFile, logger)
    }

    private val baselines = mutableMapOf<String, ToolBaselineRecord>()

    @Volatile
    var isCorrupted: Boolean = false
        private set

    init {
        loadFromDisk()
    }

    fun getBaseline(
        providerId: String,
        toolName: String,
    ): ToolBaselineRecord? =
        synchronized(lock) {
            baselines[SentinelHmacHelper.makeKey(providerId, toolName)]
        }

    fun getAllBaselines(): List<ToolBaselineRecord> =
        synchronized(lock) {
            baselines.values.toList()
        }

    fun saveBaseline(
        record: ToolBaselineRecord,
        persist: Boolean = true,
    ): Boolean =
        synchronized(lock) {
            if (isCorrupted) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Refusing to save baseline: ToolDNA baseline store is marked as corrupted",
                    mapOf("path" to baselineFile.path),
                )
                return false
            }
            val signed = record.copy(hmacSignature = SentinelHmacHelper.computeHmac(secretKeySpec, record))
            val key = SentinelHmacHelper.makeKey(signed.providerId, signed.toolName)
            baselines[key] = signed
            if (persist) persistToDisk() else true
        }

    fun saveAllBaselines(records: List<ToolBaselineRecord>): Boolean =
        synchronized(lock) {
            if (isCorrupted) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Refusing to save baselines: ToolDNA baseline store is marked as corrupted",
                    mapOf("path" to (baselineFile?.path ?: "in-memory")),
                )
                return false
            }
            for (record in records) {
                val signed = record.copy(hmacSignature = SentinelHmacHelper.computeHmac(secretKeySpec, record))
                val key = SentinelHmacHelper.makeKey(signed.providerId, signed.toolName)
                baselines[key] = signed
            }
            persistToDisk()
        }

    fun removeBaseline(
        providerId: String,
        toolName: String,
    ): Boolean =
        synchronized(lock) {
            val key = SentinelHmacHelper.makeKey(providerId, toolName)
            if (baselines.remove(key) != null) {
                persistToDisk()
            } else {
                false
            }
        }

    fun backupAndClear(): Boolean =
        synchronized(lock) {
            val file = baselineFile
            if (file != null && file.exists() && isCorrupted) {
                val backupFile = File(file.parentFile, file.name + ".corrupt")
                try {
                    file.copyTo(backupFile, overwrite = true)
                } catch (e: java.io.IOException) {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to create .corrupt backup for ToolDNA baseline",
                        mapOf("path" to file.path, "error" to (e.message ?: "IOException")),
                    )
                    return false
                } catch (e: java.lang.SecurityException) {
                    // File.copyTo() can throw SecurityException for access-denied conditions;
                    // it is a specific JDK platform type, not a logical catch-all.
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to create .corrupt backup for ToolDNA baseline",
                        mapOf("path" to file.path, "error" to (e.message ?: "SecurityException")),
                    )
                    return false
                }
            }
            baselines.clear()
            isCorrupted = false
            return persistToDisk()
        }

    private fun loadFromDisk() {
        val file = baselineFile
        if (file == null || !file.exists()) return

        synchronized(lock) {
            try {
                val text = file.readText()
                if (text.isNotBlank()) {
                    if (secretKeySpec == null) {
                        isCorrupted = true
                        logger.error(
                            LogCategory.SYSTEM,
                            "ToolDNA HMAC key unavailable; failing closed",
                            mapOf("path" to file.path),
                        )
                    } else {
                        val records = json.decodeFromString<List<ToolBaselineRecord>>(text)
                        processLoadedRecords(records, file.path)
                    }
                }
            } catch (e: SerializationException) {
                isCorrupted = true
                logger.error(
                    LogCategory.SYSTEM,
                    "Corrupted ToolDNA baseline store JSON file on disk",
                    mapOf("path" to file.path, "error" to (e.message ?: "SerializationException")),
                )
            } catch (e: java.io.IOException) {
                isCorrupted = true
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to read ToolDNA baseline store from disk",
                    mapOf("path" to file.path, "error" to (e.message ?: "IOException")),
                )
            } catch (e: IllegalArgumentException) {
                isCorrupted = true
                logger.error(
                    LogCategory.SYSTEM,
                    "Invalid baseline format reading ToolDNA baseline store",
                    mapOf("path" to file.path, "error" to (e.message ?: "IllegalArgumentException")),
                )
            }
        }
    }

    private fun processLoadedRecords(
        records: List<ToolBaselineRecord>,
        filePath: String,
    ) {
        baselines.clear()
        for (rec in records) {
            val key = SentinelHmacHelper.makeKey(rec.providerId, rec.toolName)
            if (rec.hmacSignature.isNullOrBlank()) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Unsigned ToolDNA baseline record on disk; operator re-approval required",
                    mapOf("provider" to rec.providerId, "tool" to rec.toolName),
                )
                baselines[key] =
                    rec.copy(
                        trustState = SentinelTrustState.REVIEW_REQUIRED,
                        reasonForReevaluation = "Unsigned baseline record: operator re-approval required.",
                        hmacSignature = null,
                        // Clear userDecision: an unsigned record's audit label must not influence
                        // authorization (defense-in-depth against any future trust-path regression).
                        userDecision = null,
                    )
            } else if (!SentinelHmacHelper.verifyHmac(secretKeySpec, rec)) {
                logger.error(
                    LogCategory.SYSTEM,
                    "HMAC verification failed for ToolDNA baseline record on disk (possible tampering)",
                    mapOf("provider" to rec.providerId, "tool" to rec.toolName),
                )
                val tamperMsg =
                    "HMAC verification failed: baseline record tampered with; " +
                        "operator re-approval required."
                baselines[key] =
                    rec.copy(
                        trustState = SentinelTrustState.REVIEW_REQUIRED,
                        reasonForReevaluation = tamperMsg,
                        hmacSignature = null,
                        // Clear userDecision: a tampered record's audit label must not influence
                        // authorization (defense-in-depth against any future trust-path regression).
                        userDecision = null,
                    )
            } else {
                baselines[key] = rec
            }
        }
        isCorrupted = false
        logger.info(
            LogCategory.SYSTEM,
            "Loaded ToolDNA baselines from disk",
            mapOf("count" to records.size, "path" to filePath),
        )
    }

    private fun persistToDisk(): Boolean {
        val file = baselineFile ?: return true
        return try {
            val list =
                baselines.values.map { rec ->
                    val sig = SentinelHmacHelper.computeHmac(secretKeySpec, rec)
                    rec.copy(hmacSignature = sig)
                }
            val encoded = json.encodeToString(list)
            file.atomicWriteText(encoded)
            isCorrupted = false
            true
        } catch (e: java.io.IOException) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to write ToolDNA baseline store to disk",
                mapOf("path" to file.path, "error" to (e.message ?: "IOException")),
            )
            false
        }
    }
}

internal object SentinelHmacHelper {
    fun makeKey(
        providerId: String,
        toolName: String,
    ): String = "${providerId.trim().lowercase()}/$toolName"

    fun initOrLoadHmacKey(
        baselineFile: File?,
        hmacKeyFile: File?,
        logger: ComponentLogger,
    ): javax.crypto.spec.SecretKeySpec? {
        val targetKeyFile =
            hmacKeyFile
                ?: baselineFile?.parentFile?.let { File(it, "sentinel/tooldna-master.key") }

        return when {
            baselineFile == null && hmacKeyFile == null -> {
                val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                javax.crypto.spec.SecretKeySpec(fresh, "HmacSHA256")
            }

            targetKeyFile == null -> {
                val seed = java.security.SecureRandom().generateSeed(32)
                javax.crypto.spec.SecretKeySpec(seed, "HmacSHA256")
            }

            else -> {
                readOrCreateKeyFile(targetKeyFile, logger)
            }
        }
    }

    private fun readOrCreateKeyFile(
        targetKeyFile: File,
        logger: ComponentLogger,
    ): javax.crypto.spec.SecretKeySpec? =
        try {
            targetKeyFile.parentFile?.mkdirs()
            val bytes =
                if (targetKeyFile.isFile && targetKeyFile.length() == 32L) {
                    targetKeyFile.readBytes()
                } else {
                    generateAndWriteKey(targetKeyFile, logger)
                }
            javax.crypto.spec.SecretKeySpec(bytes, "HmacSHA256")
        } catch (e: java.io.IOException) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to initialize ToolDNA HMAC master key (I/O error)",
                mapOf("path" to targetKeyFile.path, "error" to (e.message ?: "IOException")),
            )
            null
        } catch (e: java.security.GeneralSecurityException) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to initialize ToolDNA HMAC master key (Security error)",
                mapOf("path" to targetKeyFile.path, "error" to (e.message ?: "SecurityException")),
            )
            null
        }

    /** Generate a fresh 32-byte key, write it to [targetKeyFile], and restrict its permissions. */
    private fun generateAndWriteKey(
        targetKeyFile: File,
        logger: ComponentLogger,
    ): ByteArray {
        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        targetKeyFile.writeBytes(fresh)
        restrictKeyFilePermissions(targetKeyFile, logger)
        return fresh
    }

    private fun restrictKeyFilePermissions(
        targetKeyFile: File,
        logger: ComponentLogger,
    ) {
        try {
            val view =
                java.nio.file.Files.getFileAttributeView(
                    targetKeyFile.toPath(),
                    java.nio.file.attribute.PosixFileAttributeView::class.java,
                )
            if (view != null) {
                java.nio.file.Files.setPosixFilePermissions(
                    targetKeyFile.toPath(),
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    ),
                )
            }
        } catch (e: java.io.IOException) {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not set owner-only permissions on HMAC key file",
                mapOf("path" to targetKeyFile.path, "error" to (e.message ?: "IOException")),
            )
        } catch (e: java.lang.SecurityException) {
            // Files.setPosixFilePermissions() can throw SecurityException on JVM when the
            // security manager disallows the operation; it is a specific JDK platform type.
            logger.warn(
                LogCategory.SYSTEM,
                "Could not set owner-only permissions on HMAC key file",
                mapOf("path" to targetKeyFile.path, "error" to (e.message ?: "SecurityException")),
            )
        }
    }

    const val HMAC_PAYLOAD_VERSION: String = "v1"

    fun computeHmac(
        key: javax.crypto.spec.SecretKeySpec?,
        record: ToolBaselineRecord,
    ): String {
        if (key == null) return ""
        val payload =
            buildString {
                append("payloadVersion=").append(HMAC_PAYLOAD_VERSION).append("\n")
                append("provider=").append(record.providerId.trim().lowercase()).append("\n")
                append("tool=").append(record.toolName).append("\n")
                append("fingerprint=").append(record.canonicalFingerprint).append("\n")
                append("version=").append(record.fingerprintVersion).append("\n")
                append("trustState=").append(record.trustState.name).append("\n")
                append("readOnly=").append(record.readOnly).append("\n")
                append("requiresAdmin=").append(record.requiresAdmin).append("\n")
                append("userDecision=").append(record.userDecision.orEmpty()).append("\n")
                append("lastAcceptedDescription=").append(record.lastAcceptedDescription).append("\n")
                append("lastAcceptedSchemaJson=").append(record.lastAcceptedSchemaJson).append("\n")
            }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun computeLegacyHmac(
        key: javax.crypto.spec.SecretKeySpec?,
        record: ToolBaselineRecord,
    ): String {
        if (key == null) return ""
        val payload =
            buildString {
                append("provider=").append(record.providerId.trim().lowercase()).append("\n")
                append("tool=").append(record.toolName).append("\n")
                append("fingerprint=").append(record.canonicalFingerprint).append("\n")
                append("version=").append(record.fingerprintVersion).append("\n")
                append("trustState=").append(record.trustState.name).append("\n")
                append("readOnly=").append(record.readOnly).append("\n")
                append("requiresAdmin=").append(record.requiresAdmin).append("\n")
                append("userDecision=").append(record.userDecision.orEmpty()).append("\n")
                append("lastAcceptedDescription=").append(record.lastAcceptedDescription).append("\n")
                append("lastAcceptedSchemaJson=").append(record.lastAcceptedSchemaJson).append("\n")
            }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyHmac(
        key: javax.crypto.spec.SecretKeySpec?,
        record: ToolBaselineRecord,
    ): Boolean {
        val sig = record.hmacSignature
        if (sig.isNullOrBlank() || key == null) return false
        val sigBytes = sig.toByteArray(Charsets.UTF_8)
        val matchesV1 =
            java.security.MessageDigest.isEqual(
                computeHmac(key, record).toByteArray(Charsets.UTF_8),
                sigBytes,
            )
        return matchesV1 ||
            java.security.MessageDigest.isEqual(
                computeLegacyHmac(key, record).toByteArray(Charsets.UTF_8),
                sigBytes,
            )
    }
}
