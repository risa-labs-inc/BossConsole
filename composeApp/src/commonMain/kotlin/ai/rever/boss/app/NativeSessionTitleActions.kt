package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitDirection
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.NativeTitleBarAction

/** Match BossTerm's session controls, using the existing window-scoped menu actions. */
internal fun nativeSessionTitleActions(state: BossAppState): List<NativeTitleBarAction> =
    buildList {
        add(NativeTitleBarAction("new", "New tab", "plus") { MenuActionsHandler.triggerNewTab(state.windowId) })
        if (state.splitViewState.zoomedPanelId != null) {
            add(
                NativeTitleBarAction(
                    "exit_pane_fullscreen",
                    "Exit pane fullscreen",
                    "arrow.down.right.and.arrow.up.left",
                ) {
                    state.splitViewState.exitZoom()
                },
            )
        } else {
            add(
                NativeTitleBarAction("split_vertical", "Split left/right", "rectangle.split.2x1") {
                    state.openSplitTabDialog(SplitDirection.RIGHT)
                },
            )
            add(
                NativeTitleBarAction("split_horizontal", "Split top/bottom", "rectangle.split.1x2") {
                    state.openSplitTabDialog(SplitDirection.DOWN)
                },
            )
        }
    }

internal fun nativeMoreTitleAction(
    state: BossAppState,
    terminalSetupActions: List<NativeTitleBarAction> = emptyList(),
): NativeTitleBarAction =
    NativeTitleBarAction(
        "more",
        "More actions",
        "ellipsis",
        menu =
            terminalSetupActions +
                listOf(
                    NativeTitleBarAction("settings", "Settings…") { state.settingsWindow.open() },
                    NativeTitleBarAction("logout", "Sign out…") { state.showLogoutDialog = true },
                ),
        onClick = {},
    )

private fun BossAppState.openSplitTabDialog(direction: SplitDirection) {
    splitViewState.requestSplitWithNewTab(splitViewState.activePanelId, direction)
    newTabDialogInitialType = null
    showNewTabDialog = true
}
