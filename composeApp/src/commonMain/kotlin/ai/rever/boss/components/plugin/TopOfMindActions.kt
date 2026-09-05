package ai.rever.boss.components.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("TopOfMindActions")

/**
 * The Top of Mind plugin, as the two ids a caller outside it needs.
 *
 * `PanelId.pluginId` is deliberately the api DEFAULT rather than [TOP_OF_MIND_PLUGIN_ID]: a panel
 * is registered under the id the plugin hands `PanelRegistry`, nothing rewrites it, and Top of
 * Mind's `PanelInfo` constructs its `PanelId` without naming a plugin. The two ids are different
 * strings for that reason and neither can be swapped for the other.
 *
 * `defaultOrder` is not part of panel matching - the event handler compares `panelId` and
 * `pluginId` only - so this carries the plugin's real 5 for honesty rather than for matching.
 *
 * **Not [PanelIds.TOP_OF_MIND].** That constant is `PanelId("topofmind", 2)`, a different id
 * string from the one the plugin actually registers, so opening by it matches nothing at all.
 */
private val TOP_OF_MIND_PANEL = PanelId(panelId = "top-of-mind", defaultOrder = 5)

/** Deep-link handler id of the Top of Mind plugin, which is its plugin id by convention. */
private const val TOP_OF_MIND_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.topofmind"

/** What the plugin calls itself, for a sentence that names it rather than its id. */
private const val TOP_OF_MIND_NAME = "Top of Mind"

/**
 * The actions Top of Mind answers. Wire names shared with the plugin, so they are constants on
 * both sides and neither can rename one alone.
 */
private const val ACTION_OPEN_WORKSPACE_PICKER = "open-workspace-picker"
private const val ACTION_OPEN_QUICK_SWITCHER = "open-quick-switcher"

/**
 * Open Top of Mind in [windowId] and raise its workspace picker. Returns whether anything was
 * there to answer.
 *
 * **Asks first, opens second, and that order is deliberate.** `dispatch` answers synchronously
 * whether Top of Mind is loaded at all, which is the one failure the caller can still do something
 * about: it returns false and `WorkspaceButton` falls back to the menu this click replaced.
 * Opening the panel cannot answer that - it is an event with no reply. The plugin holds a request
 * that arrives before its panel is on screen, so asking early costs nothing.
 *
 * What this canNOT report is a plugin that is loaded but whose panel never composes. That path
 * ends in a click that appears to do nothing, and the honest fix for it is on the panel-open side
 * rather than here.
 */
internal fun openTopOfMindWorkspacePicker(
    windowId: String,
    scope: CoroutineScope,
): Boolean = openTopOfMind(ACTION_OPEN_WORKSPACE_PICKER, windowId, scope)

/**
 * Open Top of Mind in [windowId] and raise its quick switcher - what Ctrl+Space does.
 *
 * The switcher itself lives in the plugin, so this is the whole of the host's part: ask, then open
 * the panel. When nothing answers, [offerTopOfMind] says why and offers the fix rather than
 * falling back to a host dialog - there is exactly one switcher now, and quietly showing a
 * different one would hide the fact that the plugin is missing.
 *
 * **The host does not depend on the plugin, and this is what that costs.** api -> host is forced
 * (`ActiveTabsProvider` is `@HostImplemented`) and plugin -> host is forced (`minBossVersion`), so
 * a host that required the plugin would close a cycle. It does not: it builds and runs without
 * it, declines to do the job itself, and points at the fix.
 */
internal fun openTopOfMindQuickSwitcher(
    windowId: String,
    scope: CoroutineScope,
) {
    if (openTopOfMind(ACTION_OPEN_QUICK_SWITCHER, windowId, scope)) return
    offerTopOfMind()
}

/** Dispatch [action] at the plugin and, if it answered, bring its panel up in [windowId]. */
private fun openTopOfMind(
    action: String,
    windowId: String,
    scope: CoroutineScope,
): Boolean {
    val accepted =
        DeepLinkActionRegistryImpl.dispatch(
            handlerId = TOP_OF_MIND_PLUGIN_ID,
            action = action,
            params = emptyMap(),
        )
    if (!accepted) return false
    scope.launch { PanelEventBus.openPanel(TOP_OF_MIND_PANEL, windowId) }
    return true
}

