package ai.rever.boss.run

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of RunConfigurationManager.
 * Manages run configurations with JSON persistence in ~/.boss/run-configurations.json
 */
actual object RunConfigurationManager {
    private val logger = BossLogger.forComponent("RunConfigurationManager")

    /**
     * The production settings path, captured once so [resetForTesting] can restore it without
     * re-deriving the literal at every call site.
     */
    private val defaultSettingsFile = BossDirectories.resolve("run-configurations.json")

    /**
     * Overridable so hermetic tests exercise the real read/write path without touching
     * `~/.boss`. Restored by [resetForTesting] callers; production code never reassigns it.
     */
    @Volatile
    internal var settingsFile: File = defaultSettingsFile
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            // RunConfiguration.timestamp has a time-varying default. Encoding defaults keeps a
            // configuration saved in its creation millisecond from decoding later with load time.
            encodeDefaults = true
        }

    /** The trailing " (...)" group of a configuration name, which disambiguation rewrites. */
    private val trailingGroupRegex = Regex("\\([^)]+\\)$")

    /** The " [Project]" part inside that group, present when the name came from a project scan. */
    private val trailingProjectRegex = Regex("( \\[[^\\]]*])\\)$")

    private val detector = DesktopMainFunctionDetector()

    private val _currentSettings = MutableStateFlow(RunConfigurationSettings())
    private val settingsMutex = Mutex()
    actual val currentSettings: StateFlow<RunConfigurationSettings> = _currentSettings.asStateFlow()

    private val _detectedConfigurations = MutableStateFlow<List<RunConfiguration>>(emptyList())
    actual val detectedConfigurations: StateFlow<List<RunConfiguration>> = _detectedConfigurations.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    actual val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()

        // Load settings on initialization
        loadSettingsSync()
    }

    /**
     * Load settings synchronously on startup or test reset.
     * Note: Does NOT auto-select any configuration - user must explicitly select one.
     * Existing configs are deduplicated and names made unique.
     */
    internal fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val cleanedSettings = loadSettingsFromFile(settingsFile)
                _currentSettings.value = cleanedSettings

                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded run configurations",
                    mapOf(
                        "count" to cleanedSettings.configurations.size,
                        "path" to settingsFile.absolutePath,
                    ),
                )
            } else {
                // A missing file is not an error, but a reset must leave the manager empty
                // rather than whatever a previous load (or test) left behind.
                _currentSettings.value = RunConfigurationSettings()
                logger.debug(LogCategory.SYSTEM, "No settings file found, starting with empty configurations")
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load run settings", error = e)
            _currentSettings.value = RunConfigurationSettings()
        }
    }

    /**
     * Reset manager state and optionally redirect [settingsFile] to [testFile]; with no
     * argument, restore [defaultSettingsFile]. Call only when no mutation is in flight - the
     * load re-reads [settingsFile] without [settingsMutex] (see [loadSettingsFromFile]) - and
     * always call with no argument before finishing, so the singleton is left where the app
     * and other tests expect it.
     */
    internal fun resetForTesting(testFile: File? = null) {
        settingsFile = testFile ?: defaultSettingsFile
        _detectedConfigurations.value = emptyList()
        _isScanning.value = false
        _lastError.value = null
        loadSettingsSync()
    }

    /**
     * Reads a file without changing the manager's state; startup is its production caller.
     *
     * The optional cleanup write does not hold [settingsMutex]. That is safe only because
     * every current caller runs with no mutation in flight: the production caller runs from
     * the object's init block, where the JVM class-initialisation lock still serialises every
     * other thread's first use, and the test caller [resetForTesting] is only ever invoked
     * between tests (its KDoc states the same precondition).
     * Any future caller (reload, file watcher) must hold [settingsMutex] around the write or
     * it can clobber newer persisted state.
     */
    internal fun loadSettingsFromFile(
        file: File,
        writeCleaned: (File, String) -> Unit = { target, content -> target.atomicWriteText(content) },
    ): RunConfigurationSettings {
        val settings = json.decodeFromString<RunConfigurationSettings>(file.readText())

        val deduplicated = settings.configurations.distinctBy { it.filePath }
        val withUniqueNames = makeStoredNamesUnique(deduplicated)
        val cleanedSettings = settings.copy(configurations = withUniqueNames)

        if (deduplicated.size != settings.configurations.size) {
            try {
                val cleanedContent =
                    json.encodeToString(RunConfigurationSettings.serializer(), cleanedSettings)
                writeCleaned(file, cleanedContent)
                logger.debug(
                    LogCategory.SYSTEM,
                    "Cleaned up duplicate run configurations",
                    mapOf("removed" to (settings.configurations.size - deduplicated.size)),
                )
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not write cleaned run configurations; keeping loaded settings",
                    error = e,
                )
            }
        }

        return cleanedSettings
    }

    /**
     * Make stored configuration names unique using parent directory context.
     */
    internal fun makeStoredNamesUnique(configs: List<RunConfiguration>): List<RunConfiguration> {
        val nameGroups = configs.groupBy { it.name }

        return configs.map { config ->
            val group = nameGroups[config.name] ?: return@map config
            if (group.size <= 1) {
                config
            } else {
                // Add parent directory to make unique. A stored filePath is an OS-native
                // absolute path (File.absolutePath), so split on both separators. Empty segments
                // are dropped the way DesktopMainFunctionDetector.detectModuleName drops them:
                // this path is read from run-configurations.json, which is hand-editable, and a
                // doubled separator would otherwise put "" into takeLast(2) and label it "/Main.kt".
                val parts = config.filePath.split('/', '\\').filter { it.isNotEmpty() }
                val uniqueName =
                    if (parts.size >= 2) {
                        val parentAndFile = parts.takeLast(2).joinToString("/")
                        // Keep any " [Project]" the stored name already carries: the trailing-group
                        // regex would otherwise consume it, and makeNamesUnique rebuilds it.
                        val projectSuffix = trailingProjectRegex.find(config.name)?.groupValues?.get(1) ?: ""
                        config.name.replace(trailingGroupRegex) { "($parentAndFile$projectSuffix)" }
                    } else {
                        config.name
                    }
                config.copy(name = uniqueName)
            }
        }
    }

    /**
     * Scan a project directory for runnable entry points.
     * Note: Does NOT auto-select any configuration - user must explicitly select one.
     * Names are made unique by adding path context when duplicates exist.
     * Clears previous detected configs before scanning to prevent unbounded growth.
     */
    actual suspend fun scanProject(projectPath: String) =
        withContext(Dispatchers.IO) {
            _isScanning.value = true
            _lastError.value = null // Clear previous error
            // Clear previous detections to prevent memory leak on project switches
            _detectedConfigurations.value = emptyList()
            try {
                logger.debug(LogCategory.SYSTEM, "Scanning project for run configurations", mapOf("path" to projectPath))
                val detected = detector.scanProject(projectPath)
                val detectedWithUniqueNames = makeNamesUnique(detected, projectPath)
                _detectedConfigurations.value = detectedWithUniqueNames
                logger.debug(LogCategory.SYSTEM, "Found runnable configurations", mapOf("count" to detectedWithUniqueNames.size))
                // Don't auto-select - user must choose from dropdown
            } catch (e: Exception) {
                val errorMsg = "Failed to scan project: ${e.message}"
                logger.warn(LogCategory.SYSTEM, "Failed to scan project", error = e)
                _lastError.value = errorMsg
            } finally {
                _isScanning.value = false
            }
        }

    /**
     * Clear the last error.
     */
    actual suspend fun clearError() {
        _lastError.value = null
    }

    /**
     * Make configuration names unique by adding parent directory context for duplicates.
     * E.g., two "main (Main.kt [Project])" become "main (app/Main.kt [Project])" and "main (lib/Main.kt [Project])"
     * Preserves the project name in brackets if present.
     */
    internal fun makeNamesUnique(
        configs: List<RunConfiguration>,
        projectPath: String,
    ): List<RunConfiguration> {
        // Group by name to find duplicates
        val nameGroups = configs.groupBy { it.name }
        val projectName = projectPath.trimEnd('/', '\\').extractFileName().takeIf { it.isNotBlank() }

        return configs.map { config ->
            val group = nameGroups[config.name] ?: return@map config
            if (group.size <= 1) {
                config
            } else {
                // Add parent directory to make unique, preserving project name
                val relativePath = config.filePath.removePrefix(projectPath)
                val parts = relativePath.split('/', '\\').filter { it.isNotEmpty() }
                val uniqueName =
                    if (parts.size >= 2) {
                        // Include parent directory: "main (parent/Main.kt [Project])"
                        val parentAndFile = parts.takeLast(2).joinToString("/")
                        val projectSuffix = if (projectName != null) " [$projectName]" else ""
                        config.name.replace(trailingGroupRegex) { "($parentAndFile$projectSuffix)" }
                    } else {
                        config.name
                    }
                config.copy(name = uniqueName)
            }
        }
    }

    /**
     * Add a new run configuration.
     * - Checks for duplicates by filePath (same file = same config)
     * - Generates unique name with number suffix if name already exists
     */
    actual suspend fun addConfiguration(config: RunConfiguration) {
        settingsMutex.withLock {
            val current = _currentSettings.value

            val existingByPath = current.configurations.find { it.filePath == config.filePath }
            if (existingByPath != null) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Configuration already exists, skipping",
                    mapOf("filePath" to config.filePath),
                )
                return@withLock
            }

            val uniqueName = generateUniqueName(config.name, current.configurations.map { it.name })
            val configWithUniqueName =
                if (uniqueName != config.name) {
                    config.copy(name = uniqueName)
                } else {
                    config
                }

            val updated =
                current.copy(
                    configurations = current.configurations + configWithUniqueName,
                )
            _currentSettings.value = updated
            persistSettings(updated)
            logger.debug(LogCategory.SYSTEM, "Added run configuration", mapOf("name" to configWithUniqueName.name))
        }
    }

    /**
     * Generate a unique name by appending a number suffix if needed.
     * E.g., "Main" -> "Main", "Main" (if exists) -> "Main (2)", etc.
     */
    private fun generateUniqueName(
        baseName: String,
        existingNames: List<String>,
    ): String {
        if (baseName !in existingNames) {
            return baseName
        }

        var counter = 2
        while (true) {
            val candidateName = "$baseName ($counter)"
            if (candidateName !in existingNames) {
                return candidateName
            }
            counter++
        }
    }

    /**
     * Remove a run configuration by ID.
     */
    actual suspend fun removeConfiguration(configId: String) {
        settingsMutex.withLock {
            val current = _currentSettings.value
            val updated =
                current.copy(
                    configurations = current.configurations.filter { it.id != configId },
                    lastUsedConfigId = if (current.lastUsedConfigId == configId) null else current.lastUsedConfigId,
                    recentConfigIds = current.recentConfigIds.filter { it != configId },
                )
            _currentSettings.value = updated
            persistSettings(updated)
        }
    }

    /**
     * Update an existing run configuration.
     */
    actual suspend fun updateConfiguration(config: RunConfiguration) {
        settingsMutex.withLock {
            val current = _currentSettings.value
            val updated =
                current.copy(
                    configurations =
                        current.configurations.map {
                            if (it.id == config.id) config else it
                        },
                )
            _currentSettings.value = updated
            persistSettings(updated)
        }
    }

    /**
     * Clear all detected configurations.
     */
    actual suspend fun clearDetected() {
        _detectedConfigurations.value = emptyList()
    }

    /**
     * Save current settings to disk.
     */
    actual suspend fun saveSettings() =
        settingsMutex.withLock {
            persistSettings(_currentSettings.value)
        }

    private suspend fun persistSettings(settings: RunConfigurationSettings) =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(RunConfigurationSettings.serializer(), settings)
                settingsFile.atomicWriteText(content)
                logger.debug(LogCategory.SYSTEM, "Run settings saved", mapOf("path" to settingsFile.absolutePath))
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Failed to save run settings", error = e)
            }
        }
}
