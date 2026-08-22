package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginDependency
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * A dependency a just-installed plugin declares but which is not present.
 *
 * Carries the dependent's display name so the prompt can say "Flow needs the AI Gateway"
 * rather than naming two plugin ids at a user.
 */
data class MissingPluginDependency(
    val dependentPluginId: String,
    val dependentDisplayName: String,
    val missingPluginId: String,
    /** True when the dependent declared it `optional`, i.e. it works without it. */
    val optional: Boolean,
) {
    /**
     * What to tell the user, given whatever name we managed to resolve for the dependency.
     *
     * [resolvedName] is the dependency's store display name when the lookup succeeded and its
     * plugin id when it did not - a raw id is poor but honest, and better than a prompt that
     * waits on the network before it can say anything.
     */
    fun description(resolvedName: String): String =
        if (optional) {
            "$dependentDisplayName works without $resolvedName, but some of its features need it."
        } else {
            "$dependentDisplayName needs $resolvedName, which is not installed."
        }
}

/**
 * A loaded plugin that declares a dependency on the one being unloaded.
 *
 * The host's own shape, distinct from the api's `DependentPluginInfo`: this one carries
 * [loadPriority] so the restart runs in the same order a load would, and it is what the
 * commonMain dialog and the pure tests speak. The delegate converts at the api boundary.
 */
data class DependentPlugin(
    val pluginId: String,
    val displayName: String,
    /** True when the dependent declared it `optional`, i.e. it works without it. */
    val optional: Boolean,
    val loadPriority: Int = 100,
)

/**
 * Works out which of a plugin's declared dependencies are absent.
 *
 * Pure, so the interesting rules are testable without a plugin loader: manifest
 * `dependencies` were previously read in exactly one place -
 * `DynamicPluginManager.checkCanUnload` - which meant installing a plugin whose dependency
 * was missing produced no signal at all. The user found out when a feature silently did
 * nothing.
 */
object PluginDependencyResolution {
    /**
     * Plugin ids a manifest can name but which must never be offered for install.
     *
     * Both are system components that happen to live in the store under a plugin id, and both
     * have a guard somewhere else that this path would route around:
     *
     * - the **microkernel runtime** is a classpath dependency for out-of-process child JVMs.
     *   `PluginLoaderDelegateImpl.loadPlugin` refuses it outright, and `DefaultPlugin` skips it
     *   on directory scan - so it is never in `pluginStates`, which would make it look missing
     *   to every manifest that names it. Pushing it through `installPlugin` trips the
     *   binary-compatibility validator on core JDK classes.
     * - the **api plugin** is the shared `ApiClassLoader` layer. Installing a newer one is an
     *   unload-everything / swap / reload-everything hot swap, which is not a thing to start
     *   from a two-button dialog about something else.
     *
     * The api id is a literal because `ApiClassLoader.API_PLUGIN_ID` lives in `desktopMain`
     * while this resolver is common; `PluginDependencyResolutionTest` pins the pair.
     */
    val NOT_USER_INSTALLABLE = setOf(MicrokernelRuntime.PLUGIN_ID, "ai.rever.boss.plugin.api")