/**
 * Say why Top of Mind did not answer, and offer whatever would actually fix it.
 *
 * The reasons and their ORDER come from [pluginSectionAbsence], which is the same decision the
 * settings sections make and is where the two traps in it are written down - permissions asked
 * first because an inaccessible plugin is recorded DISABLED too, and incompatible/disabled asked
 * before installed because `MissingPluginOffer.isInstalled` counts both as installed. A second
 * copy of that ordering here is a second chance to get it wrong.
 *
 * `servesNoPanel = true` is passed unconditionally, and reads here as "serves no such action":
 * this is only reached after `dispatch` has already returned false, so a plugin that is present,
 * enabled and running is by definition one whose handler did not know the action - an older build.
 * The ordering above means it is only consulted once the states that outrank it are ruled out.
 *
 * Three outcomes, one per thing the user can do:
 *
 * - **not installed** - the host's real store-backed offer ([MissingPluginOffer.offerIfMissing]),
 *   which resolves the id against the store, shows what it will download and installs it. It is
 *   `userInitiated`, so a keypress re-asks even after a previous dismissal.
 * - **installed but switched off** - Enable, through the host's one enable-offering dialog. An
 *   Install button cannot fix this: `installPlugin` would refuse or rewrite the same jar.
 * - **anything else** - a status line naming the state, because there is no button that would
 *   help: an update, an administrator, or simply waiting for startup to finish.
 */
private fun offerTopOfMind() {
    val manager = DynamicPluginManager.anyActiveManager()
    val missingPermissions =
        manager
            ?.getInaccessiblePlugins()
            ?.firstOrNull { it.pluginId == TOP_OF_MIND_PLUGIN_ID }
            ?.missingPermissions
            .orEmpty()
    val absence =
        pluginSectionAbsence(
            installed = MissingPluginOffer.isInstalled(TOP_OF_MIND_PLUGIN_ID),
            state =
                manager
                    ?.pluginStates
                    ?.value
                    ?.get(TOP_OF_MIND_PLUGIN_ID)
                    ?.state,
            isIncompatible = PluginCrashRegistry.isIncompatible(TOP_OF_MIND_PLUGIN_ID),
            missingPermissions = missingPermissions,
            servesNoPanel = true,
        )

    logger.info(
        LogCategory.UI,
        "Quick switcher asked for Top of Mind and nothing answered",
        mapOf("absence" to absence.name),
    )

    val offered =
        when (absence) {
            PluginSectionAbsence.NOT_INSTALLED -> {
                MissingPluginOffer.offerIfMissing(TOP_OF_MIND_PLUGIN_ID, "Quick switcher")
            }

            PluginSectionAbsence.DISABLED -> {
                offerToEnableTopOfMind(manager)
            }

            else -> {
                false
            }
        }
    // Not an else branch: `offerIfMissing` fails open when there is no manager or no injected
    // installer factory, and the enable offer refuses a plugin the user already declined this
    // session. A keypress that produced neither a dialog nor a word is the failure this whole
    // function exists to end, so the sentence is the fallback for both.
    if (!offered) {
        StatusMessageManager.showMessage(topOfMindAbsenceMessage(absence, missingPermissions), durationMs = 5_000)
    }
}

/**
 * Raise the host's Enable dialog for Top of Mind, returning whether it was raised.
 *
 * `MissingHandlerPluginEventBus` is the host's ONLY dialog with an Enable verb - `MissingDependency
 * Dialog` can just install - and standing up a second one is what the "no second install path"
 * rule is about. What it was built for is a missing tab-type plugin, so two of its fields are read
 * here in the nearest sense they have:
 *
 * - `tabTypeId` carries the ACTION name. It feeds the bus's log lines and the collector's
 *   "has it registered since?" re-check, which compares against `TabRegistry`'s type strings - no
 *   tab type is called `open-quick-switcher`, so that re-check can never suppress this prompt.
 * - `capability` is unused by the ENABLE copy, which reads "$purpose needs $name, which is
 *   installed but switched off".
 *
 * **`wasDeclined` is asked first, and answering false is the point.** The bus remembers a "Not
 * now" for the session and would silently drop every later report, where `MissingPluginOffer` is
 * `userInitiated` and re-asks. Refusing here sends the caller to the status line instead, so the
 * keypress still says something.
 *
 * Enabling re-dispatches rather than leaving the user to press the key again: the plugin registers
 * its handler during `register()`, so by the time `enablePlugin` returns success there is
 * something to answer.
 */
