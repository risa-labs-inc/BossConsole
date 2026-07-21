package ai.rever.boss.components.window_panel

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.components.window_panel.components.BossResizablePanel
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.components.window_panel.components.side_panel.SidePanel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun BossDraggableComponent.BossWindow(
    modifier: Modifier = Modifier,
    tabsComponent: BossTabsComponent,
    panelComponentStore: PanelComponentStore,
    splitViewState: SplitViewState? = null,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    onShowSettings: (() -> Unit)? = null,
    onOpenProjectDialog: (() -> Unit)? = null,
    onNewProject: (() -> Unit)? = null
) {
    // Process any pending panel opens (for two-phase transitions)
    // This is critical for JxBrowser-based plugins to avoid BrowserViewState conflicts
    ProcessPendingPanelOpen()

    // State for split panels - use provided or create new
    val actualSplitViewState = splitViewState ?: rememberSplitViewState(
        tabRegistry = tabsComponent.tabRegistry,
        windowId = tabsComponent.windowId,
        initialTabsComponent = tabsComponent
    )

    // Perform any deferred "promote sidebar plugin to a main tab" request
    // (from the header's "Open as Tab" action or a drag-out onto the central area).
    ProcessPendingPromoteToTab(actualSplitViewState, panelComponentStore)

    // Focus the hosting tab when a hosted plugin's sidebar icon is clicked.
    ProcessPendingFocusHostedTab(actualSplitViewState)

    @Composable
    fun WithPanel(panel: Panel,
                  isPanelVisible: Boolean = isVisible(panel),
                  isMainVisible: Boolean = true,
                  isRelative: Boolean = false,
                  panelContent: @Composable BoxScope.() -> Unit = { SidePanel(panel, panelComponentStore) },
                  mainContent: (@Composable BoxScope.() -> Unit)? = null) {
        BossResizablePanel(
            modifier = modifier,
            panel = panel,
            isPanelVisible = isPanelVisible,
            isMainVisible = isMainVisible,
            isRelative = isRelative,
            sideContent = panelContent,
            mainContent = mainContent
        )
    }

    @Composable
    fun WithNestedPanel(panel: Panel,
                        secondaryPanel: Panel = bottom,
                        isFirstPanelVisible: Boolean = isVisible(panel.bottom),
                        isLastPanelVisible: Boolean = isVisible(panel.top),
                        isNestedRelative: Boolean = true,
                        firstPanel: @Composable BoxScope.() -> Unit = { SidePanel(panel.bottom, panelComponentStore) },
                        lastPanel: @Composable BoxScope.() -> Unit = { SidePanel(panel.top, panelComponentStore) },
                        mainContent: @Composable BoxScope.() -> Unit) {
        WithPanel(panel,
            panelContent = {
                WithPanel(secondaryPanel,
                    isPanelVisible = isFirstPanelVisible,
                    isMainVisible = isLastPanelVisible,
                    isRelative = isNestedRelative,
                    panelContent = firstPanel,
                    mainContent = lastPanel
                )},
            mainContent = mainContent)
    }

    WithPanel(bottom) {
        WithNestedPanel(left) {
            WithNestedPanel(right) {
                // Central tab area — also the drop target for a header drag-out (open as tab).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { mainAreaBounds = it.boundsInWindow() }
                ) {
                    // Use the new split view panel
                    SplitViewPanel(
                        splitViewState = actualSplitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        onShowSettings = onShowSettings,
                        onOpenProjectDialog = onOpenProjectDialog,
                        onNewProject = onNewProject
                    )
                    // While a header is dragged over the central area, highlight the
                    // resolved target region (a whole panel for center-drop, or the half
                    // where the new split would land) — mirrors the tab drag feedback.
                    val highlightRect = mainAreaHighlight
                    val areaOrigin = mainAreaBounds
                    if (highlightRect != null && areaOrigin != null && draggingItem != null) {
                        val density = LocalDensity.current
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (highlightRect.left - areaOrigin.left).roundToInt(),
                                        (highlightRect.top - areaOrigin.top).roundToInt()
                                    )
                                }
                                .size(
                                    with(density) { highlightRect.width.toDp() },
                                    with(density) { highlightRect.height.toDp() }
                                )
                                .background(Color.White.copy(alpha = 0.10f))
                                .border(2.dp, Color.White.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }
    }
}