    /**
     * The plugins that count as installed: an entry whose jar is still on disk.
     *
     * One function for both halves of the prompt - the reporter's "what is missing" set and the
     * Install button's guard - because when they disagreed the feature broke in a way no test
     * saw. `installPlugin` registers a DISABLED entry for a binary-incompatible plugin, and the
     * installer deletes the jar it just rejected; a definition of "installed" that looked only
     * at entries then treated that plugin as present, so every *later* dependent of it reported
     * nothing at all, silently re-creating the problem this feature exists to remove.
     *
     * [isIncompatible] qualifies the **jar** clause only, and must not override LOADED.
     * `PluginCrashRegistry` keeps the flag until something clears it, and the only caller of
     * `clearIncompatible` is the re-enable path - not install or update. So a plugin that failed
     * registration, was updated from the Toolbox's own "Plugin Incompatible" prompt and is now
     * running still carries the flag; excluding it would report a *running* plugin as missing,
     * and taking Install would then discard the download and claim it "did not start".
     *
     * The jar clause needs [isIncompatible] to stay honest. There are **two** binary
     * incompatibility paths in `installPlugin` and only one of them fails: the load-time one
     * returns `Result.failure`, but the *registration-time* one force-unloads the plugin and
     * returns `Result.success` with `state = DISABLED` and the jar still on disk. Without this
     * filter that jar makes a plugin which was unloaded and will not run count as installed - so
     * the Install button would report success for it, and every other dependent of it would go
     * unprompted.
     *
     * @param exists injected so this is testable without a filesystem; the host passes
     *   `File(it).isFile`
     * @param isIncompatible whether the host has recorded this plugin as binary-incompatible;
     *   the host passes `PluginCrashRegistry.isIncompatible`. Being last, it captures a trailing
     *   lambda, so pass both by name - [exists] deliberately has no default, which turns a call
     *   that meant to write `exists` as a trailing lambda into a compile error rather than a
     *   silent swap of the two.
     */
    fun installedAndOnDisk(
        states: Map<String, DynamicPluginInfo>,
        exists: (jarPath: String) -> Boolean,
        isIncompatible: (pluginId: String) -> Boolean = { false },
    ): Set<String> =
        states
            // A plugin that is LOADED counts however its jar path reads. The manager's
            // `jarPath` is not repointed when a file moves - `PluginJarReconciler` rewrites
            // `installed.json` and the updater writes a version-named file and deletes the old
            // one - so a running plugin can hold a path that no longer exists. Without this it
            // would look absent, the prompt would fire, and Install would fail with "Plugin
            // already loaded", which is both wrong and unactionable. The case this predicate
            // exists for is unaffected: a binary-incompatible entry is DISABLED, never LOADED.
            .filterValues { info ->
                info.state == PluginState.LOADED ||
                    (exists(info.jarPath) && !isIncompatible(info.manifest.pluginId))
            }.keys

    /**
     * Dependencies of [manifest] that are not in [installedPluginIds].
     *
     * Returns optional dependencies too. They are worth telling someone about - an optional
     * dependency is how a plugin says "this feature needs that plugin", which is exactly
     * the thing a user would want to know at install time - but they are flagged so the
     * prompt can be a suggestion rather than a warning.
     *
     * **Presence is by id only.** `PluginDependency.version` is ignored, so a plugin needing
     * 2.x is satisfied by 1.x being installed. That matches the one other reader of these
     * declarations (`DynamicPluginManager.checkCanUnload`); resolving version ranges would be a
     * different feature, and a prompt that offered to "install" something already present at
     * the wrong version could not do anything useful about it anyway.
     */
    fun missingFor(
        manifest: PluginManifest,
        installedPluginIds: Set<String>,
    ): List<MissingPluginDependency> =
        manifest.dependencies
            .filterNot { dependency -> dependency.pluginId in installedPluginIds }
            // A plugin depending on itself is a manifest mistake, not something to offer to
            // install: the prompt would ask the user to install what they just installed.
            .filterNot { dependency -> dependency.pluginId == manifest.pluginId }
            .filterNot { dependency -> dependency.pluginId in NOT_USER_INSTALLABLE }
            // A blank id is a manifest typo, and it reads as one: "Flow works without , but
            // some of its features need it", then " was not found in the plugin store."
            .filterNot { dependency -> dependency.pluginId.isBlank() }
            .groupBy { dependency -> dependency.pluginId }
            // One prompt per plugin, and when a manifest declares the same dependency twice
            // with different flags the stricter one wins: calling something "Recommended"
            // that the plugin actually requires is the worse way to be wrong.
            .map { (_, declarations) -> declarations.minBy { it.optional } }
            .map { dependency -> manifest.toMissing(dependency) }

    /**
     * Every loaded, enabled plugin that declares a dependency on [pluginId] - optional ones
     * included - for the prompt that offers to restart them.
     *
     * [blockingDependentsOf] is this list narrowed to the hard declarations, so the veto and the
     * prompt cannot disagree about who depends on what. They deliberately answer *different*
     * questions on top of the same set:
     *
     * - the **veto** counts only `optional: false`, because that is what the manifest means by
     *   "cannot work without it";
     * - the **prompt** counts optional dependents too, because that is the case it exists for.
     *   All three of the AI Gateway's consumers declare it optional, so its update already
     *   succeeded and left them running against a classloader that had closed underneath them.
     *   "Works without it" describes a cold start, not a handle resolved before the swap.
     *
     * Self-declarations and DISABLED dependents drop out for the reasons given on
     * [blockingDependentsOf]; [isDisabled] has no default there and none here either.
     */
    fun dependentsOf(
        pluginId: String,
        loadedManifests: List<PluginManifest>,
        isDisabled: (pluginId: String) -> Boolean,
    ): List<DependentPlugin> =
        loadedManifests
            .filterNot { manifest -> manifest.pluginId == pluginId }
            .mapNotNull { manifest ->
                manifest.dependencies
                    .filter { dependency -> dependency.pluginId == pluginId }
                    // A manifest declaring the same dependency twice with different flags is
                    // treated by its stricter declaration, as `missingFor` does: calling
                    // something optional that the plugin actually requires is the worse way to
                    // be wrong, and here it would also drop the dependent from the veto.
                    .minByOrNull { dependency -> dependency.optional }
                    ?.let { dependency ->
                        DependentPlugin(
                            pluginId = manifest.pluginId,
                            displayName = manifest.displayName,
                            optional = dependency.optional,
                            loadPriority = manifest.loadPriority,
                        )
                    }
            }.filterNot { dependent -> isDisabled(dependent.pluginId) }

