package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.ApprovedArtifact
import ai.rever.boss.mcp.PreparedPackDisplayModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The outcome of writing one pack rule through the policy engine. */
enum class RuleWrite {
    ADDED,

    /** A rule appeared between planning and writing. The engine refused to replace it. */
    KEPT_EXISTING,

    POLICY_UNREADABLE,

    /** The engine accepted the rule but could not persist it. */
    NOT_SAVED,

    /**
     * The subject's provider is DENYed, so the engine refused a rule that would not have taken
     * effect anyway. Distinct from [KEPT_EXISTING]: nothing of the operator's was preserved, and
     * reporting this as added would claim a capability the pack does not get.
     */
    DENIED_BY_PROVIDER,

    /**
     * The tool's effective policy is DENY for a reason other than its provider's rule - today a
     * DENY default for its risk class - and the engine does not write a rule under an effective
     * DENY. Kept apart from [DENIED_BY_PROVIDER] so the result names the cause that was checked.
     */
    DENIED_BY_POLICY,

    /**
     * The operator reset this subject between approving the pack and this write running, so the
     * authorization the write is carrying is stale. Refused rather than applied.
     */
    REVOKED_SINCE_APPROVAL,
}

/**
 * Everything [PluginPackApplier] does to the host, behind one seam.
 *
 * Production wiring is `DesktopPluginPackEffects`, which reaches only install, enable and policy
 * paths the host already trusts. Tests substitute a fake, so every combination of success and
 * failure is reachable without a store, a plugin loader or a policy file.
 */
interface PluginPackEffects {
    /** A consistent view of plugin, store and policy state for [pack]. */
    suspend fun snapshot(pack: PluginPack): PackSnapshot

    /**
     * Put [version] of [pluginId] in place, from the store.
     *
     * @param latest whether [version] is the store's current release, which is the only version the
     *   dependency-resolving installer can fetch
     * @param approvedOrder exactly what to install, dependencies first, as resolved by this apply's
     *   own plan. Passed in rather than resolved here so the plan row, the install and the reported
     *   result describe one closure: re-resolving at install time would follow a closure that grew
     *   in between. This bounds the install to that plan, not to anything the operator saw - a
     *   direct `pack_apply` with no earlier `pack_plan` has no closure in its consent dialog. Empty
     *   means the one plugin named, which is the pinned-version path.
     */
    suspend fun install(
        pluginId: String,
        version: String,
        latest: Boolean,
        approvedOrder: List<String>,
        approvedArtifacts: List<ApprovedArtifact> = emptyList(),
    ): Result<Unit>

    /** Replace the installed build of [pluginId] with the store's [version]. */
    suspend fun changeVersion(
        pluginId: String,
        version: String,
    ): Result<Unit>

    suspend fun enable(pluginId: String): Result<Unit>

    /**
     * Add [rule] only if no rule exists for its subject. Never replaces an operator's rule.
     *
     * [stamp] is what the policy looked like for this subject when the pack was planned and shown
     * to the operator. It is passed in rather than read here so a reset made in between refuses
     * the write; see [RuleStamp].
     */
    fun addRule(
        rule: PackRule,
        stamp: RuleStamp,
    ): RuleWrite
}

enum class PluginResultKind { ALREADY_SATISFIED, DONE, FAILED, BLOCKED }

data class PluginResult(
    val step: PluginStep,
    val kind: PluginResultKind,
    val message: String,
)

enum class RuleResultKind {
    ADDED,
    ALREADY_SET,
    KEPT_EXISTING,
    POLICY_UNREADABLE,
    NOT_SAVED,

    /** The rule was not written because its provider is denied; writing it would change nothing. */
    DENIED_BY_PROVIDER,

    /** The rule was not written because the tool's effective policy is DENY for another reason. */
    DENIED_BY_POLICY,

    /** The operator reset this subject after approving the pack, so the write was refused. */
    REVOKED_SINCE_APPROVAL,
}

