package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction

/** What the host knows about one plugin when a pack is planned. */
data class InstalledPlugin(
    val version: String,
    val enabled: Boolean,
)

/**
 * The store's answer about one plugin.
 *
 * [Unreachable] is kept apart from [NotPublished] because "could not ask" and "does not exist" need
 * different next steps, and reporting the first as the second is a mistake this codebase has made
 * before (see `StoreMissingDependencyInstaller.installFromStore`).
 */
sealed interface StoreListing {
    data class Published(
        val latest: String,
        val versions: Set<String>,
    ) : StoreListing

    data object NotPublished : StoreListing

    data class Unreachable(
        val reason: String,
    ) : StoreListing
}

/**
 * What the policy looked like for one rule subject when the pack was planned.
 *
 * Captured at the snapshot, never re-read at write time. A pack apply is a queued persistent
 * grant: the operator approves at T0, may reset the subject at T1, and the detached job writes at
 * T2. Reading the counter at T2 and comparing it with itself is a tautology that authorizes
 * exactly the write the reset was supposed to stop - and the "no rule exists" check cannot catch
 * it either, because a reset is what removed the rule.
 *
 * The tool's own counter and its provider's are kept apart rather than summed. The provider is
 * re-resolved when the rule is written, because the flow a pack exists for - install plugin X, then
 * allow X's tool - has no provider at snapshot time: the tool registers only once the install has
 * run. A summed stamp would read that newly visible provider's counter as a reset that never
 * happened; kept apart, the write is held to the tool's stamp and to the stamped provider's, and a
 * provider that appeared since is checked as it is now.
 *
 * @property revocation the subject's own reset counter at snapshot time: the tool's for a tool
 *   subject (excluding its provider's), the provider's for a provider subject
 * @property providerId the provider contributing a tool subject at snapshot time, so the plan is
 *   computed against the same provider-aware policy the invocation will be. Null for a provider
 *   subject, and for a tool no registered provider contributes yet.
 * @property providerRevocation [providerId]'s reset counter at snapshot time; 0 when it is null
 */
data class RuleStamp(
    val revocation: Long,
    val providerId: String?,
    val providerRevocation: Long = 0L,
)

/**
 * Every plugin an install of one pack row would actually bring in, named in full.
 *
 * A pack names one plugin; the store-backed installer installs that plugin's whole transitive
 * dependency closure. Consent has to cover what actually happens, and this repo is explicit about
 * it: "Transitive dependencies are resolved in the consent dialog, before installation ... Every id
 * stays readable; do not ellipsize consent to additional installs." So the closure is resolved when
 * the plan is computed, shown with the plan, and then applied *as computed* rather than recomputed
 * at install time, where it could have grown since the operator looked at it.
 *
 * @property order dependencies first, the named plugin last - the order the installer runs
 * @property alsoInstalls everything in [order] except the named plugin: the part consent must name
 * @property unresolved ids the store could not describe. Their install is still attempted, but they
 *   were not expanded, so the closure may be incomplete
 * @property cyclic two store rows point at each other
 * @property truncated the walk hit `PluginDependencyResolution.MAX_PLAN_SIZE` and stopped expanding,
 *   so the real closure is larger than this one
 */
data class InstallClosure(
    val order: List<String>,
    val alsoInstalls: List<String>,
    val unresolved: Set<String>,
    val cyclic: Boolean,
    val truncated: Boolean,
) {
    /** Whether the closure is known to be incomplete, so a caller can say so rather than imply it is exact. */
    val partial: Boolean get() = unresolved.isNotEmpty() || cyclic || truncated
}

/** Everything a plan is computed from, read once so the plan describes one consistent moment. */
data class PackSnapshot(
    val installed: Map<String, InstalledPlugin>,
    val store: Map<String, StoreListing>,
    val toolRules: Map<String, McpPolicyAction>,
    val providerRules: Map<String, McpPolicyAction>,
    val policyReadable: Boolean,
    /** Keyed by [PackRule.subject]. Empty for a caller that writes no rules, such as a plan-only run. */
    val stamps: Map<String, RuleStamp> = emptyMap(),
    /** Keyed by plugin id, for rows that would install. Empty when nothing needs installing. */
    val closures: Map<String, InstallClosure> = emptyMap(),
)