    /**
     * Display names of the [loadedManifests] that would actually break if [pluginId] were
     * unloaded, for `DynamicPluginManager.checkCanUnload`.
     *
     * **Only non-optional declarations count.** `optional: true` is how a manifest says the
     * plugin works without the dependency and merely lights up extra features when it is
     * there - [missingFor] reports exactly that, and [MissingPluginDependency.description]
     * words it as "works without X, but some of its features need it". Vetoing an unload on
     * that same declaration contradicts the manifest, and it is not a small contradiction:
     * jupyter-notebook, flow-tab and llmrpa all declare the AI Gateway optional, so with the
     * optional filter missing the gateway could not be unloaded while any one of them was
     * loaded. The Toolbox's update path is an uninstall followed by a reinstall, so what the
     * user saw was an Update button that did nothing at all.
     *
     * **A disabled dependent does not count.** `DynamicPluginManager.disablePlugin` unregisters
     * the tracking context and flips the state to `DISABLED`, but never calls
     * `pluginLoader.unloadPlugin` - so a disabled plugin is still in `getLoadedPlugins()`. It
     * would otherwise veto on behalf of something that cannot break, because it is not running:
     * the same shape as the optional case above, and the reason shown would name a plugin the
     * user had already turned off.
     *
     * **Presence is by id only.** `PluginDependency.version` is ignored, matching [missingFor]:
     * a dependent needing 2.x is "satisfied" by 1.x here too, so this never refuses an unload
     * over a version range it has no way to act on.
     *
     * @param isDisabled whether a dependent is switched off; the host asks its `_pluginStates`.
     *   Deliberately **has no default**, so a new call site has to answer rather than silently
     *   getting the over-vetoing behaviour this exists to remove. It is asked only about
     *   plugins that already declare a hard dependency, and it **fails closed**: the host
     *   answers false for a state it does not recognise or does not track, so an unfamiliar
     *   state still vetoes. `PluginState` has more members than LOADED and DISABLED
     *   (REGISTERED, INITIALIZING, ...), and treating "not exactly LOADED" as "not running"
     *   would drop real dependents.
     */
    fun blockingDependentsOf(
        pluginId: String,
        loadedManifests: List<PluginManifest>,
        isDisabled: (pluginId: String) -> Boolean,
    ): List<String> =
        // Expressed on top of [dependentsOf] rather than repeating its filters, so the plugins
        // the prompt offers to restart are always a superset of the ones the veto names. When
        // those two drifted before, the prompt could describe a set the unload had already
        // refused over - and the self-declaration and DISABLED rules would have to be right
        // twice.
        dependentsOf(pluginId, loadedManifests, isDisabled)
            .filterNot { dependent -> dependent.optional }
            .map { dependent -> dependent.displayName }

    private fun PluginManifest.toMissing(dependency: PluginDependency) =
        MissingPluginDependency(
            dependentPluginId = pluginId,
            dependentDisplayName = displayName,
            missingPluginId = dependency.pluginId,
            optional = dependency.optional,
        )
}

/**
 * Installs a plugin the host knows only by id, for the prompt's Install button.
 *
 * An interface so the prompt can be built and tested without a store, a network or a plugin
 * loader; the real one lives in `desktopMain` because resolving a version and downloading a
 * jar is desktop-side work.
 */
