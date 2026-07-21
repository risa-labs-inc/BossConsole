package ai.rever.boss.run

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop implementation of RunConfigurationManager.
 * Manages run configurations with JSON persistence in ~/.boss/run-configurations.json
 */
actual object RunConfigurationManager {
    private val logger = BossLogger.forComponent("RunConfigurationManager")
    private val settingsFile = BossDirectories.resolve("run-configurations.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val detector = DesktopMainFunctionDetector()

    private val _currentSettings = MutableStateFlow(RunConfigurationSettings())
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
     * Load settings synchronously on startup.
     * Note: Does NOT auto-select any configuration - user must explicitly select one.
     * Existing configs are deduplicated and names made unique.
     */
    private fun loadSettingsSync() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val settings = json.decodeFromString<RunConfigurationSettings>(content)

                // Deduplicate by filePath and make names unique
                val deduplicated = settings.configurations
                    .distinctBy { it.filePath }
                val withUniqueNames = makeStoredNamesUnique(deduplicated)

                val cleanedSettings = settings.copy(configurations = withUniqueNames)
                _currentSettings.value = cleanedSettings

                logger.debug(LogCategory.SYSTEM, "Loaded run configurations", mapOf("count" to cleanedSettings.configurations.size, "path" to settingsFile.absolutePath))

                // Save cleaned settings if we deduplicated anything
                if (deduplicated.size != settings.configurations.size) {
                    settingsFile.writeText(json.encodeToString(RunConfigurationSettings.serializer(), cleanedSettings))
                    logger.debug(LogCategory.SYSTEM, "Cleaned up duplicate run configurations", mapOf("removed" to (settings.configurations.size - deduplicated.size)))
                }
            } else {
                logger.debug(LogCategory.SYSTEM, "No settings file found, starting with empty configurations")
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load run settings", error = e)
            _currentSettings.value = RunConfigurationSettings()
        }
    }

    /**
     * Make stored configuration names unique using parent directory context.
     */
    private fun makeStoredNamesUnique(configs: List<RunConfiguration>): List<RunConfiguration> {
        val nameGroups = configs.groupBy { it.name }

        return configs.map { config ->
            val group = nameGroups[config.name] ?: return@map config
            if (group.size <= 1) {
                config
            } else {
                // Add parent directory to make unique
                val parts = config.filePath.split("/")
                val uniqueName = if (parts.size >= 2) {
                    val parentAndFile = parts.takeLast(2).joinToString("/")
                    config.name.replace(Regex("\\([^)]+\\)$")) { "($parentAndFile)" }
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
    actual suspend fun scanProject(projectPath: String) = withContext(Dispatchers.IO) {
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
    private fun makeNamesUnique(configs: List<RunConfiguration>, projectPath: String): List<RunConfiguration> {
        // Group by name to find duplicates
        val nameGroups = configs.groupBy { it.name }
        val projectName = projectPath.substringAfterLast('/').takeIf { it.isNotBlank() }

        return configs.map { config ->
            val group = nameGroups[config.name] ?: return@map config
            if (group.size <= 1) {
                config
            } else {
                // Add parent directory to make unique, preserving project name
                val relativePath = config.filePath.removePrefix(projectPath).removePrefix("/")
                val parts = relativePath.split("/")
                val uniqueName = if (parts.size >= 2) {
                    // Include parent directory: "main (parent/Main.kt [Project])"
                    val parentAndFile = parts.takeLast(2).joinToString("/")
                    val projectSuffix = if (projectName != null) " [$projectName]" else ""
                    config.name.replace(Regex("\\([^)]+\\)$")) { "($parentAndFile$projectSuffix)" }
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
        val current = _currentSettings.value

        // Check if configuration with same filePath already exists
        val existingByPath = current.configurations.find { it.filePath == config.filePath }
        if (existingByPath != null) {
            logger.debug(LogCategory.SYSTEM, "Configuration already exists, skipping", mapOf("filePath" to config.filePath))
            return
        }

        // Generate unique name if needed
        val uniqueName = generateUniqueName(config.name, current.configurations.map { it.name })
        val configWithUniqueName = if (uniqueName != config.name) {
            config.copy(name = uniqueName)
        } else {
            config
        }

        val updated = current.copy(
            configurations = current.configurations + configWithUniqueName
        )
        _currentSettings.value = updated
        saveSettings()
        logger.debug(LogCategory.SYSTEM, "Added run configuration", mapOf("name" to configWithUniqueName.name))
    }

    /**
     * Generate a unique name by appending a number suffix if needed.
     * E.g., "Main" -> "Main", "Main" (if exists) -> "Main (2)", etc.
     */
    private fun generateUniqueName(baseName: String, existingNames: List<String>): String {
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
        val current = _currentSettings.value
        val updated = current.copy(
            configurations = current.configurations.filter { it.id != configId },
            lastUsedConfigId = if (current.lastUsedConfigId == configId) null else current.lastUsedConfigId,
            recentConfigIds = current.recentConfigIds.filter { it != configId }
        )
        _currentSettings.value = updated
        saveSettings()
    }

    /**
     * Update an existing run configuration.
     */
    actual suspend fun updateConfiguration(config: RunConfiguration) {
        val current = _currentSettings.value
        val updated = current.copy(
            configurations = current.configurations.map {
                if (it.id == config.id) config else it
            }
        )
        _currentSettings.value = updated
        saveSettings()
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
    actual suspend fun saveSettings() = withContext(Dispatchers.IO) {
        try {
            val content = json.encodeToString(RunConfigurationSettings.serializer(), _currentSettings.value)
            settingsFile.writeText(content)
            logger.debug(LogCategory.SYSTEM, "Run settings saved", mapOf("path" to settingsFile.absolutePath))
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to save run settings", error = e)
        }
    }
}
