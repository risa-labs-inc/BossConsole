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

    private val envVarsProperties = Properties()

    /**
     * Config baked into the app at build time by the generateEmbeddedConfig
     * Gradle task (from CI secrets or the developer's local.properties). This
     * is how packaged apps on end-user machines — which have no env vars or
     * local.properties — receive the JxBrowser license and Supabase settings.
     * The resource is generated into the build directory and never committed.
     */
    private val embeddedProperties = Properties()

    init {
        loadEnvVars()
        loadLocalProperties()
        loadEmbeddedProperties()
    }

    /**
     * Loads properties from the same BossDirectories env_vars file used by the settings writers if it exists.
     * This is where user settings like BOSS_MODE=KERNEL are saved.
     */
    private fun loadEnvVars() {
        try {
            val envVarsFile =
                ai.rever.boss.plugin.pathutils.BossDirectories
                    .resolve("env_vars")
            if (envVarsFile.exists()) {
                logger.debug(LogCategory.SYSTEM, "Loading env_vars", mapOf("path" to envVarsFile.absolutePath))
                envVarsProperties.putAll(parseEnvVars(envVarsFile.readLines(Charsets.UTF_8)))
                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded properties from env_vars",
                    mapOf("count" to envVarsProperties.size.toString()),
                )
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            // Widen past Exception: BossDirectories is another class, and an
            // ExceptionInInitializerError or NoClassDefFoundError from its own static init
            // would escape this catch, escape ConfigLoader's <clinit>, and make every later
            // getConfig call - including the ones before any window exists - throw
            // NoClassDefFoundError permanently. A missing saved preference must never take
            // the whole config loader down (BossConsole#450's review).
            logger.warn(LogCategory.SYSTEM, "Could not load env_vars", error = e)
        }
    }

    /**
     * Loads properties from local.properties file if it exists.
     * This file should not be committed to version control.
     */
    private fun loadLocalProperties() {
        try {
            // Try multiple locations where local.properties might be
            val possibleLocations =
                listOf(
                    File("local.properties"), // Current directory
                    File("../local.properties"), // Parent directory (when running from composeApp)
                    File(System.getProperty("user.dir"), "local.properties"),
                    File(System.getProperty("user.dir"), "../local.properties"),
                )

            for (localPropertiesFile in possibleLocations) {
                if (localPropertiesFile.exists()) {
                    logger.debug(LogCategory.SYSTEM, "Loading local.properties", mapOf("path" to localPropertiesFile.absolutePath))
                    FileInputStream(localPropertiesFile).use { input ->
                        properties.load(input)
                    }
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Loaded properties from local.properties",
                        mapOf(
                            "count" to properties.size,
                            "hasSupabaseUrl" to properties.containsKey("SUPABASE_URL"),
                            "hasSupabaseAnonKey" to properties.containsKey("SUPABASE_ANON_KEY"),
                            "hasSupabaseFunctionUrl" to properties.containsKey("SUPABASE_FUNCTION_URL"),
                        ),
                    )
                    return // Stop after finding the first one
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
                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded embedded build config",
                    mapOf(
                        "count" to embeddedProperties.size,
                    ),
                )
            } ?: logger.debug(LogCategory.SYSTEM, "No embedded build config resource present")
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not load embedded build config", error = e)
        }
    }

    /**
     * Gets a configuration value from the following sources in order:
     * 1. System environment variable
     * 2. System property
     * 3. env_vars file (BOSS_MODE only; plain unquoted KEY=value, no export syntax)
     * 4. local.properties file
     * 5. Embedded build config (baked in at build time from CI secrets)
     * 6. Default value
     */
    fun getConfig(
        key: String,
        defaultValue: String? = null,
    ): String? =
        resolve(
            key = key,
            defaultValue = defaultValue,
            envValue = System.getenv(key),
            sysPropValue = System.getProperty(key),
            envVarsProps = envVarsProperties,
            localProps = properties,
            embeddedProps = embeddedProperties,
        )

    /**
     * The precedence contract as a pure function, separated from the process
     * environment so tests can pin every tier (see ConfigLoaderTest).
     */
    @Suppress("LongParameterList")
    internal fun resolve(
        key: String,
        defaultValue: String?,
        envValue: String?,
        sysPropValue: String?,
        envVarsProps: Properties,
        localProps: Properties,
        embeddedProps: Properties,
    ): String? {
        val resolved =
            envValue.orNullIfBlank()
                ?: sysPropValue.orNullIfBlank()
                ?: envVarsProps.getProperty(key).takeIf { key == "BOSS_MODE" }.orNullIfBlank()
                ?: localProps.getProperty(key).orNullIfBlank()
                ?: embeddedProps.getProperty(key).orNullIfBlank()
                // NOT blank-filtered: a caller that passes "" as its default has said so explicitly,
                // unlike an exported variable that merely happens to be empty.
                ?: defaultValue
        return if (key == "BOSS_MODE") resolved?.trim()?.uppercase() else resolved
    }

    /**
     * A blank value at any tier is not a value, so the next tier gets its turn.
     *
     * `export BOSS_RENDERING_MODE=` produces an empty string, which is non-null, so it used to win
     * the chain and shadow every tier below it - including a setting the user had chosen in the
     * app. Silently: the value resolves to `""`, which every consumer then treats as unrecognised
     * and falls back on, so the effect is a platform default with nothing to explain it.
     *
     * That became worse once Settings started publishing to the system-property tier. The Settings
     * UI reports env ownership by asking whether the variable is set, and it now treats blank as
     * unset - so with a blank variable exported the dropdown showed the user's choice, the
     * command-line preview showed the switches for it, the startup log said it was applied, and
     * this function still handed the engine `""`. Fixing only the UI turned a confusing-but-honest
     * state into a silent lie; the fix belongs here, where the value is actually decided.
     *
     * Applied to all four sources rather than just the environment: a properties file line reading
     * `KEY=` is the same mistake with the same consequence.
     */
    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
