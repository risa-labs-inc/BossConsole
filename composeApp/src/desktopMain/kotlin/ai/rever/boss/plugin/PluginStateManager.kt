package ai.rever.boss.plugin

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persisted state for a single plugin.
 */
@Serializable
data class PluginPersistedState(
    /**
     * Unique plugin ID.
     */
    val pluginId: String,

    /**
     * Path to the plugin JAR file.
     */
    val jarPath: String,

    /**
     * Whether the plugin is enabled.
     */
    val enabled: Boolean = true,

    /**
     * Timestamp when the plugin was installed.
     */
    val installedAt: Long = System.currentTimeMillis(),

    /**
     * Plugin version (for update tracking).
     */
    val version: String = "",

    /**
     * Whether this plugin should auto-load on startup.
     */
    val autoLoad: Boolean = true
)

/**
 * Collection of all persisted plugin states.
 */
@Serializable
data class PluginStatesFile(
    /**
     * Version of the state file format.
     */
    val version: Int = 1,

    /**
     * Map of plugin ID to persisted state.
     */
    val plugins: Map<String, PluginPersistedState> = emptyMap()
)

/**
 * Manages persistence of installed plugin states.
 *
 * Plugins that are installed at runtime are tracked here so they can
 * be automatically loaded on subsequent app starts.
 *
 * State is persisted to `~/.boss/plugin-states.json`.
 */
class PluginStateManager(
    /**
     * Base directory for BOSS configuration files.
     * Defaults to `~/.boss`.
     */
    private val configDir: File = BossDirectories.rootDir
) {
    private val logger = BossLogger.forComponent("PluginStateManager")

    /**
     * Mutex for thread-safe file operations.
     */
    private val mutex = Mutex()

    /**
     * The state file path.
     */
    private val stateFile: File get() = File(configDir, "plugin-states.json")

    /**
     * JSON serializer with pretty printing for human-readable state files.
     */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * In-memory cache of plugin states.
     */
    private var cachedStates: PluginStatesFile? = null

    /**
     * Load all plugin states from disk.
     *
     * @return Map of plugin ID to persisted state
     */
    suspend fun loadStates(): Map<String, PluginPersistedState> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!stateFile.exists()) {
                    logger.debug(LogCategory.SYSTEM, "Plugin states file does not exist")
                    cachedStates = PluginStatesFile()
                    return@withContext emptyMap()
                }

                val content = stateFile.readText()
                val states = json.decodeFromString<PluginStatesFile>(content)
                cachedStates = states

                logger.info(LogCategory.SYSTEM, "Loaded plugin states", mapOf(
                    "count" to states.plugins.size
                ))

                states.plugins
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to load plugin states", error = e)
                cachedStates = PluginStatesFile()
                emptyMap()
            }
        }
    }

    /**
     * Save all plugin states to disk.
     *
     * @param states Map of plugin ID to persisted state
     */
    suspend fun saveStates(states: Map<String, PluginPersistedState>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                // Ensure config directory exists
                if (!configDir.exists()) {
                    configDir.mkdirs()
                }

                val statesFile = PluginStatesFile(plugins = states)
                val content = json.encodeToString(statesFile)
                stateFile.writeText(content)

                cachedStates = statesFile

                logger.info(LogCategory.SYSTEM, "Saved plugin states", mapOf(
                    "count" to states.size
                ))
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to save plugin states", error = e)
            }
        }
    }

    /**
     * Add or update a plugin's persisted state.
     *
     * @param state The plugin state to save
     */
    suspend fun savePluginState(state: PluginPersistedState) {
        val currentStates = cachedStates?.plugins ?: loadStates()
        val updatedStates = currentStates + (state.pluginId to state)
        saveStates(updatedStates)
    }

    /**
     * Remove a plugin's persisted state.
     *
     * @param pluginId The ID of the plugin to remove
     */
    suspend fun removePluginState(pluginId: String) {
        val currentStates = cachedStates?.plugins ?: loadStates()
        val updatedStates = currentStates - pluginId
        saveStates(updatedStates)
    }

    /**
     * Get a plugin's persisted state.
     *
     * @param pluginId The plugin ID
     * @return The persisted state, or null if not found
     */
    suspend fun getPluginState(pluginId: String): PluginPersistedState? {
        val states = cachedStates?.plugins ?: loadStates()
        return states[pluginId]
    }

    /**
     * Update a plugin's enabled state.
     *
     * @param pluginId The plugin ID
     * @param enabled Whether the plugin is enabled
     */
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val currentState = getPluginState(pluginId) ?: return
        savePluginState(currentState.copy(enabled = enabled))
    }

    /**
     * Get all plugins that should be auto-loaded on startup.
     *
     * @return List of plugin states for plugins that should auto-load
     */
    suspend fun getAutoLoadPlugins(): List<PluginPersistedState> {
        val states = cachedStates?.plugins ?: loadStates()
        return states.values.filter { it.autoLoad && it.enabled }
    }

    /**
     * Check if the state file exists.
     */
    fun hasStateFile(): Boolean = stateFile.exists()

    /**
     * Get the path to the state file.
     */
    fun getStateFilePath(): String = stateFile.absolutePath

    /**
     * Clear all persisted states.
     */
    suspend fun clearAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (stateFile.exists()) {
                    stateFile.delete()
                }
                cachedStates = PluginStatesFile()

                logger.info(LogCategory.SYSTEM, "Cleared all plugin states")
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to clear plugin states", error = e)
            }
        }
    }
}
