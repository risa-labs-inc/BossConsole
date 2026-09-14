package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MissingPluginOffer
import ai.rever.boss.components.plugin.PluginSectionAbsence
import ai.rever.boss.components.plugin.pluginSectionAbsence
import ai.rever.boss.components.plugin.pluginSectionMessage
import ai.rever.boss.components.plugin.pluginSectionOffersInstall
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val logger = BossLogger.forComponent("PluginSettingsNotice")

/**
 * Shown in a settings section whose panel is served by a plugin that has not registered its API.
 *
 * It says which of [PluginSectionAbsence]'s reasons applies, and when the plugin is genuinely
 * absent it offers to install it - the host can, which is the point: it resolves the id against
 * the store, downloads the jar and loads it, none of which a plugin can do for itself. Pressing
 * Install raises the host's own consent dialog (`MissingDependencyDialog`), which names the plugin
 * from the **store** and shows the id it will install by, rather than installing on this button's
 * say-so.
 *
 * **Everything except [servesNoPanel] is derived from [pluginId]**, so a section gets the whole
 * behaviour by naming its plugin. An earlier version took the missing permissions as a parameter
 * and only one of the three sections passed it, which left the other two telling a user who cannot
 * access the plugin to go and switch it on.
 *
 * **Known: the consent dialog opens over the main window, not this one.** `SettingsWindow` is
 * composed inside the main window's subtree and opts *its own* dialogs out of heavyweight overlay
 * routing for exactly this reason, but the dependency dialog is raised through
 * `PluginDependencyEventBus` and composed by `BossAppDialogs`, outside that opt-out. It is
 * always-on-top so it is not lost, but it appears centred on the main window rather than where the
 * press happened. Routing it would mean the prompt carrying a window id - the same change
 * `MissingDependencyPrompt` already records as not built, for the two-window case.
 *
 * @param what a **plural** noun phrase, e.g. "AI provider settings" - see [pluginSectionMessage]
 * @param pluginName the plugin's display name, for the sentence and the button
 * @param pluginId the id the host installs by, and the id every other fact is looked up under
 * @param servesNoPanel true when the plugin's API is present but this version serves no panel for
 *   this section. The one input the manager cannot answer, so the section has to.
 */
@Composable
internal fun PluginSettingsUnavailableNotice(
    what: String,
    pluginName: String,
    pluginId: String,
    servesNoPanel: Boolean = false,
) {
    val facts = rememberPluginSectionFacts(pluginId, servesNoPanel)
    // Keyed on the absence as well as the plugin, so the line cannot outlive the state that
    // produced it. Keyed on the plugin alone, pressing Install just as the plugin arrived left
    // "could not start the install" sitting under "isn't loaded yet", with no press left to clear
    // it - the button it belongs to having disappeared.
    var offerNotRaised by remember(pluginId, facts.absence) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = pluginSectionMessage(facts.absence, what, pluginName, facts.missingPermissions),
            color = BossTheme.colors.textMuted,
            fontSize = 13.sp,
        )

        if (pluginSectionOffersInstall(facts.absence)) {
            BossPrimaryButton(
                text = "Install $pluginName",
                onClick = {
                    // False means there was nothing to offer after all: the plugin arrived between
                    // the render and the press, or the installer factory is not wired. Note that
                    // true is not proof a dialog appeared either - the bus drops a report for a
                    // plugin already queued - which is why the failure is logged as well as shown.
                    val raised = MissingPluginOffer.offerIfMissing(pluginId)
                    offerNotRaised = !raised
                    if (!raised) {
                        logger.warn(
                            LogCategory.UI,
                            "Settings offered to install a plugin and nothing was raised",
                            mapOf("pluginId" to pluginId, "absence" to facts.absence.name),
                        )
                    }
                },
            )
        }

        if (offerNotRaised) {
            Text(
                text = "Could not start the install here. Install $pluginName from the Toolbox.",
                color = BossTheme.colors.textMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/** The absence and the permissions behind it, resolved together so they cannot disagree. */
private data class PluginSectionFacts(
    val absence: PluginSectionAbsence,
    val missingPermissions: List<String>,
)

/**
 * Recomputed whenever any plugin's state changes, so the notice follows the plugin.
 *
 * Observing `pluginStates` is what makes Install self-clearing: the install lands, the manager
 * updates, this recomposes, and the section swaps to the real panel without the user reopening
 * Settings. It is also the signal for the plugin being enabled or disabled elsewhere, and for the
 * access re-check that moves one in or out of `hiddenPlugins`.
 */
@Composable
private fun rememberPluginSectionFacts(
    pluginId: String,
    servesNoPanel: Boolean,
): PluginSectionFacts {
    // Same shape as the tab bar's bookmarks shelf, deliberately: a `?: return` before a
    // `collectAsState` would make the observation itself conditional on a global that can change
    // between compositions.
    val manager = DynamicPluginManager.anyActiveManager()
    val states = manager?.pluginStates?.collectAsState()?.value
    return remember(states, pluginId, servesNoPanel) {
        val missingPermissions =
            manager
                ?.getInaccessiblePlugins()
                ?.firstOrNull { it.pluginId == pluginId }
                ?.missingPermissions
                .orEmpty()
        PluginSectionFacts(
            absence =
                pluginSectionAbsence(
                    // The Install button's own predicate, so the sentence and the button can never
                    // disagree about whether the plugin is here.
                    installed = MissingPluginOffer.isInstalled(pluginId),
                    state = states?.get(pluginId)?.state,
                    isIncompatible = PluginCrashRegistry.isIncompatible(pluginId),
                    missingPermissions = missingPermissions,
                    servesNoPanel = servesNoPanel,
                ),
            missingPermissions = missingPermissions,
        )
    }
}
