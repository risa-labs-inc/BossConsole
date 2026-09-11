package ai.rever.boss.components.plugin

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap

/** What the user has to do about a missing tab-type plugin. */
enum class MissingHandlerRemedy {
    /** The plugin is not on the machine. Download and load it. */
    INSTALL,

    /**
     * The plugin is installed and switched off.
     *
     * A separate remedy, not a detail: offering to *install* something already on
     * disk is both wrong and unactionable - `installPlugin` refuses with "Plugin
     * already loaded" or reinstalls the same jar and changes nothing, because
     * what is stopping it is the `installed.json` enabled flag. This is the same
     * distinction `install-time-dependency-prompt` records as having broken the
     * dependency prompt when the two halves disagreed about "installed".
     */
    ENABLE,
}

/**
 * BOSS was asked to open something and the plugin that renders it is not running.
 *
 * @property purpose what the user was trying to do, in their terms ("Opening
 *   README.md"). Carried rather than derived so the dialog can name the file the
 *   OS handed over, which is the thing that makes the prompt make sense.
 * @property capability what the plugin provides, from [TabTypePlugins.describe].
 * @property tabTypeId the type that is missing, for logging and dedupe.
 * @property pluginId the plugin to install or enable.
 * @property remedy which of the two this is.
 */
data class MissingHandlerPlugin(
    val purpose: String,
    val capability: String,
    val tabTypeId: String,
    val pluginId: String,
    val remedy: MissingHandlerRemedy,
)

/**
 * A missing tab-type plugin plus the means to fix it.
 *
 * The installer travels with the event for the reason [MissingDependencyPrompt]
 * documents: it is bound to the `DynamicPluginManager` that is missing the
 * plugin, one per window, so the fix always lands in the manager that needs it.
 *
 * @property resolve performs the remedy. A suspending lambda rather than an
 *   interface because the two remedies have nothing in common at the call site -
 *   one downloads a jar through [MissingDependencyInstaller], the other flips a
 *   flag through `DynamicPluginManager.enablePlugin` - and the dialog only needs
 *   "do the thing, tell me if it worked".
 * @property displayName resolves the plugin's store name, so the dialog can say
 *   "Code Editor Tab" rather than `ai.rever.boss.plugin.dynamic.editortab`.
 *   Returns null when the store cannot be reached, and the dialog then shows the
 *   id: a prompt that appears late or not at all is worse than an ugly one.
 */
data class MissingHandlerPluginPrompt(
    val missing: MissingHandlerPlugin,
    val resolve: suspend () -> Result<Unit>,
    val displayName: suspend () -> String?,
)

/**
 * Carries a missing tab-type plugin from wherever an open was attempted to
 * whichever window can ask about it.
 *
 * Was written as a near-copy of [PluginDependencyBus], which then used a bounded `Channel` for
 * the same three reasons this one still does, below. [PluginDependencyBus] was later redesigned
 * (BossConsole#465) into a claim-based map registry precisely because that channel could
 * silently drop an already-admitted prompt once enough other, genuinely distinct prompts filled
 * its buffer - so the two are no longer the same shape, and this KDoc no longer describes its
 * sibling, only itself. The two also answer different questions - "a plugin you installed needs
 * another plugin" against "the thing you just double-clicked needs a plugin" - and the text of
 * the dependency dialog is specifically about a manifest declaration, which is reason enough on
 * its own not to reuse it directly. This bus's own reasons for a `Channel` here:
 *
 * - a **`Channel`, not a `SharedFlow`**: a broadcast would put an identical
 *   dialog in front of every open window and let each of them start the same
 *   install.
 * - **buffered**, so raising never suspends the code that was trying to open a
 *   file, and a prompt raised before any window exists is asked as soon as one
 *   appears rather than lost.
 * - **`trySend` on a suspend-on-overflow channel**, so a full buffer refuses the
 *   newest and says so, instead of a `DROP_OLDEST` that silently discards the one
 *   the user is part-way through answering.
 */
open class MissingHandlerPluginBus {
    private val logger = BossLogger.forComponent("MissingHandlerPluginBus")

    /**
     * Plugins the user has already declined, for this process only.
     *
     * Keyed by plugin id, not by the file that triggered it: declining once for
     * `README.md` is an answer about the editor plugin, and asking again for the
     * next file would be the same question. Not persisted, for the reason
     * [PluginDependencyBus] gives: "not now" is an answer about now.
     */
    private val declined = ConcurrentHashMap.newKeySet<String>()

    /** Plugins with a prompt already waiting, so the buffer is not spent on duplicates. */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /**
     * Buffer of 2, deliberately smaller than the dependency bus's 4.
     *
     * There are only four tab types the host opens, and a user selecting twelve
     * files in Finder with no editor plugin produces one useful question, not
     * twelve. `queued` collapses those to one; the buffer only has to hold a
     * second *different* plugin (a link and a file arriving together).
     */
    private val prompts = Channel<MissingHandlerPluginPrompt>(capacity = 2)

    val missingHandlers =
        prompts
            .receiveAsFlow()
            // Freed on the way out rather than when the dialog is answered: the
            // collector may decline to show a prompt whose plugin has since
            // appeared, and a slot held for a prompt nobody will show is what
            // `queued` exists to avoid.
            .onEach { prompt -> queued.remove(prompt.missing.pluginId) }

    fun decline(pluginId: String) {
        declined.add(pluginId)
    }

    fun wasDeclined(pluginId: String): Boolean = pluginId in declined

    /** Non-suspending, so the open path never waits on a UI. */
    fun report(prompt: MissingHandlerPluginPrompt) {
        val pluginId = prompt.missing.pluginId
        if (wasDeclined(pluginId) || !queued.add(pluginId)) return
        if (prompts.trySend(prompt).isFailure) {
            queued.remove(pluginId)
            logger.warn(
                LogCategory.UI,
                "Dropped a missing-handler prompt",
                mapOf("plugin" to pluginId, "tabType" to prompt.missing.tabTypeId),
            )
        }
    }

    /** Test seam: forget this session's declines. */
    internal fun resetForTest() {
        declined.clear()
        queued.clear()
    }
}

/** The bus the host actually uses. */
object MissingHandlerPluginEventBus : MissingHandlerPluginBus()
