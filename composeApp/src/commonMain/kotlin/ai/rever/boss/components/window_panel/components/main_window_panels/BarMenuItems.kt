package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettingsManager
import ai.rever.boss.window.displayName
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The menu a tab surface offers on its OWN empty space, as opposed to on a tab.
 *
 * Hoisted out of `rememberTabBarState` for the same reason [TabMenuState] was: more than one
 * surface has empty space to right-click, and they owe the same menu. The vertical bar's tab
 * list has it, and so does the favicon strip across the top of each pane - and that strip has no
 * [TabBarState] to borrow from, because in LEFT position the window owns the one bar and the
 * panel deliberately builds none.
 *
 * Everything here is window chrome rather than tab actions, which is what makes it shareable:
 * where the bar lives, whether the strip is drawn, and whether this workspace is a favourite are
 * the same questions wherever they are asked. [openNewTab] is the one thing that differs, and it
 * is a parameter - the bar's opens a tab in the pane it leads, the strip's in the pane it sits
 * on.
 */
@Composable
fun rememberBarMenuItems(openNewTab: () -> Unit): List<ContextMenuItem> {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()

    // Kept apart from any scope the surface uses for its own work, so a settings write is never
    // cancelled by a bar that recomposed mid-drag, and neither reads as the other's concern.
    val settingsScope = rememberCoroutineScope()

    return buildList {
        add(ContextMenuItem("New Tab", Icons.Default.Add, onClick = openNewTab))

        add(ContextMenuItem(isDivider = true))

        add(tabBarPositionItem(settings.tabBarPosition, settingsScope))

        // Deliberately offered on BOTH surfaces, not only on the strip it hides.
        //
        // The strip is the obvious place to ask for it - that is the thing under the pointer -
        // but a menu that can only ever turn it off is a one-way door: once hidden, the strip has
        // no empty space to right-click, and the way back would be Settings. Offering the same
        // toggle on the bar's empty space is what makes it reversible where it was reversed.
        //
        // Worded as the action rather than as a checkmark, because at top level there is nowhere
        // native menus put one - the submenu above has to spell its own tick into the label for
        // exactly that reason.
        add(
            ContextMenuItem(
                if (settings.showPaneTabStrip) "Hide Pane Tab Strip" else "Show Pane Tab Strip",
                Icons.Outlined.Tab,
                onClick = {
                    settingsScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            WindowAppearanceSettingsManager.currentSettings.value
                                .copy(showPaneTabStrip = !settings.showPaneTabStrip),
                        )
                    }
                },
            ),
        )

        add(ContextMenuItem(isDivider = true))

        favoriteWorkspaceItem()?.let(::add)
    }
}

/**
 * Where the bar lives, as a submenu.
 *
 * A label-only submenu, so it stays isNativeRepresentable() and survives the native-NSMenu path
 * on macOS; the checkmark is spelled as a trailing tick in the label because a native menu item
 * has nowhere else to put one.
 */
private fun tabBarPositionItem(
    current: TabBarPosition,
    scope: CoroutineScope,
): ContextMenuItem =
    ContextMenuItem(
        "Tab Bar Position",
        Icons.Outlined.ViewColumn,
        subMenu =
            TabBarPosition.entries.map { position ->
                ContextMenuItem(
                    if (position == current) "${position.displayName} ✓" else position.displayName,
                    onClick = {
                        scope.launch {
                            WindowAppearanceSettingsManager.updateSettings(
                                WindowAppearanceSettingsManager.currentSettings.value
                                    .copy(tabBarPosition = position),
                            )
                        }
                    },
                )
            },
    )

/** Star this workspace, or unstar it. Absent when no workspace is loaded, rather than disabled. */
private fun favoriteWorkspaceItem(): ContextMenuItem? {
    val current = workspaceManager.currentWorkspace.value ?: return null
    val isFavorited = BookmarkAPIAccess.isFavorite(current.id)
    return ContextMenuItem(
        if (isFavorited) "Unfavorite Workspace" else "Favorite Workspace",
        // The icon shows what the action DOES, matching the label: "Unfavorite" empties the
        // star, "Favorite" fills it.
        if (isFavorited) Icons.Outlined.StarBorder else Icons.Filled.Star,
        onClick = {
            if (isFavorited) {
                BookmarkAPIAccess.removeFavoriteWorkspace(current.id)
            } else {
                BookmarkAPIAccess.addFavoriteWorkspace(current.id, current.name)
            }
        },
    )
}
