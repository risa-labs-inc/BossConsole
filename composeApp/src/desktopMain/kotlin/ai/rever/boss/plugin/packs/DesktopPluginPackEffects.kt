package ai.rever.boss.plugin.packs

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.components.plugin.PluginStoreVersionBridge
import ai.rever.boss.components.plugin.StoreVersionInstaller
import ai.rever.boss.components.plugin.StoreVersionRequest
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.McpPolicyFault
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.MissingDependencyReporter
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.shortFailureReason
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * [PluginPackEffects] over the host's existing, trusted paths. It adds no download, verification
 * or policy-writing logic of its own.
 *
 * - A store-latest install goes through the dependency installer that the missing-dependency
 *   prompt and the Home tool grid use, so a pack's plugin arrives with its dependencies, its
 *   signature sidecar and its manifest vetted exactly as a Toolbox install would.
 * - A change of version goes through [PluginStoreVersionBridge], the "install the store's version"
 *   path with its promote-and-rollback handling. A pinned release of a plugin not yet installed
 *   uses the same [StoreVersionInstaller] underneath, without the bridge's unload of a running build.
 * - Rules go through [McpPolicyEngine]'s add-if-absent writes, which refuse to replace any rule
 *   the operator already has.
 *
 * Plugin state is read from, and changes are made through, a live window's manager, since there is
 * no process-wide one. With no window open there is nothing to install into, and that is reported
 * rather than guessed around.
 */
