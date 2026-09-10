package ai.rever.boss.plugin.launchpad

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Canonical permission vocabulary supported by BossConsole host runtime.
 */
@Serializable
enum class PluginPermission(
    val identifier: String,
) {
    @SerialName("network")
    NETWORK("network"),

    @SerialName("filesystem")
    FILESYSTEM("filesystem"),

    @SerialName("terminal")
    TERMINAL("terminal"),

    @SerialName("browser")
    BROWSER("browser"),

    @SerialName("notifications")
    NOTIFICATIONS("notifications"),

    @SerialName("auth")
    AUTH("auth"),

    @SerialName("mcp")
    MCP("mcp"),

    @SerialName("editor")
    EDITOR("editor"),

    @SerialName("clipboard")
    CLIPBOARD("clipboard"),

    @SerialName("settings")
    SETTINGS("settings"),

    @SerialName("system")
    SYSTEM("system"),

    @SerialName("storage")
    STORAGE("storage"),
    ;

    companion object {
        private val lookup = entries.associateBy { it.identifier }

        fun isValid(name: String): Boolean = lookup.containsKey(name)

        fun fromIdentifier(name: String): PluginPermission? = lookup[name]
    }
}

/**
 * Manifest schema for BossConsole third-party plugins (matching plugin.json).
 */
@Serializable
data class PluginManifest(
    val id: String, // Regex: ^[a-z0-9-]+$
    val name: String,
    val version: String, // SemVer 2.0
    val description: String = "",
    val author: String = "",
    val minApiVersion: String, // Must be compatible with HostMeta.CURRENT_API_VERSION
    val entrypointClass: String, // Fully qualified class name
    val permissions: List<String> = emptyList(), // Validated against PluginPermission registry
    val mcpTools: List<PluginMcpToolDeclaration> = emptyList(),
)

@Serializable
data class PluginMcpToolDeclaration(
    val name: String, // Regex: ^mcp__[a-z0-9_-]+__[a-z0-9_-]+$
    val description: String,
    val adminOnly: Boolean = false,
)

@Serializable
data class ValidationCheck(
    val name: String,
    val passed: Boolean,
    val message: String,
)

@Serializable
data class ValidationResult(
    val isValid: Boolean,
    val checks: List<ValidationCheck>,
)

@Serializable
data class ValidationFailure(
    val checkName: String,
    val message: String,
)

@Serializable
data class ValidationReport(
    val success: Boolean,
    val targetPath: String,
    val checksPassed: Int,
    val totalChecks: Int,
    val failures: List<ValidationFailure> = emptyList(),
    val checks: List<ValidationCheck> = emptyList(),
)

object HostMeta {
    const val DEFAULT_API_VERSION = "1.0.88"

    val CURRENT_API_VERSION: String = resolveCurrentApiVersion()

    private fun resolveCurrentApiVersion(): String =
        System.getProperty("boss.plugin.api.version")
            ?: runCatching { ai.rever.boss.utils.VersionConstants.PLUGIN_API_VERSION }.getOrNull()
            ?: readPropertyFromResource("version.properties", "pluginApiVersion")
            ?: DEFAULT_API_VERSION

    private fun readPropertyFromFile(
        file: java.io.File,
        key: String,
    ): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val props = java.util.Properties()
            file.inputStream().use { props.load(it) }
            props.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun readPropertyFromResource(
        resourcePath: String,
        key: String,
    ): String? {
        val candidates =
            listOf(
                java.io.File(resourcePath),
                java.io.File("..", resourcePath),
                java.io.File("../..", resourcePath),
            )
        for (c in candidates) {
            val value = readPropertyFromFile(c, key)
            if (value != null) return value
        }
        return runCatching {
            HostMeta::class.java.classLoader.getResourceAsStream(resourcePath)?.use { stream ->
                val props = java.util.Properties()
                props.load(stream)
                props.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    val ALLOWED_PERMISSIONS: Set<String> =
        PluginPermission.entries.map { it.identifier }.toSet()
}

/**
 * SemVer 2.0 conformance and compatibility validator.
 */
object SemVerValidator {
    val SEMVER_REGEX =
        Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
                """(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?""" +
                """(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$""",
        )

    fun isValid(version: String): Boolean = SEMVER_REGEX.matches(version)

    fun isCompatible(
        requiredMin: String,
        hostVersion: String,
    ): Boolean {
        if (!isValid(requiredMin) || !isValid(hostVersion)) return false
        val reqParts =
            requiredMin
                .split('-')[0]
                .split('+')[0]
                .split('.')
                .map { it.toIntOrNull() ?: 0 }
        val hostParts =
            hostVersion
                .split('-')[0]
                .split('+')[0]
                .split('.')
                .map { it.toIntOrNull() ?: 0 }

        val rMajor = reqParts.getOrElse(0) { 0 }
        val rMinor = reqParts.getOrElse(1) { 0 }
        val rPatch = reqParts.getOrElse(2) { 0 }

        val hMajor = hostParts.getOrElse(0) { 0 }
        val hMinor = hostParts.getOrElse(1) { 0 }
        val hPatch = hostParts.getOrElse(2) { 0 }

        return when {
            hMajor != rMajor -> false
            hMinor > rMinor -> true
            hMinor == rMinor -> hPatch >= rPatch
            else -> false
        }
    }
}

/**
 * Execution context supplied to a running BossPlugin instance.
 */
data class PluginContext(
    val coroutineScope: CoroutineScope? = null,
    val hostContext: Any? = null,
)

/**
 * Common entrypoint interface for plugins developed via the launchpad.
 * Enforces clean lifecycle teardown and background job cancellation contract.
 */
interface BossPlugin {
    fun onStart(context: PluginContext)

    fun onStop() {}

    // Backward compatibility hooks
    fun initialize() {}

    fun shutdown() {}
}

val launchpadJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }
