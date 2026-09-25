package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.ApprovedArtifact
import ai.rever.boss.mcp.McpPolicyAction

/**
 * A scriptable [PluginPackEffects] that records every call.
 *
 * Installing, re-versioning or enabling a plugin updates [installed], and a written rule updates the
 * rule maps, so a second apply against the same fake sees the first one's effects, as the host would.
 */
internal class FakePackEffects(
    val installed: MutableMap<String, InstalledPlugin> = mutableMapOf(),
    val store: MutableMap<String, StoreListing> = mutableMapOf(),
    val toolRules: MutableMap<String, McpPolicyAction> = mutableMapOf(),
    val providerRules: MutableMap<String, McpPolicyAction> = mutableMapOf(),
    var policyReadable: Boolean = true,
) : PluginPackEffects {
    /** Reset counters and contributing providers the snapshot reports, keyed by rule subject. */
    val stamps = mutableMapOf<String, RuleStamp>()

    /** Bumped subjects, standing in for an operator reset between the snapshot and the write. */
    val revokedAfterSnapshot = mutableSetOf<String>()

    /** Dependency closures the snapshot reports, keyed by plugin id. */
    val closures = mutableMapOf<String, InstallClosure>()

    val calls = mutableListOf<String>()
    val installedArtifacts = mutableListOf<ApprovedArtifact>()
    var snapshots = 0
    val failures = mutableMapOf<String, Throwable>()
    val ruleWrites = mutableMapOf<String, RuleWrite>()
    var beforeSnapshot: suspend () -> Unit = {}
    var beforeInstall: suspend () -> Unit = {}

    override suspend fun snapshot(pack: PluginPack): PackSnapshot {
        beforeSnapshot()
        snapshots++
        return PackSnapshot(
            installed.toMap(),
            store.toMap(),
            toolRules.toMap(),
            providerRules.toMap(),
            policyReadable,
            pack.rules.associate { it.subject to (stamps[it.subject] ?: RuleStamp(0L, providerId = null)) },
            closures.toMap(),
        )
    }

    override suspend fun install(
        pluginId: String,
        version: String,
        latest: Boolean,
        approvedOrder: List<String>,
        approvedArtifacts: List<ApprovedArtifact>,
    ): Result<Unit> {
        beforeInstall()
        installedArtifacts += approvedArtifacts
        // The order is appended only when there is one, so rows with no closure keep reading the
        // way every existing assertion spells them.
        val order = if (approvedOrder.isEmpty()) "" else " order=${approvedOrder.joinToString("+")}"
        calls += "install $pluginId $version latest=$latest$order"
        return outcome(pluginId) {
            // Model the real installer: everything in the approved order arrives, not just the
            // named plugin, so a test can assert the closure was installed as approved.
            approvedOrder.ifEmpty { listOf(pluginId) }.forEach { id ->
                installed[id] = InstalledPlugin(if (id == pluginId) version else "1.0.0", enabled = true)
            }
        }
    }

    override suspend fun changeVersion(
        pluginId: String,
        version: String,
    ): Result<Unit> {
        calls += "changeVersion $pluginId $version"
        return outcome(pluginId) { installed[pluginId] = InstalledPlugin(version, enabled = true) }
    }

    override suspend fun enable(pluginId: String): Result<Unit> {
        calls += "enable $pluginId"
        return outcome(pluginId) { installed[pluginId] = installed.getValue(pluginId).copy(enabled = true) }
    }

    override fun addRule(
        rule: PackRule,
        stamp: RuleStamp,
    ): RuleWrite {
        calls += "addRule ${rule.scope} ${rule.subject} ${rule.action}"
        // The real engine compares the stamp it was handed with the subject's counter now; the
        // fake models the same thing by treating a subject reset since the snapshot as a bump.
        val deniedByProvider =
            rule.scope == PackRuleScope.TOOL && providerRules[stamp.providerId] == McpPolicyAction.DENY
        val map = if (rule.scope == PackRuleScope.TOOL) toolRules else providerRules
        val scripted = ruleWrites[rule.subject]
        return when {
            rule.subject in revokedAfterSnapshot -> {
                RuleWrite.REVOKED_SINCE_APPROVAL
            }

            deniedByProvider -> {
                RuleWrite.DENIED_BY_PROVIDER
            }

            scripted != null -> {
                scripted
            }

            rule.subject in map -> {
                RuleWrite.KEPT_EXISTING
            }

            else -> {
                map[rule.subject] = rule.action
                RuleWrite.ADDED
            }
        }
    }

    private fun outcome(
        pluginId: String,
        onSuccess: () -> Unit,
    ): Result<Unit> {
        failures[pluginId]?.let { throw it }
        onSuccess()
        return Result.success(Unit)
    }
}