class DesktopPluginPackEffects(
    private val manager: () -> DynamicPluginManager? = { DynamicPluginManager.anyActiveManager() },
    private val store: () -> PluginRepository? = { PluginStoreSetup.remoteRepository },
    private val policy: McpPolicyEngine = McpToolRegistryImpl.policyEngine,
) : PluginPackEffects {
    override suspend fun snapshot(pack: PluginPack): PackSnapshot {
        val states = manager()?.pluginStates?.value.orEmpty()
        val usable =
            PluginDependencyResolution.installedAndOnDisk(
                states = states,
                exists = { File(it).isFile },
                isIncompatible = { PluginCrashRegistry.isIncompatible(it) },
            )
        val installed =
            states
                .filterKeys { it in usable }
                .mapValues { (_, info) -> InstalledPlugin(info.manifest.version, info.enabled) }

        // Ask the store only about rows the installed state cannot already satisfy: a pack that is
        // fully in place must plan without a network round trip, and without failing offline.
        val needStore =
            pack.plugins
                .filter { plugin ->
                    val present = installed[plugin.pluginId]
                    present == null || (plugin.version != null && plugin.version != present.version)
                }.map { it.pluginId }
        val repository = store()
        val listings =
            needStore.associateWith { pluginId ->
                if (repository == null) {
                    StoreListing.Unreachable(STORE_UNAVAILABLE)
                } else {
                    listingFor(repository, pluginId)
                }
            }

        val config = policy.config.value
        return PackSnapshot(
            installed = installed,
            store = listings,
            toolRules = config.rules,
            providerRules = config.providerRules,
            policyReadable = policy.fault.value !is McpPolicyFault.PersistedPolicyUnreadable,
            stamps = pack.rules.associate { it.subject to stampFor(it) },
            closures = closuresFor(pack, installed),
        )
    }

    /**
     * What each not-yet-installed pack plugin would actually pull in, resolved now so the plan can
     * name it and the apply can be held to it.
     *
     * Only rows that would install the store's current release: a pinned version goes through
     * [StoreVersionInstaller], which installs the one plugin named. A row whose plugin is already
     * installed needs no closure. With no window there is no installer to ask, and the empty map
     * that results simply means the plan names no extra ids - the install itself already fails
     * with [noWindow].
     */
    private suspend fun closuresFor(
        pack: PluginPack,
        installed: Map<String, InstalledPlugin>,
    ): Map<String, InstallClosure> {
        val window = manager() ?: return emptyMap()
        val installer = MissingDependencyReporter.installerFor(window)
        return pack.plugins
            .filter { it.pluginId !in installed && it.version == null }
            .associate { plugin -> plugin.pluginId to closureFor(installer, plugin.pluginId) }
    }

    /**
     * The reset counter, and the contributing provider, for one rule subject *now* - i.e. at the
     * moment the plan the operator is shown is computed. [addRule] compares against this rather
     * than re-reading, so an operator reset between approval and the detached write refuses it.
     */
    private fun stampFor(rule: PackRule): RuleStamp =
        when (rule.scope) {
            PackRuleScope.TOOL -> {
                val providerId = providerFor(rule.subject)
                RuleStamp(policy.revocationVersion(rule.subject, providerId), providerId)
            }

            PackRuleScope.PROVIDER -> {
                RuleStamp(policy.providerRevocationVersion(rule.subject), providerId = null)
            }
        }

    // Guard clauses: no window, then the latest release, then the pinned-release path below.
    @Suppress("ReturnCount")
    override suspend fun install(
        pluginId: String,
        version: String,
        latest: Boolean,
        approvedOrder: List<String>,
    ): Result<Unit> {
        val window = manager() ?: return noWindow()
        if (latest) {
            val installer = MissingDependencyReporter.installerFor(window)
            // The order resolved when the plan was computed, never a fresh walk: re-resolving here
            // would install whatever the store's dependency rows say at this instant, which is not
            // what the operator approved. An empty order means the closure was never resolved (no
            // window at snapshot time), so fall back to the one plugin named rather than to a walk.
            return installer.installAll(approvedOrder.ifEmpty { listOf(pluginId) })
        }
        // A pinned older release of a plugin that is not installed. The version bridge cannot take
        // this: it always unloads the running build first, and unloading a plugin that was never
        // loaded fails with "Plugin not found". Nothing is running, so the unload is a no-op here,
        // and the download, vetting, promotion and record are still the store-version installer's.
        val repository = store() ?: return Result.failure(IllegalStateException(STORE_UNAVAILABLE))
        return StoreVersionInstaller(pluginDir = { PluginStoreSetup.getPluginDir() })
            .install(
                store = repository,
                request =
                    StoreVersionRequest(
                        pluginId = pluginId,
                        version = version,
                        sourceUrl = null,
                        runningJarPath = null,
                        hasLiveInstance = false,
                    ),
                unload = { Result.success(Unit) },
                load = { path -> window.installPlugin(path, enabled = true).map { it.state == PluginState.LOADED } },
            ).map { }
    }

    override suspend fun changeVersion(
        pluginId: String,
        version: String,
    ): Result<Unit> {
        val window = manager() ?: return noWindow()
        return PluginStoreVersionBridge
            .installStoreVersion(pluginId, version, sourceUrl = null, manager = window)
            .map { }
    }

    override suspend fun enable(pluginId: String): Result<Unit> {
        val window = manager() ?: return noWindow()
        return window.enablePlugin(pluginId)
    }

    override fun addRule(
        rule: PackRule,
        stamp: RuleStamp,
    ): RuleWrite {
        // Refuse before asking the engine when the reset already happened: the engine's own
        // stale-stamp check reports a plain Refused, which is indistinguishable from "a rule
        // appeared", and the operator needs to be told their reset is what stopped this.
        val current =
            when (rule.scope) {
                PackRuleScope.TOOL -> policy.revocationVersion(rule.subject, stamp.providerId)
                PackRuleScope.PROVIDER -> policy.providerRevocationVersion(rule.subject)
            }
        if (current != stamp.revocation) return RuleWrite.REVOKED_SINCE_APPROVAL

        val outcome =
            when (rule.scope) {
                PackRuleScope.TOOL -> {
                    policy.setToolPolicyIfAbsent(rule.subject, rule.action, stamp.revocation, stamp.providerId)
                }

                PackRuleScope.PROVIDER -> {
                    policy.setProviderPolicyIfAbsent(rule.subject, rule.action, stamp.revocation)
                }
            }
        return when (outcome) {
            McpProactivePolicyOutcome.Saved -> RuleWrite.ADDED
            McpProactivePolicyOutcome.Refused -> RuleWrite.KEPT_EXISTING
            McpProactivePolicyOutcome.Denied -> RuleWrite.DENIED_BY_PROVIDER
            McpProactivePolicyOutcome.PolicyUnreadable -> RuleWrite.POLICY_UNREADABLE
            is McpProactivePolicyOutcome.Failed -> RuleWrite.NOT_SAVED
        }
    }

    private suspend fun listingFor(
        repository: PluginRepository,
        pluginId: String,
    ): StoreListing {
        val lookup = flatten { repository.getPlugin(pluginId) }
        val failure = lookup.exceptionOrNull()
        val latest = lookup.getOrNull()?.version?.takeIf { it.isNotBlank() }
        return when {
            failure != null -> {
                StoreListing.Unreachable("Could not reach the plugin store: ${shortFailureReason(failure)}")
            }

            latest == null -> {
                StoreListing.NotPublished
            }

            else -> {
                // The version list is a second call that can fail on its own. The latest release is
                // still known then, so a pack asking for it is not blocked by the list being unreadable.
                val versions = flatten { repository.getPluginVersions(pluginId) }.getOrNull().orEmpty()
                StoreListing.Published(latest, (versions.map { it.version } + latest).toSet())
            }
        }
    }

    /**
     * Both failure shapes as one [Result], cancellation excepted.
     *
     * The repository returns `Result.failure` for store errors but may also throw, and a caller's
     * cancellation must propagate rather than be reported as the store being unreachable.
     */
    @Suppress("TooGenericExceptionCaught") // Either failure shape of a third-party repository.
    private suspend fun <T> flatten(call: suspend () -> Result<T>): Result<T> {
        val result =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result
    }

    private companion object {
        const val STORE_UNAVAILABLE = "The plugin store is not available. Check your connection and sign-in."
    }

    private fun noWindow(): Result<Unit> =
        Result.failure(
            IllegalStateException("No BOSS window is open to install into. Open a window and apply the pack again."),
        )
}

