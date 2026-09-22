package ai.rever.boss.tabs

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Predefined workspace tab categories with harmonious UI badge color tokens.
 */
enum class TabCategory(
    val label: String,
    val colorHex: String,
) {
    WORK("Work", "#3B82F6"), // Blue
    DEV("Dev", "#10B981"), // Emerald Green
    DOCS("Docs", "#F59E0B"), // Amber
    PERSONAL("Personal", "#EC4899"), // Pink
    IMPORTANT("Important", "#EF4444"), // Red
}

/**
 * Custom color tag metadata assigned to a workspace tab.
 */
data class TabColorTag(
    val category: TabCategory,
    val customLabel: String? = null,
) {
    val displayLabel: String
        get() = customLabel ?: category.label

    val color: Color
        get() = parseColorHex(category.colorHex)
}

@Serializable
private data class SerializableTabTag(
    val category: String,
    val customLabel: String? = null,
)

/**
 * Parses a hex color string into a Compose Color object.
 */
fun parseColorHex(hex: String): Color {
    val cleanHex = hex.removePrefix("#").trim()
    val colorInt = cleanHex.toLongOrNull(16) ?: 0x3B82F6L
    return when (cleanHex.length) {
        6 -> Color(0xFF000000 or colorInt)
        8 -> Color(colorInt)
        else -> Color(0xFF3B82F6)
    }
}

/**
 * Thread-safe registry managing tab color tags per workspace with file persistence.
 */
object TabColorRegistry {
    private val logger = BossLogger.forComponent("TabColorRegistry")
    private const val TAB_COLOR_TAGS_FILE = "tab-color-tags.json"

    private val lock = Any()
    private val tabTags = mutableMapOf<String, TabColorTag>()

    private val _tagsFlow = MutableStateFlow<Map<String, TabColorTag>>(emptyMap())
    val tagsFlow: StateFlow<Map<String, TabColorTag>> = _tagsFlow.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ioScope.launch {
            loadTagsFromDisk()
        }
    }

    /**
     * Assigns a color tag to a specific tab ID.
     */
    fun setTag(
        tabId: String,
        category: TabCategory,
        customLabel: String? = null,
    ) {
        if (tabId.isBlank()) return
        synchronized(lock) {
            tabTags[tabId] = TabColorTag(category = category, customLabel = customLabel)
            _tagsFlow.value = tabTags.toMap()
        }
        ioScope.launch {
            saveTagsToDisk()
        }
    }

    /**
     * Retrieves the color tag assigned to a tab ID.
     */
    fun getTag(tabId: String): TabColorTag? =
        synchronized(lock) {
            tabTags[tabId]
        }

    /**
     * Removes a color tag assignment from a tab ID.
     */
    fun removeTag(tabId: String) {
        synchronized(lock) {
            tabTags.remove(tabId)
            _tagsFlow.value = tabTags.toMap()
        }
        ioScope.launch {
            saveTagsToDisk()
        }
    }

    /**
     * Clears all tab color tag assignments from memory.
     */
    fun clear() {
        synchronized(lock) {
            tabTags.clear()
            _tagsFlow.value = emptyMap()
        }
    }

    private fun getTagsFile(): File? =
        runCatching {
            val bossDir = BossDirectories.rootDir
            if (!bossDir.exists()) bossDir.mkdirs()
            File(bossDir, TAB_COLOR_TAGS_FILE)
        }.getOrNull()

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun loadTagsFromDisk() =
        withContext(Dispatchers.IO) {
            try {
                val file = getTagsFile() ?: return@withContext
                if (file.exists()) {
                    val json = file.readText()
                    val rawMap = Json.decodeFromString<Map<String, SerializableTabTag>>(json)
                    val loaded =
                        rawMap
                            .mapNotNull { (id, serializable) ->
                                val cat = runCatching { TabCategory.valueOf(serializable.category) }.getOrNull()
                                if (cat != null) {
                                    id to TabColorTag(category = cat, customLabel = serializable.customLabel)
                                } else {
                                    null
                                }
                            }.toMap()

                    synchronized(lock) {
                        tabTags.clear()
                        tabTags.putAll(loaded)
                        _tagsFlow.value = tabTags.toMap()
                    }
                    logger.debug(
                        LogCategory.FILE,
                        "Loaded tab color tags from disk",
                        mapOf("count" to loaded.size),
                    )
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to load tab color tags", error = e)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun saveTagsToDisk() =
        withContext(Dispatchers.IO) {
            try {
                val file = getTagsFile() ?: return@withContext
                val snapshot =
                    synchronized(lock) {
                        tabTags.mapValues { (_, tag) ->
                            SerializableTabTag(category = tag.category.name, customLabel = tag.customLabel)
                        }
                    }
                val json = Json.encodeToString(snapshot)
                file.writeText(json)
            } catch (e: Exception) {
                logger.warn(LogCategory.FILE, "Failed to save tab color tags", error = e)
            }
        }
}
