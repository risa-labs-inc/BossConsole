package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelRegistry

/**
 * The registry's own [PanelId] for [requested], or null when no registered panel matches.
 *
 * [PanelId] is a data class of three fields and [PanelRegistry] keys on all of them, but a
 * caller from outside the host knows only the panel's id string. [PanelId.defaultOrder] is a
 * sidebar-ordering detail it has no way to look up - the api's own helpers guess it
 * (`AiAvailability` builds `PanelId(TOOLBOX_PANEL_ID, 0)`) - so a promote or open addressed
 * with the wrong number would find nothing and report no error.
 *
 * Matching on `panelId` + `pluginId` and returning the REGISTERED id is what the panel-open
 * event handler already does inline; this is the same rule, shared, so every id arriving from
 * a plugin is normalised the same way before it reaches anything keyed on the whole class
 * (`PanelComponentStore`, the hosted-as-tab counts).
 *
 * `pluginId` is part of the match, not dropped: it defaults to `"ai.rever.boss"` and the few
 * plugins that do set it (docker, kubernetes, organisation, dna-origami) are distinguishing
 * themselves on purpose.
 */
fun PanelRegistry.resolveRegisteredPanelId(requested: PanelId): PanelId? =
    getAllPanels()
        .find { it.id.panelId == requested.panelId && it.id.pluginId == requested.pluginId }
        ?.id