data class RuleResult(
    val step: RuleStep,
    val kind: RuleResultKind,
)

enum class PackApplyStatus {
    /** Nothing needed doing. */
    ALREADY_SATISFIED,

    /** Every required plugin is in place and every rule the pack could add was added. */
    APPLIED,

    /** Something changed, but a required plugin or a rule did not land. */
    PARTIAL,

    /** A required plugin or a rule did not land, and nothing changed. */
    FAILED,

    /** The plan changed between approval and job start; apply again for a new preview. */
    PLAN_CHANGED,
}

data class PackApplyResult(
    val packId: String,
    val status: PackApplyStatus,
    val plugins: List<PluginResult>,
    val rules: List<RuleResult>,
)

/**
 * An immutable, fully resolved pack application prepared and approved before job start.
 */
data class PreparedPackApply(
    val pack: PluginPack,
    val plan: PackPlan,
    val snapshot: PackSnapshot,
    val stamps: Map<String, RuleStamp>,
    val artifacts: List<ApprovedArtifact>,
    val displayModel: PreparedPackDisplayModel,
)

/**
 * Applies a pack: re-plans against a fresh snapshot, then performs each step in the pack's order.
 *
 * Deliberately not all-or-nothing. The host has no transaction spanning plugin installs, and
 * uninstalling a plugin the pack just installed could remove one the operator relied on in the
 * meantime. A row that fails is reported, later rows still run, and the status says [PackApplyStatus.PARTIAL]
 * so a caller can re-apply once the cause is fixed: every row is idempotent, so a second apply
 * does only what the first left undone.
 *
 * An optional plugin that cannot be satisfied is reported but does not make the pack partial.
 */
