package ai.rever.boss.components.plugin.panels.right_top

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.config.ConfigLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = BossLogger.forComponent("LLMSettingsManager")

/**
 * Desktop implementation of environment variable access
 * DMG apps don't inherit shell environment variables from .zshrc/.zprofile/.bashrc
 * This function implements multiple fallback mechanisms:
 * 1. ConfigLoader - checks env vars, system properties, and local.properties
 * 2. launchctl getenv - works for system-wide environment variables
 * 3. ~/.boss/env_vars file - manual fallback for DMG distributions
 */
actual fun getEnvironmentVariable(name: String): String? {
    // First try ConfigLoader which checks multiple sources including local.properties
    val configValue = ConfigLoader.getConfig(name)
    if (!configValue.isNullOrBlank()) {
        logger.debug(LogCategory.SYSTEM, "Found env var from ConfigLoader", mapOf("name" to name))
        return configValue
    }

    // Try launchctl getenv for system-wide environment variables (macOS)
    try {
        val process = ProcessBuilder("launchctl", "getenv", name).start()
        process.waitFor()
        if (process.exitValue() == 0) {
            val result = process.inputStream.bufferedReader().readText().trim()
            if (result.isNotBlank()) {
                logger.debug(LogCategory.SYSTEM, "Found env var from launchctl", mapOf("name" to name))
                return result
            }
        }
    } catch (e: Exception) {
        // launchctl might not be available or might fail - this is normal
    }

    // Fallback to reading from ~/.boss/env_vars file for DMG distributions
    try {
        val envFile = BossDirectories.resolve("env_vars")
        if (envFile.exists()) {
            val envVars = envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim() to parts[1].trim()
                    } else {
                        "" to ""
                    }
                }
            val result = envVars[name]
            if (!result.isNullOrBlank()) {
                logger.debug(LogCategory.SYSTEM, "Found env var from env_vars file", mapOf("name" to name))
                return result
            }
        }
    } catch (e: Exception) {
        logger.warn(LogCategory.SYSTEM, "Could not read environment variables file", error = e)
    }

    logger.debug(LogCategory.SYSTEM, "Env var not found in any location", mapOf("name" to name))
    return null
}

/**
 * Desktop implementation of LLM Settings Manager
 */
actual object LLMSettingsManager {
    private val settingsFile = BossDirectories.resolve("llm_settings.json")
    
    actual suspend fun loadSettings() {
        withContext(Dispatchers.IO) {
            try {
                if (settingsFile.exists()) {
                    val json = settingsFile.readText()
                    LLMSettings.loadFromJson(json)
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error loading LLM settings", error = e)
            }
        }
    }

    actual suspend fun saveSettings() {
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                settingsFile.writeText(LLMSettings.toJson())

                // Create env_vars template file if it doesn't exist
                createEnvVarsTemplateIfNeeded()
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error saving LLM settings", error = e)
            }
        }
    }
    
    private fun createEnvVarsTemplateIfNeeded() {
        try {
            val envFile = BossDirectories.resolve("env_vars")
            if (!envFile.exists()) {
                envFile.parentFile?.mkdirs()
                val template = """
                    # Environment variables for BOSS
                    #
                    # IMPORTANT: DMG applications on macOS don't inherit shell environment variables
                    # from .zshrc, .zprofile, .bashrc, etc. This file provides a workaround.
                    #
                    # ===== REQUIRED: Supabase Configuration =====
                    # These are REQUIRED for BOSS to function properly
                    # Uncomment and set your Supabase credentials:

                    # SUPABASE_URL=https://api.risaboss.com
                    # SUPABASE_ANON_KEY=your-supabase-anon-key-here

                    # ===== OPTIONAL: LLM API Keys =====
                    # Uncomment and set your LLM API keys below:

                    # ANTHROPIC_API_KEY=your-anthropic-api-key-here
                    # OPENAI_API_KEY=your-openai-api-key-here
                    # TOGETHER_API_KEY=your-together-api-key-here
                    # CUSTOM_LLM_API_KEY=your-custom-llm-api-key-here

                    # ===== Alternative Methods =====
                    # METHOD 2: Set system-wide environment variables (advanced)
                    # Run in terminal: launchctl setenv SUPABASE_URL "https://api.risaboss.com"
                    # Then restart BOSS app
                    #
                    # METHOD 3: Use the Settings UI in BOSS (for LLM keys only)
                    # Settings → LLM Providers → Enter keys manually

                    # Priority order: System.getenv() → launchctl getenv → this file → Settings UI
                """.trimIndent()

                envFile.writeText(template)
                logger.info(LogCategory.SYSTEM, "Created environment variables template", mapOf("path" to envFile.absolutePath))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not create env_vars template", error = e)
        }
    }
}
