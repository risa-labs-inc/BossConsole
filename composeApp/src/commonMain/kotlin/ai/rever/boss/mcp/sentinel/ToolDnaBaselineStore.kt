package ai.rever.boss.mcp.sentinel

import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
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
    private val baselineFile: File? = null,
) {
    private val logger = BossLogger.forComponent("ToolDnaBaselineStore")
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val baselines = mutableMapOf<String, ToolBaselineRecord>()

    @Volatile
    var isCorrupted: Boolean = false
        private set

    init {
        loadFromDisk()
    }

    fun makeKey(providerId: String, toolName: String): String = "$providerId/$toolName"

    fun getBaseline(providerId: String, toolName: String): ToolBaselineRecord? = synchronized(lock) {
        baselines[makeKey(providerId, toolName)]
    }

    fun getAllBaselines(): List<ToolBaselineRecord> = synchronized(lock) {
        baselines.values.toList()
    }

    fun saveBaseline(record: ToolBaselineRecord): Boolean = synchronized(lock) {
        if (isCorrupted) {
            logger.warn(
                LogCategory.SYSTEM,
                "Refusing to save baseline: ToolDNA baseline store is marked as corrupted",
                mapOf("path" to (baselineFile?.path ?: "in-memory")),
            )
            return false
        }
        val key = makeKey(record.providerId, record.toolName)
        baselines[key] = record
        persistToDisk()
    }

    fun saveAllBaselines(records: List<ToolBaselineRecord>): Boolean = synchronized(lock) {
        if (isCorrupted) {
            logger.warn(
                LogCategory.SYSTEM,
                "Refusing to save baselines: ToolDNA baseline store is marked as corrupted",
                mapOf("path" to (baselineFile?.path ?: "in-memory")),
            )
            return false
        }
        for (record in records) {
            val key = makeKey(record.providerId, record.toolName)
            baselines[key] = record
        }
        persistToDisk()
    }

    fun removeBaseline(providerId: String, toolName: String): Boolean = synchronized(lock) {
        val key = makeKey(providerId, toolName)
        if (baselines.remove(key) != null) {
            persistToDisk()
        } else false
    }

    fun clear(): Boolean = synchronized(lock) {
        baselines.clear()
        isCorrupted = false
        persistToDisk()
    }

    private fun loadFromDisk() {
        val file = baselineFile ?: return
        if (!file.exists()) return

        synchronized(lock) {
            try {
                val text = file.readText()
                if (text.isNotBlank()) {
                    val records = json.decodeFromString<List<ToolBaselineRecord>>(text)
                    baselines.clear()
                    for (rec in records) {
                        baselines[makeKey(rec.providerId, rec.toolName)] = rec
                    }
                    isCorrupted = false
                    logger.info(
                        LogCategory.SYSTEM,
                        "Loaded ToolDNA baselines from disk",
                        mapOf("count" to records.size, "path" to file.path),
                    )
                }
            } catch (e: SerializationException) {
                isCorrupted = true
                logger.error(
                    LogCategory.SYSTEM,
                    "Corrupted ToolDNA baseline store JSON file on disk",
                    mapOf("path" to file.path, "error" to (e.message ?: "SerializationException")),
                )
            } catch (t: Throwable) {
                isCorrupted = true
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to read ToolDNA baseline store from disk",
                    mapOf("path" to file.path, "error" to (t.message ?: t::class.simpleName)),
                )
            }
        }
    }

    private fun persistToDisk(): Boolean {
        val file = baselineFile ?: return true
        return try {
            val list = baselines.values.toList()
            val encoded = json.encodeToString(list)
            file.atomicWriteText(encoded)
            isCorrupted = false
            true
        } catch (t: Throwable) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to write ToolDNA baseline store to disk",
                mapOf("path" to file.path, "error" to (t.message ?: t::class.simpleName)),
            )
            false
        }
    }
}