interface MissingDependencyInstaller {
    /**
     * Whether the plugin is present and usable *now*.
     *
     * A prompt can be raised and answered later, so what was missing at report time may not
     * be by the time it reaches a window - two dependents of one missing plugin each raise a
     * prompt, and installing for the first satisfies the second. Without this the second
     * dialog would state something untrue and reinstall what is already there.
     *
     * "Usable" and not merely "the manager has an entry": a load that fails as binary
     * incompatible *registers* a disabled entry, and this installer deletes the jar it just
     * rejected. Answering only "is there an entry" would then make Retry close the dialog
     * reporting success with nothing installed, and silence the prompt for every other
     * dependent of the same plugin.
     */
    fun isInstalled(pluginId: String): Boolean

    /**
     * The dependency's display name in the store, or null when it cannot be resolved.
     *
     * Separate from [install] so the prompt can appear immediately with the id and improve
     * itself when the lookup lands, rather than blocking on the network to say anything.
     */
    suspend fun displayNameFor(pluginId: String): String?

    /** Downloads and loads the plugin. The message on failure is shown to the user. */
    suspend fun install(pluginId: String): Result<Unit>
}

/**
 * A missing dependency plus the means to fix it.
 *
 * The installer travels with the event rather than sitting in a global holder because it is
 * bound to the `DynamicPluginManager` that reported - one per window - so Install always loads
 * the dependency into the manager that was actually missing it, whichever window asks.
 *
 * **Known limitation, with more than one window open.** Delivery is to whichever window
 * collects first, which is not necessarily the one that reported. The install is still correct
 * (the jar lands on disk and loads into the reporting window's manager), but the person who
 * answered may see nothing change in the window they were looking at until the next launch.
 * Routing back to the reporting window would need the prompt to carry a window id and the
 * collector to be able to decline one without consuming it - a claim registry rather than a
 * channel. Not built, because a single window is the overwhelmingly common case and the
 * consequence is cosmetic.
 */
data class MissingDependencyPrompt(
    val missing: MissingPluginDependency,
    val installer: MissingDependencyInstaller,
    /**
     * True when a person asked for this directly, by pressing a control that needs the plugin.
     *
     * Such a request is not subject to [PluginDependencyBus.wasDeclined]. That set exists to stop
     * *automatic* prompts recurring - three consumers of one optional gateway asking one question
     * three times - and it is the right answer for those. It is the wrong answer for a button:
     * someone who dismissed the offer and then pressed the control again is asking again, and
     * answering a click with silence is the exact failure this feature exists to remove.
     *
     * Carried on the prompt rather than passed to [PluginDependencyBus.report], because the
     * decline set is consulted **twice** on the way to a dialog - once when reporting and again
     * by the window that collects, which re-checks because a prompt can be answered between the
     * two. A report-time flag suppresses only the first, so the request still died silently at
     * the collector. The intent has to travel with the prompt to survive that gap.
     */
    val userInitiated: Boolean = false,
)

/**
 * Carries a missing dependency from the install path to whichever window can ask about it.
 *
 * An event bus rather than a direct call because the install runs in
 * `PluginLoaderDelegateImpl`, which has no window, no Compose scope and no idea whether a
 * UI exists at all - the same reason `TerminalLinkEventBus` exists, though not the same
 * delivery (see [prompts]).
 *
 * **Only user-initiated installs emit here.** Startup restore and the api hot-swap's
 * reload-all both go through `DynamicPluginManager.installPlugin` directly, and a prompt on
 * those paths would be a dialog per plugin on every launch.
 *
 * A class with a singleton subclass rather than a bare object, so a test can hold its own bus.
 * The shared buffer otherwise carries prompts between tests, which is the same coupling two
 * windows would have.
 */
open class PluginDependencyBus {
    private val logger = BossLogger.forComponent("PluginDependencyBus")

    /**
     * Missing plugins the user has already declined, for this process only.
     *
     * All three gateway consumers declare it optional, so without this, declining for
     * jupyter-notebook means being asked again for llmrpa and again for flow-tab. The
     * `isInstalled` re-check only covers the case where it was installed in between.
     *
     * Not persisted, deliberately: "Not now" is an answer about now, and a decision that
     * outlived the session would leave no way to be asked again short of editing a file.
     */
    private val declined =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /**
     * Missing plugins with a prompt already waiting, so the buffer is not spent on duplicates.
     *
     * Three consumers of one gateway report three prompts and the collector discards two on
     * arrival - but they occupy slots first.
     */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /**
     * Records a dismissal so the same question stops being asked this session.
     *
     * Keyed differently by kind, because the two buttons ask different things. "Not now" on an
     * *optional* dependency is an answer about that plugin - three consumers declare the gateway
     * optional, and being asked three times for one answer is the thing this prevents. "Skip" on
     * a *required* one is an answer about this dependent only: another plugin that hard-requires
     * the same thing is a different question, and silencing it would be strictly worse than the
     * optional case.
     */
    fun decline(missing: MissingPluginDependency) {
        declined.add(declineKey(missing))
    }

