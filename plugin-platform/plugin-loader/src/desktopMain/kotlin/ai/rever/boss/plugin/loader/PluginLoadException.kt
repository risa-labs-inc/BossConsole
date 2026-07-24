package ai.rever.boss.plugin.loader

/**
 * Exception thrown when plugin loading fails.
 */
open class PluginLoadException(
    message: String,
    val pluginId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        /**
         * Message prefix of the refusal when the plugin is already loaded.
         * DefaultPlugin's directory scan matches this prefix to downgrade a
         * duplicate-jar retry to an info-level skip — reference it instead of
         * retyping the string so the two sides can't drift.
         */
        const val ALREADY_LOADED_PREFIX = "Plugin already loaded"
    }
}

/**
 * Exception thrown when a plugin manifest is invalid or missing.
 */
class PluginManifestException(
    message: String,
    pluginId: String? = null,
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)

/**
 * Exception thrown when a plugin's main class cannot be found or instantiated.
 */
class PluginClassException(
    message: String,
    pluginId: String? = null,
    val className: String? = null,
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)

/**
 * Exception thrown when a plugin cannot be unloaded.
 */
class PluginUnloadException(
    message: String,
    pluginId: String? = null,
    val reasons: List<String> = emptyList(),
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)

/**
 * Exception thrown when a plugin's API version is incompatible.
 */
class PluginApiVersionException(
    message: String,
    pluginId: String? = null,
    val requiredVersion: String? = null,
    val currentVersion: String? = null,
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)

/**
 * Exception thrown when a plugin JAR signature verification fails.
 */
class PluginSignatureException(
    message: String,
    pluginId: String? = null,
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)

/**
 * Exception thrown when a plugin requires a newer BOSS version.
 */
class PluginBossVersionException(
    message: String,
    pluginId: String? = null,
    val requiredVersion: String? = null,
    val currentVersion: String? = null,
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)

/**
 * Exception thrown when a plugin requires a newer boss-plugin-api layer
 * (manifest minApiVersion) than the one installed. Distinct from
 * [PluginApiVersionException] (the legacy coarse apiVersion check) and from
 * [PluginBossVersionException] (host app version): this gates on the version
 * of the runtime-updatable api jar resolved by the ApiClassLoader, turning
 * "class not found" validator noise into an actionable requirement.
 */
class PluginApiLevelException(
    message: String,
    pluginId: String? = null,
    val requiredVersion: String? = null,
    val installedVersion: String? = null,
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)

/**
 * Exception thrown when a plugin JAR has binary incompatibilities with the current API.
 */
class PluginBinaryIncompatibilityException(
    message: String,
    pluginId: String? = null,
    val manifest: ai.rever.boss.plugin.api.PluginManifest? = null,
    cause: Throwable? = null,
) : PluginLoadException(message, pluginId, cause)