/** What applying a pack would do for one plugin row. */
enum class PluginStepKind {
    /** Installed, enabled, and at the requested version (or any version, when none was named). */
    SATISFIED,

    /** Installed at the requested version but disabled. */
    ENABLE,

    /** Not installed; the store has what was asked for. */
    INSTALL,

    /** Installed at a different version than the one named; the store has the named one. */
    CHANGE_VERSION,

    /** The store does not publish this plugin, or not at the named version. */
    UNAVAILABLE,

    /** The store could not be asked. Nothing is known either way. */
    STORE_UNREACHABLE,
}

data class PluginStep(
    val plugin: PackPlugin,
    val kind: PluginStepKind,
    /** The version the step installs, or null when it installs nothing. */
    val targetVersion: String?,
    val installedVersion: String?,
    val detail: String,
    /** Whether [targetVersion] is the store's current release. */
    val targetIsLatest: Boolean = false,
    /**
     * Everything this row would install, resolved at plan time. Null when the row installs nothing,
     * or when it pins a version, which the closure-resolving installer cannot fetch and which
     * therefore installs exactly the one plugin named.
     */
    val closure: InstallClosure? = null,
) {
    val needsWork: Boolean
        get() = kind == PluginStepKind.ENABLE || kind == PluginStepKind.INSTALL || kind == PluginStepKind.CHANGE_VERSION
    val blocked: Boolean get() = kind == PluginStepKind.UNAVAILABLE || kind == PluginStepKind.STORE_UNREACHABLE
}

/** What applying a pack would do for one policy rule. */
enum class RuleStepKind {
    /** No rule exists for this subject; the pack's rule would be added. */
    ADD,

    /** The operator already has exactly this rule. */
    ALREADY_SET,

    /** The operator has a different rule. A pack never overrides one, in either direction. */
    KEPT_EXISTING,

    /** The policy file is unreadable, so the host is failing closed and nothing is written. */
    POLICY_UNREADABLE,

    /**
     * No rule exists, so the pack's rule would be added - but the tool's provider is DENYed, so
     * adding it changes nothing: the invocation stays denied. Reported rather than written,
     * because a plan that says `added` for a rule the operator cannot use is a plan that lies.
     */
    INEFFECTIVE_PROVIDER_DENY,
}

data class RuleStep(
    val rule: PackRule,
    val kind: RuleStepKind,
    val existing: McpPolicyAction?,
)

/** The full, side-effect-free answer to "what would applying this pack do?". */
data class PackPlan(
    val pack: PluginPack,
    val plugins: List<PluginStep>,
    val rules: List<RuleStep>,
) {
    /**
     * True when the pack is already in effect: no plugin needs work and every rule is either set or
     * deliberately kept. A rule blocked by an unreadable policy is not in effect, so it is not.
     */
    val satisfied: Boolean
        get() =
            plugins.none { it.needsWork } &&
                rules.all { it.kind == RuleStepKind.ALREADY_SET || it.kind == RuleStepKind.KEPT_EXISTING }

    /** Required rows that cannot be satisfied; applying would leave the pack incomplete. */
    val requiredBlocked: List<PluginStep>
        get() = plugins.filter { it.blocked && !it.plugin.optional }
}

/**
 * Turns a pack and a [PackSnapshot] into a [PackPlan], without touching anything.
 *
 * Kept pure so every row kind is reachable from a test, and so `pack_plan` and `pack_apply` cannot
 * disagree: the applier re-plans against a fresh snapshot with this same function immediately before
 * acting, rather than trusting a plan the caller computed earlier.
 */
object PluginPackPlanner {
    fun plan(
        pack: PluginPack,
        snapshot: PackSnapshot,
    ): PackPlan =
        PackPlan(
            pack = pack,
            plugins = pack.plugins.map { pluginStep(it, snapshot) },
            rules = pack.rules.map { ruleStep(it, snapshot) },
        )

    private fun pluginStep(
        plugin: PackPlugin,
        snapshot: PackSnapshot,
    ): PluginStep {
        val installed = snapshot.installed[plugin.pluginId]
        val step = Stepper(plugin, installed)
        val wanted = plugin.version
        return when {
            installed != null && (wanted == null || wanted == installed.version) -> {
                if (installed.enabled) {
                    step.make(PluginStepKind.SATISFIED, "Installed at ${installed.version}.")
                } else {
                    step.make(PluginStepKind.ENABLE, "Installed at ${installed.version} but disabled.")
                }
            }

            else -> {
                storeStep(step, snapshot.store[plugin.pluginId]).withClosure(snapshot)
            }
        }
    }