class PluginPackApplier(
    private val effects: PluginPackEffects,
) {
    suspend fun apply(
        prepared: PreparedPackApply,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): PackApplyResult {
        val freshSnapshot = effects.snapshot(prepared.pack)
        val freshPlan = PluginPackPlanner.plan(prepared.pack, freshSnapshot)
        if (isPlanChanged(prepared, freshSnapshot, freshPlan)) {
            return PackApplyResult(
                packId = prepared.pack.id,
                status = PackApplyStatus.PLAN_CHANGED,
                plugins = emptyList(),
                rules = emptyList(),
            )
        }
        val plan = prepared.plan
        val total = plan.plugins.size + plan.rules.size
        var done = 0

        val pluginResults =
            plan.plugins.map { step ->
                currentCoroutineContext().ensureActive()
                onProgress(done, total, step.plugin.pluginId)
                applyPlugin(step).also { done++ }
            }
        val ruleResults =
            plan.rules.map { step ->
                currentCoroutineContext().ensureActive()
                onProgress(done, total, step.rule.subject)
                applyRule(step, prepared.snapshot).also { done++ }
            }
        onProgress(done, total, "")
        return PackApplyResult(prepared.pack.id, statusOf(plan, pluginResults, ruleResults), pluginResults, ruleResults)
    }

    fun isPlanChanged(
        prepared: PreparedPackApply,
        freshSnapshot: PackSnapshot,
        freshPlan: PackPlan,
    ): Boolean {
        if (prepared.plan.plugins.size != freshPlan.plugins.size) return true
        for (i in prepared.plan.plugins.indices) {
            val p = prepared.plan.plugins[i]
            val f = freshPlan.plugins[i]
            if (p.plugin.pluginId != f.plugin.pluginId) return true
            if (p.kind != f.kind) return true
            if (p.targetVersion != f.targetVersion) return true
            if (p.installedVersion != f.installedVersion) return true
            if (p.targetSha256 != f.targetSha256) return true
            val pClosure = p.closure
            val fClosure = f.closure
            if ((pClosure == null) != (fClosure == null)) return true
            if (pClosure != null && fClosure != null) {
                if (pClosure.order != fClosure.order) return true
                if (pClosure.alsoInstalls != fClosure.alsoInstalls) return true
                if (pClosure.artifacts != fClosure.artifacts) return true
            }
        }

        if (prepared.plan.rules.size != freshPlan.rules.size) return true
        for (i in prepared.plan.rules.indices) {
            val pr = prepared.plan.rules[i]
            val fr = freshPlan.rules[i]
            if (pr.rule.scope != fr.rule.scope) return true
            if (pr.rule.subject != fr.rule.subject) return true
            if (pr.rule.action != fr.rule.action) return true
            if (pr.kind != fr.kind) return true
            if (pr.existing != fr.existing) return true
            val pStamp = prepared.stamps[pr.rule.subject]
            val fStamp = freshSnapshot.stamps[pr.rule.subject]
            if (pStamp != fStamp) return true
        }

        return false
    }

    suspend fun apply(
        pack: PluginPack,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): PackApplyResult {
        val snapshot = effects.snapshot(pack)
        val plan = PluginPackPlanner.plan(pack, snapshot)
        val total = plan.plugins.size + plan.rules.size
        var done = 0

        val pluginResults =
            plan.plugins.map { step ->
                currentCoroutineContext().ensureActive()
                onProgress(done, total, step.plugin.pluginId)
                applyPlugin(step).also { done++ }
            }
        val ruleResults =
            plan.rules.map { step ->
                currentCoroutineContext().ensureActive()
                onProgress(done, total, step.rule.subject)
                applyRule(step, snapshot).also { done++ }
            }
        onProgress(done, total, "")
        return PackApplyResult(pack.id, statusOf(plan, pluginResults, ruleResults), pluginResults, ruleResults)
    }

    private suspend fun applyPlugin(step: PluginStep): PluginResult {
        val pluginId = step.plugin.pluginId
        val attempt: Result<Unit>? =
            when (step.kind) {
                PluginStepKind.SATISFIED, PluginStepKind.UNAVAILABLE, PluginStepKind.STORE_UNREACHABLE -> {
                    null
                }

                PluginStepKind.ENABLE -> {
                    guarded { effects.enable(pluginId) }
                }

                PluginStepKind.INSTALL -> {
                    guarded {
                        effects.install(
                            pluginId,
                            checkNotNull(step.targetVersion),
                            step.targetIsLatest,
                            step.closure?.order.orEmpty(),
                            step.closure?.artifacts.orEmpty(),
                        )
                    }
                }

                PluginStepKind.CHANGE_VERSION -> {
                    guarded { effects.changeVersion(pluginId, checkNotNull(step.targetVersion)) }
                }
            }
        val failure = attempt?.exceptionOrNull()
        return when {
            step.kind == PluginStepKind.SATISFIED -> {
                PluginResult(step, PluginResultKind.ALREADY_SATISFIED, step.detail)
            }

            attempt == null -> {
                PluginResult(step, PluginResultKind.BLOCKED, step.detail)
            }

            failure == null -> {
                PluginResult(step, PluginResultKind.DONE, doneMessage(step))
            }

            else -> {
                PluginResult(step, PluginResultKind.FAILED, failure.message ?: "Failed without a reason.")
            }
        }
    }

    private fun applyRule(
        step: RuleStep,
        snapshot: PackSnapshot,
    ): RuleResult {
        val kind =
            when (step.kind) {
                RuleStepKind.ALREADY_SET -> {
                    RuleResultKind.ALREADY_SET
                }

                RuleStepKind.KEPT_EXISTING -> {
                    RuleResultKind.KEPT_EXISTING
                }

                RuleStepKind.POLICY_UNREADABLE -> {
                    RuleResultKind.POLICY_UNREADABLE
                }

                RuleStepKind.INEFFECTIVE_PROVIDER_DENY -> {
                    RuleResultKind.DENIED_BY_PROVIDER
                }

                RuleStepKind.ADD -> {
                    // The stamp the plan was computed from, not one read at write time: the
                    // whole point is to notice a reset that happened in between.
                    val stamp = snapshot.stamps[step.rule.subject] ?: RuleStamp(revocation = 0L, providerId = null)
                    when (effects.addRule(step.rule, stamp)) {
                        RuleWrite.ADDED -> RuleResultKind.ADDED
                        RuleWrite.KEPT_EXISTING -> RuleResultKind.KEPT_EXISTING
                        RuleWrite.POLICY_UNREADABLE -> RuleResultKind.POLICY_UNREADABLE
                        RuleWrite.NOT_SAVED -> RuleResultKind.NOT_SAVED
                        RuleWrite.DENIED_BY_PROVIDER -> RuleResultKind.DENIED_BY_PROVIDER
                        RuleWrite.DENIED_BY_POLICY -> RuleResultKind.DENIED_BY_POLICY
                        RuleWrite.REVOKED_SINCE_APPROVAL -> RuleResultKind.REVOKED_SINCE_APPROVAL
                    }
                }
            }
        return RuleResult(step, kind)
    }

    private fun statusOf(
        plan: PackPlan,
        plugins: List<PluginResult>,
        rules: List<RuleResult>,
    ): PackApplyStatus {
        if (plan.satisfied && plan.requiredBlocked.isEmpty()) {
            return PackApplyStatus.ALREADY_SATISFIED
        }
        val changed = plugins.any { it.kind == PluginResultKind.DONE } || rules.any { it.kind == RuleResultKind.ADDED }
        val missed =
            plugins.any { !it.step.plugin.optional && it.kind.missed() } || rules.any { it.kind.missed() }
        return when {
            !missed -> PackApplyStatus.APPLIED
            changed -> PackApplyStatus.PARTIAL
            else -> PackApplyStatus.FAILED
        }
    }

    /**
     * A rule that did not land for a reason the operator did not choose. An existing operator rule
     * is kept by design, so [RuleResultKind.KEPT_EXISTING] is not a miss - and neither is
     * [RuleResultKind.REVOKED_SINCE_APPROVAL], which is the operator's reset being honoured.
     *
     * [RuleResultKind.DENIED_BY_PROVIDER] and [RuleResultKind.DENIED_BY_POLICY] ARE misses: the
     * pack asked for something it did not get, and the status has to say the pack is not fully in
     * effect rather than report it applied.
     */
    private fun RuleResultKind.missed(): Boolean {
        val missed =
            setOf(
                RuleResultKind.POLICY_UNREADABLE,
                RuleResultKind.NOT_SAVED,
                RuleResultKind.DENIED_BY_PROVIDER,
                RuleResultKind.DENIED_BY_POLICY,
            )
        return this in missed
    }

    private fun PluginResultKind.missed(): Boolean {
        val missed = setOf(PluginResultKind.FAILED, PluginResultKind.BLOCKED)
        return this in missed
    }

    private fun doneMessage(step: PluginStep): String =
        when (step.kind) {
            PluginStepKind.ENABLE -> "Enabled."
            PluginStepKind.INSTALL -> installedMessage(step)
            PluginStepKind.CHANGE_VERSION -> "Changed ${step.installedVersion} to ${step.targetVersion}."
            else -> step.detail
        }

    /**
     * Names the dependencies that actually arrived, rather than reporting one install for what may
     * have been several. The plan said which ids these would be; this says they did.
     */
    private fun installedMessage(step: PluginStep): String {
        val also = step.closure?.alsoInstalls.orEmpty()
        if (also.isEmpty()) return "Installed ${step.targetVersion}."
        val noun = if (also.size == 1) "dependency" else "dependencies"
        return "Installed ${step.targetVersion}, with ${also.size} $noun: ${also.joinToString(", ")}."
    }

    /** A thrown failure becomes a row result; cancellation still stops the whole apply. */
    @Suppress("TooGenericExceptionCaught") // One broken installer must not abort the other rows.
    private suspend fun guarded(block: suspend () -> Result<Unit>): Result<Unit> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
