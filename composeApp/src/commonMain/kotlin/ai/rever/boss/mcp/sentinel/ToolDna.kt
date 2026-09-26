package ai.rever.boss.mcp.sentinel

import kotlinx.serialization.Serializable

/**
 * Trust states for an MCP tool monitored by MCP Sentinel.
 */
@Serializable
enum class SentinelTrustState {
    /** Tool has not been indexed or evaluated yet. */
    UNKNOWN,

    /** Tool is newly discovered and has no prior baseline. */
    NEW,

    /** Tool definition matches the established and approved baseline. */
    TRUSTED,

    /** Previously trusted tool definition has changed (fingerprint mismatch). */
    CHANGED,

    /** Security scanner detected suspicious patterns or anomalies in definition. */
    SUSPICIOUS,

    /** Changed or suspicious tool requires explicit operator review before use. */
    REVIEW_REQUIRED,

    /** Tool is explicitly blocked by policy or operator decision. */
    BLOCKED,
}

/**
 * Categories of changes detected between an old tool definition baseline and a new definition.
 */
@Serializable
enum class ChangeCategory {
    DESCRIPTION_CHANGED,
    INPUT_SCHEMA_CHANGED,
    OUTPUT_SCHEMA_CHANGED,
    PROVIDER_CHANGED,
    REQUIRED_PARAMETER_ADDED,
    OPTIONAL_PARAMETER_ADDED,
    PARAMETER_REMOVED,
    PARAMETER_TYPE_CHANGED,
    DESTRUCTIVE_PARAMETER_ADDED,
    CAPABILITY_EXPANSION,
    SUSPICIOUS_INSTRUCTION,
    INVISIBLE_UNICODE,
    CROSS_SERVER_COLLISION,
}

/**
 * Severity level for static security analysis findings.
 */
@Serializable
enum class FindingSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/**
 * A single security finding produced by the MCP Sentinel static content scanner.
 */
@Serializable
data class SecurityFinding(
    val ruleId: String,
    val severity: FindingSeverity,
    val location: String,
    val matchedText: String,
    val explanation: String,
)

/**
 * Finding for cross-provider tool shadowing or name collision.
 */
@Serializable
data class ShadowingFinding(
    val collidingProviderId: String,
    val toolName: String,
    val collisionType: String,
    val explanation: String,
)

/**
 * Canonical ToolDNA fingerprint representation of an MCP tool definition.
 */
@Serializable
data class ToolDnaFingerprint(
    val providerId: String,
    val toolName: String,
    val fingerprint: String,
    val algorithmVersion: String = CURRENT_ALGORITHM_VERSION,
    val canonicalDescription: String,
    val canonicalInputSchemaJson: String,
    val readOnly: Boolean,
    val requiresAdmin: Boolean,
) {
    companion object {
        const val CURRENT_ALGORITHM_VERSION: String = "v1"
    }
}

/**
 * Detail item describing a specific difference between two tool definitions.
 */
@Serializable
data class DiffDetail(
    val category: ChangeCategory,
    val fieldName: String,
    val oldValue: String,
    val newValue: String,
    val explanation: String,
)

/**
 * Structured semantic diff result comparing an old tool definition against a new definition.
 */
@Serializable
data class ToolDnaDiffResult(
    val hasChanges: Boolean,
    val categories: Set<ChangeCategory>,
    val diffDetails: List<DiffDetail>,
)

/**
 * Persistent baseline record for an MCP tool identity (providerId + toolName).
 */
@Serializable
data class ToolBaselineRecord(
    val providerId: String,
    val toolName: String,
    val canonicalFingerprint: String,
    val fingerprintVersion: String = ToolDnaFingerprint.CURRENT_ALGORITHM_VERSION,
    val firstSeenTimestamp: Long,
    val lastSeenTimestamp: Long,
    val trustState: SentinelTrustState,
    val lastAcceptedDescription: String,
    val lastAcceptedSchemaJson: String,
    val readOnly: Boolean = false,
    val requiresAdmin: Boolean = false,
    val fingerprintHistory: List<String> = emptyList(),
    val changeHistory: List<String> = emptyList(),
    val reasonForReevaluation: String? = null,
    val findings: List<SecurityFinding> = emptyList(),
    val userDecision: String? = null,
)