/**
 * The provider contributing [toolName], or null when no registered tool matches.
 *
 * Needed so a tool rule is checked against the same provider-aware policy the invocation will be:
 * `policyFor(tool, null)` cannot see a provider-scoped DENY, so without this a pack could report a
 * rule `added` while every call to that tool stayed denied.
 *
 * File level rather than a method, to stay under the host's per-class function limit; it reads a
 * global registry and needs nothing from an instance.
 */
private fun providerFor(toolName: String): String? =
    McpToolRegistryImpl.tools.value
        .firstOrNull { it.definition.name == toolName }
        ?.providerId

/**
 * One plugin's closure, or the plugin alone when the walk could not be completed.
 *
 * The walk reads the store, so it fails the way every other store read here does rather than taking
 * the whole plan down with it: the row still plans and installs, and the closure simply says it is
 * not known to be complete, which is the honest answer and the one that stops a caller reading an
 * empty `alsoInstalls` as "nothing else will be installed".
 *
 * File level for the same reason as [providerFor]: it needs only its two arguments.
 */
@Suppress("TooGenericExceptionCaught") // A store walk fails in both shapes; neither may abort the plan.
private suspend fun closureFor(
    installer: MissingDependencyInstaller,
    pluginId: String,
): InstallClosure {
    val plan =
        try {
            installer.planFor(pluginId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return InstallClosure(
                order = listOf(pluginId),
                alsoInstalls = emptyList(),
                unresolved = setOf(pluginId),
                cyclic = false,
                truncated = false,
            )
        }
    return InstallClosure(
        order = plan.order,
        alsoInstalls = plan.order.filterNot { it == pluginId },
        unresolved = plan.unresolved,
        cyclic = plan.cyclic,
        truncated = plan.truncated,
    )
}
