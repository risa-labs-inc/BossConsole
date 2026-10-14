package ai.rever.boss.mcp.secrets

import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.sandbox.McpRiskLevel

/**
 * Raise a call's risk to reflect the secrets it would hand the tool.
 *
 * At least HIGH: whatever the tool is, it is about to receive a credential, and HIGH is the tier
 * the approval dialog already treats as needing a deliberate look. The reason names each secret
 * by website, username and field so the operator reads "github.com (deploy-bot) - password" in
 * the same line as the tool's own risk, never a value.
 */
fun McpRiskAssessment.withSecrets(descriptors: List<SecretDescriptor>): McpRiskAssessment {
    if (descriptors.isEmpty()) return this
    val noun = if (descriptors.size == 1) "secret" else "secrets"
    return McpRiskAssessment(
        level = maxOf(level, McpRiskLevel.HIGH),
        reason = "$reason; receives ${descriptors.size} $noun: " + descriptors.joinToString { it.display },
    )
}