    /**
     * Attaches the resolved closure to a row that installs the store's current release.
     *
     * Only that row: [PluginStepKind.CHANGE_VERSION] and a pinned install go through the
     * store-version installer, which installs the one plugin named and resolves nothing further.
     */
    private fun PluginStep.withClosure(snapshot: PackSnapshot): PluginStep =
        if (kind == PluginStepKind.INSTALL && targetIsLatest) {
            copy(closure = snapshot.closures[plugin.pluginId])
        } else {
            this
        }

    private fun storeStep(
        step: Stepper,
        listing: StoreListing?,
    ): PluginStep =
        when (listing) {
            null, StoreListing.NotPublished -> {
                step.make(PluginStepKind.UNAVAILABLE, "Not published in the plugin store.")
            }

            is StoreListing.Unreachable -> {
                step.make(PluginStepKind.STORE_UNREACHABLE, listing.reason)
            }

            is StoreListing.Published -> {
                publishedStep(step, listing)
            }
        }

    private fun publishedStep(
        step: Stepper,
        listing: StoreListing.Published,
    ): PluginStep {
        val wanted = step.plugin.version
        val target = wanted ?: listing.latest
        val installed = step.installed
        return when {
            wanted != null && wanted !in listing.versions -> {
                step.make(
                    PluginStepKind.UNAVAILABLE,
                    "The store does not publish version $wanted (latest is ${listing.latest}).",
                )
            }

            installed == null -> {
                step.make(PluginStepKind.INSTALL, "Install $target from the plugin store.", target, listing.latest)
            }

            else -> {
                step.make(
                    PluginStepKind.CHANGE_VERSION,
                    "Replace ${installed.version} with $target from the plugin store.",
                    target,
                    listing.latest,
                )
            }
        }
    }

    /** Builds the steps for one plugin row, so each call site names only what differs. */
    private class Stepper(
        val plugin: PackPlugin,
        val installed: InstalledPlugin?,
    ) {
        fun make(
            kind: PluginStepKind,
            detail: String,
            target: String? = null,
            latest: String? = null,
        ) = PluginStep(
            plugin = plugin,
            kind = kind,
            targetVersion = target,
            installedVersion = installed?.version,
            detail = detail,
            targetIsLatest = target != null && target == latest,
        )
    }

    private fun ruleStep(
        rule: PackRule,
        snapshot: PackSnapshot,
    ): RuleStep {
        if (!snapshot.policyReadable) return RuleStep(rule, RuleStepKind.POLICY_UNREADABLE, null)
        val existing =
            when (rule.scope) {
                PackRuleScope.TOOL -> snapshot.toolRules[rule.subject]
                PackRuleScope.PROVIDER -> snapshot.providerRules[rule.subject]
            }
        val kind =
            when {
                existing == rule.action -> RuleStepKind.ALREADY_SET
                existing != null -> RuleStepKind.KEPT_EXISTING
                deniedByProvider(rule, snapshot) -> RuleStepKind.INEFFECTIVE_PROVIDER_DENY
                else -> RuleStepKind.ADD
            }
        return RuleStep(rule, kind, existing)
    }

    /**
     * Whether a tool rule would be written into the shadow of its provider's DENY.
     *
     * A tool rule outranks a provider rule in `policyFor`, *except* that `setToolPolicyIfAbsent`
     * refuses to write one under a provider DENY at all - so the honest plan row is "this would
     * not take effect", not "added". Only asked for a TOOL subject: a provider rule's own subject
     * is the thing being denied, and that case is already `KEPT_EXISTING`.
     */
    private fun deniedByProvider(
        rule: PackRule,
        snapshot: PackSnapshot,
    ): Boolean {
        val providerId =
            if (rule.scope == PackRuleScope.TOOL) snapshot.stamps[rule.subject]?.providerId else null
        return providerId != null && snapshot.providerRules[providerId] == McpPolicyAction.DENY
    }
}