    /** Whether this exact question was already declined this session. */
    fun wasDeclined(missing: MissingPluginDependency): Boolean = declineKey(missing) in declined

    private fun declineKey(missing: MissingPluginDependency) =
        if (missing.optional) {
            missing.missingPluginId
        } else {
            "${missing.dependentPluginId}->${missing.missingPluginId}"
        }

    /**
     * A channel, not a `SharedFlow`, because exactly one window must ask.
     *
     * A broadcast would put an identical dialog in front of every open window, and each of
     * them could start the same install - so this is not a smaller version of the right
     * thing, it is the wrong delivery semantics. Channel receive hands each prompt to a
     * single collector.
     *
     * Buffered, so reporting never suspends the installer and a prompt raised before any
     * window exists is asked as soon as one appears rather than lost.
     *
     * Left on the default suspend-on-overflow policy and only ever written with `trySend`:
     * a `DROP_OLDEST` channel always accepts, so the overflow would be invisible, and the
     * oldest prompt is the one the user is most likely part-way through answering. A full
     * buffer refuses the newest and says so instead.
     */
    private val prompts = Channel<MissingDependencyPrompt>(capacity = 4)

    val missingDependencies =
        prompts
            .receiveAsFlow()
            // Freed on the way out, not when the dialog is answered: the collector may decline to
            // show this one (already installed, or declined since), and a slot held for a prompt
            // nobody will ever show is what `queued` exists to avoid.
            .onEach { prompt -> queued.remove(declineKey(prompt.missing)) }

    /**
     * Non-suspending on purpose, so the install path never waits on a UI.
     *
     * Filters here and not only in the collector, because a prompt the collector is certain to
     * discard still costs a buffer slot on the way through - and with four slots, that can be
     * what refuses a different dependency which could have been shown.
     */
    fun report(prompt: MissingDependencyPrompt) {
        val missingPluginId = prompt.missing.missingPluginId
        // Keyed like a decline, not by bare id: an optional prompt already waiting would
        // otherwise swallow a *required* one for the same plugin, and declining the optional
        // dialog would then silence the plugin that actually requires it.
        //
        // A user-initiated prompt skips the decline check only, never the duplicate check, so a
        // second click while the dialog is already up does not stack another copy of it. See
        // [MissingDependencyPrompt.userInitiated].
        val silenced = !prompt.userInitiated && wasDeclined(prompt.missing)
        if (silenced || !queued.add(declineKey(prompt.missing))) return
        if (prompts.trySend(prompt).isFailure) {
            queued.remove(declineKey(prompt.missing))
            // DROP_OLDEST is silent, and a prompt that never appears is indistinguishable
            // from a feature that does not exist. Say so somewhere.
            logger.warn(
                LogCategory.SYSTEM,
                "Dropped a missing-dependency prompt",
                mapOf(
                    "dependent" to prompt.missing.dependentPluginId,
                    "missing" to prompt.missing.missingPluginId,
                ),
            )
        }
    }
}

/**
 * Whether a prompt that reached a window should be put on screen.
 *
 * The window re-checks both conditions rather than trusting the report, because a prompt can be
 * answered in the gap between the two: two dependents of one missing plugin each raise one, and
 * installing for the first satisfies the second, whose dialog would otherwise claim something
 * untrue and reinstall what is already loaded.
 *
 * Extracted from the collector so the rule is testable. It was not, and that mattered: the first
 * version of the user-initiated exemption was applied at the report only, the collector went on
 * silencing the same prompt, and dismissing the offer once left the control dead for the session.
 * A test written against a *copy* of this expression passed the whole time.
 *
 * @param present the dependency is installed and usable now, so there is nothing to offer
 * @param declined this exact question was dismissed earlier this session
 */
fun shouldShowMissingDependency(
    prompt: MissingDependencyPrompt,
    present: Boolean,
    declined: Boolean,
): Boolean = !present && (prompt.userInitiated || !declined)

/** The bus the host actually uses. */
object PluginDependencyEventBus : PluginDependencyBus()
