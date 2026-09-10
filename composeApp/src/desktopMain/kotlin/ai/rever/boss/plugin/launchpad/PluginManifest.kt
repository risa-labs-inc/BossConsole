package ai.rever.boss.plugin.launchpad

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Canonical permission vocabulary supported by BossConsole host runtime.
 */
enum class PluginPermission(
    val identifier: String,
) {
    NETWORK("network"),
    FILESYSTEM("filesystem"),
    TERMINAL("terminal"),
    BROWSER("browser"),
    NOTIFICATIONS("notifications"),
    AUTH("auth"),
    MCP("mcp"),
    EDITOR("editor"),
    CLIPBOARD("clipboard"),
    SETTINGS("settings"),
    SYSTEM("system"),
    STORAGE("storage"),
    ;

    companion object {
        private val lookup = entries.associateBy { it.identifier }

        fun isValid(name: String): Boolean = lookup.containsKey(name)

        fun fromIdentifier(name: String): PluginPermission? = lookup[name]
    }
}

/**
 * Manifest schema for BossConsole plugins (matching META-INF/boss-plugin/plugin.json).
 * Supports canonical fields and aliases for backward-compatibility.
 */
@Serializable
data class PluginManifest(
    @SerialName("pluginId")
    val pluginId: String = "",
    @SerialName("displayName")
    val displayName: String = "",
    val version: String = "1.0.0",
    @SerialName("apiVersion")
    val apiVersion: String = HostMeta.DEFAULT_API_VERSION,
    @SerialName("mainClass")
    val mainClass: String = "",
    val description: String = "",
    val author: String = "",
    val license: String = "Apache-2.0",
    val manifestVersion: Int = 1,
    @SerialName("systemPlugin")
    val systemPlugin: Boolean = false,
    @SerialName("canUnload")
    val canUnload: Boolean = true,
    @SerialName("permissions")
    val permissions: List<String> = emptyList(),
    val mcpTools: List<PluginMcpToolDeclaration> = emptyList(),
    // Backward-compatibility aliases for legacy JSON fields
    @SerialName("id")
    private val legacyId: String? = null,
    @SerialName("name")
    private val legacyName: String? = null,
    @SerialName("minApiVersion")
    private val legacyMinApiVersion: String? = null,
    @SerialName("entrypointClass")
    private val legacyEntrypointClass: String? = null,
    @SerialName("requiredPermissions")
    private val legacyRequiredPermissions: List<String>? = null,
) {
    val id: String get() = pluginId.ifBlank { legacyId.orEmpty() }
    val name: String get() = displayName.ifBlank { legacyName.orEmpty() }
    val minApiVersion: String get() = apiVersion.ifBlank { legacyMinApiVersion.orEmpty() }
    val entrypointClass: String get() = mainClass.ifBlank { legacyEntrypointClass.orEmpty() }
    val requiredPermissions: List<String> get() = permissions.ifEmpty { legacyRequiredPermissions.orEmpty() }

    /** Returns the canonical plugin ID. */
    fun resolvedPluginId(): String = pluginId.ifBlank { legacyId.orEmpty() }

    /** Returns the canonical display name. */
    fun resolvedDisplayName(): String = displayName.ifBlank { legacyName.orEmpty() }

    /** Returns the canonical entrypoint class. */
    fun resolvedMainClass(): String = mainClass.ifBlank { legacyEntrypointClass.orEmpty() }

    /** Returns the canonical API version. */
    fun resolvedApiVersion(): String = apiVersion.ifBlank { legacyMinApiVersion.orEmpty() }
}

@Serializable
data class PluginMcpToolDeclaration(
    val name: String,
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
            ?: DEFAULT_API_VERSION

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

val launchpadJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }
