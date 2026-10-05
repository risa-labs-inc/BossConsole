package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel

internal object DefaultSpace {
    const val ID = "space-planet-berul"
    val planetBerul = LayoutWorkspace(
        id = ID,
        name = "Planet Berul",
        description = "Your starting Space",
        layout = SinglePanel(PanelConfig(id = "planet-berul-main", tabs = emptyList())),
    )
}

internal data class SpaceMenuGroups(
    val recent: List<LayoutWorkspace>,
    val more: List<LayoutWorkspace>,
    val templates: List<LayoutWorkspace>,
)

internal fun spaceMenuGroups(spaces: List<LayoutWorkspace>, recentIds: List<String>): SpaceMenuGroups {
    val actual = distinctSpaceMenuNames(spaces.filter { it.id !in PredefinedWorkspaces.allIds && it.id != LAST_SESSION_ID })
    val ordered = actual.sortedWith(compareBy<LayoutWorkspace> {
        recentIds.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
    }.thenByDescending { it.timestamp })
    return SpaceMenuGroups(ordered.take(5), ordered.drop(5), PredefinedWorkspaces.allWorkspaces)
}

/** Keep distinct saved identities selectable when legacy records share a display name. */
internal fun distinctSpaceMenuNames(spaces: List<LayoutWorkspace>): List<LayoutWorkspace> {
    val used = spaces.map { it.name }.toMutableSet()
    val seen = mutableSetOf<String>()
    val labels = spaces.sortedByDescending { it.timestamp }.associate { space ->
        val label = if (seen.add(space.name)) space.name else {
            var index = 2
            while ("${space.name} ($index)" in used) index++
            "${space.name} ($index)".also { used.add(it) }
        }
        space.id to label
    }
    return spaces.map { it.copy(name = labels.getValue(it.id)) }
}
