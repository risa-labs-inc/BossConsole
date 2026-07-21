package ai.rever.boss.config

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.FileInputStream
import java.util.*

/**
 * Utility object for loading configuration from various sources.
 */
object ConfigLoader {
    private val logger = BossLogger.forComponent("ConfigLoader")
    private val properties = Properties()

    /**
     * Config baked into the app at build time by the generateEmbeddedConfig
     * Gradle task (from CI secrets or the developer's local.properties). This
     * is how packaged apps on end-user machines — which have no env vars or
     * local.properties — receive the JxBrowser license and Supabase settings.
     * The resource is generated into the build directory and never committed.
     */
    private val embeddedProperties = Properties()

    init {
        loadLocalProperties()
        loadEmbeddedProperties()
    }
    
    /**
     * Loads properties from local.properties file if it exists.
     * This file should not be committed to version control.
     */
    private fun loadLocalProperties() {
        try {
            // Try multiple locations where local.properties might be
            val possibleLocations = listOf(
                File("local.properties"),  // Current directory
                File("../local.properties"),  // Parent directory (when running from composeApp)
                File(System.getProperty("user.dir"), "local.properties"),
                File(System.getProperty("user.dir"), "../local.properties")
            )
            
            for (localPropertiesFile in possibleLocations) {
                if (localPropertiesFile.exists()) {
                    logger.debug(LogCategory.SYSTEM, "Loading local.properties", mapOf("path" to localPropertiesFile.absolutePath))
                    FileInputStream(localPropertiesFile).use { input ->
                        properties.load(input)
                    }
                    logger.debug(LogCategory.SYSTEM, "Loaded properties from local.properties", mapOf(
                        "count" to properties.size,
                        "hasSupabaseUrl" to properties.containsKey("SUPABASE_URL"),
                        "hasSupabaseAnonKey" to properties.containsKey("SUPABASE_ANON_KEY"),
                        "hasSupabaseFunctionUrl" to properties.containsKey("SUPABASE_FUNCTION_URL")
                    ))
                    return  // Stop after finding the first one
                }
            }

            logger.warn(LogCategory.SYSTEM, "local.properties not found in any of the expected locations")
        } catch (e: Exception) {
            // Silently ignore if file doesn't exist or can't be read
            logger.warn(LogCategory.SYSTEM, "Could not load local.properties", error = e)
        }
    }
    
    private fun loadEmbeddedProperties() {
        try {
            ConfigLoader::class.java.getResourceAsStream("/boss-build-config.properties")?.use { input ->
                embeddedProperties.load(input)
                logger.debug(LogCategory.SYSTEM, "Loaded embedded build config", mapOf(
                    "count" to embeddedProperties.size
                ))
            } ?: logger.debug(LogCategory.SYSTEM, "No embedded build config resource present")
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not load embedded build config", error = e)
        }
    }

    /**
     * Gets a configuration value from the following sources in order:
     * 1. System environment variable
     * 2. System property
     * 3. local.properties file
     * 4. Embedded build config (baked in at build time from CI secrets)
     * 5. Default value
     */
    fun getConfig(key: String, defaultValue: String? = null): String? {
        // First try environment variable
        System.getenv(key)?.let { return it }

        // Then try system property
        System.getProperty(key)?.let { return it }

        // Then try local.properties
        properties.getProperty(key)?.let { return it }

        // Then try the build-time embedded config
        embeddedProperties.getProperty(key)?.let { return it }

        // Finally return default
        return defaultValue
    }
}
