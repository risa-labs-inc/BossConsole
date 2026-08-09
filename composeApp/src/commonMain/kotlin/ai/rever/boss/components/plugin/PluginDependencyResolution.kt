package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginDependency
import ai.rever.boss.plugin.api.PluginManifest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

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
     * Dependencies of [manifest] that are not in [installedPluginIds].
     *
     * Returns optional dependencies too. They are worth telling someone about - an optional
     * dependency is how a plugin says "this feature needs that plugin", which is exactly
     * the thing a user would want to know at install time - but they are flagged so the
     * prompt can be a suggestion rather than a warning.
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
            .distinctBy { dependency -> dependency.pluginId }
            .map { dependency -> manifest.toMissing(dependency) }

    /**
     * What to tell the user, given whatever name we managed to resolve for the dependency.
     *
     * [resolvedName] is the dependency's store display name when the lookup succeeded and its
     * plugin id when it did not - a raw id is poor but honest, and better than a prompt that
     * waits on the network before it can say anything.
     */
    fun MissingPluginDependency.description(resolvedName: String): String =
        if (optional) {
            "$dependentDisplayName works without $resolvedName, but some of its features need it."
        } else {
            "$dependentDisplayName needs $resolvedName, which is not installed."
        }

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
 * bound to the `DynamicPluginManager` that reported - one per window - and installing through
 * a different window's manager would load the plugin into the wrong one.
 */
data class MissingDependencyPrompt(
    val missing: MissingPluginDependency,
    val installer: MissingDependencyInstaller,
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
    /**
     * A channel, not a `SharedFlow`, because exactly one window must ask.
     *
     * A broadcast would put an identical dialog in front of every open window, and each of
     * them could start the same install - so this is not a smaller version of the right
     * thing, it is the wrong delivery semantics. Channel receive hands each prompt to a
     * single collector.
     *
     * Buffered and dropping, so reporting never suspends the installer and a prompt raised
     * before any window exists is asked as soon as one appears rather than lost.
     */
    private val prompts =
        Channel<MissingDependencyPrompt>(
            capacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val missingDependencies = prompts.receiveAsFlow()

    /** Non-suspending on purpose, so the install path never waits on a UI. */
    fun report(prompt: MissingDependencyPrompt) {
        prompts.trySend(prompt)
    }
}

/** The bus the host actually uses. */
object PluginDependencyEventBus : PluginDependencyBus()