private fun offerToEnableTopOfMind(manager: DynamicPluginManager?): Boolean {
    if (manager == null || MissingHandlerPluginEventBus.wasDeclined(TOP_OF_MIND_PLUGIN_ID)) return false
    val installer = MissingPluginOffer.installerFactory?.invoke(manager)
    MissingHandlerPluginEventBus.report(
        MissingHandlerPluginPrompt(
            missing =
                MissingHandlerPlugin(
                    purpose = "The quick switcher",
                    capability = "the tab switcher",
                    tabTypeId = ACTION_OPEN_QUICK_SWITCHER,
                    pluginId = TOP_OF_MIND_PLUGIN_ID,
                    remedy = MissingHandlerRemedy.ENABLE,
                ),
            resolve = {
                manager.enablePlugin(TOP_OF_MIND_PLUGIN_ID).onSuccess {
                    // The panel is not opened here: `openTopOfMind` needs a window id and this
                    // lambda runs from whichever window collected the prompt, which is the same
                    // ambiguity `MissingDependencyPrompt` records. Dispatching alone still lands,
                    // because the plugin holds a request that arrives before its panel composes.
                    DeepLinkActionRegistryImpl.dispatch(
                        handlerId = TOP_OF_MIND_PLUGIN_ID,
                        action = ACTION_OPEN_QUICK_SWITCHER,
                        params = emptyMap(),
                    )
                }
            },
            displayName = { installer?.displayNameFor(TOP_OF_MIND_PLUGIN_ID) },
        ),
    )
    // The bus drops a report for a plugin already queued, so this is "we asked", not "a dialog
    // appeared" - the same caveat the settings notice records about `offerIfMissing`.
    return true
}

/**
 * One sentence per state that has no button behind it.
 *
 * A `when` over the enum rather than a chain of `==`, for the reason [pluginSectionMessage] gives:
 * a member added without a sentence should fail to compile rather than fall through to a line that
 * says nothing. The wording is this path's own - the settings copy reads "$what are provided by",
 * which needs a plural subject a keypress does not have.
 */
private fun topOfMindAbsenceMessage(
    absence: PluginSectionAbsence,
    missingPermissions: List<String>,
): String =
    when (absence) {
        PluginSectionAbsence.NOT_INSTALLED -> {
            "The quick switcher is $TOP_OF_MIND_NAME, which is not installed. Install it from the Toolbox."
        }

        PluginSectionAbsence.DISABLED -> {
            "The quick switcher is $TOP_OF_MIND_NAME, which is switched off. Enable it in the Toolbox."
        }

        PluginSectionAbsence.INCOMPATIBLE -> {
            "The quick switcher is $TOP_OF_MIND_NAME, which this version of BOSS could not load. " +
                "Update it in the Toolbox."
        }

        PluginSectionAbsence.NO_ACCESS -> {
            "The quick switcher is $TOP_OF_MIND_NAME, which you do not have access to. " +
                "Ask an administrator to grant: ${missingPermissions.joinToString(", ")}."
        }

        PluginSectionAbsence.NO_PANEL -> {
            "$TOP_OF_MIND_NAME is running, and the installed version has no quick switcher. " +
                "Update it in the Toolbox."
        }

        PluginSectionAbsence.FAILED -> {
            "The quick switcher is $TOP_OF_MIND_NAME, which failed to load. See the BOSS log for why."
        }

        PluginSectionAbsence.STARTING, PluginSectionAbsence.UNKNOWN -> {
            "$TOP_OF_MIND_NAME has not finished starting. Try again in a moment."
        }
    }
