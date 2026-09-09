package ai.rever.boss.components.window_panel

import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.window_panel.components.BossResizablePanel
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.components.window_panel.components.side_panel.SidePanel
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
    /** Window chrome for the foot of the vertical tab bar. See SplitViewPanel. */
    verticalBarFooter: @Composable () -> Unit = {},
    /** Window chrome for below the FULL vertical bar's split map. See [SplitViewPanel]. */
    verticalBarBelowMap: @Composable () -> Unit = {},
    /** The same chrome for the foot of the bar's collapsed rail. See [SplitViewPanel]. */
    verticalBarRailActions: @Composable () -> Unit = {},
    /** Clearance above the vertical bar, for the macOS traffic lights. See [SplitViewPanel]. */
    verticalBarTopInset: Dp = 0.dp,
    /** Reports whether the hover-revealed bar is on screen. See [SplitViewPanel]. */
    onDrawerVisibleChange: (Boolean) -> Unit = {},
    /** See `SplitViewPanel.onBarRailedChange`. */
    onBarRailedChange: (Boolean) -> Unit = {},
    /**
     * Clearance at the top of an open LEFT plugin panel, for the macOS traffic lights.
     *
     * The panel sits between the icon strip and the vertical tab bar, so whenever one is open it -
     * not the bar - is the column the lights are drawn over. Its own header was under them.
     */
    leftPanelTopInset: Dp = 0.dp,
    /**
     * Which open plugin panel's column carries [panelFooter], or null for none.
     *
     * Decided by the window, not here: `hostActionsPanelEdge` is the same expression that decides
     * whether those actions are in a panel foot at all, and two answers could disagree - which
     * would render the row into a column nothing is composing. Compared by root, so it names a
     * column (`left`) rather than one of its two halves.
     *
     * All three columns ask, though today only `right` is ever named - `hostActionsPanelEdge` has
     * the reasons the other two are out. Asking uniformly is what lets that stay one decision in
     * one place, and an unfilled [PanelColumn] is a `Column` around one `weight(1f)` child, which
     * lays out exactly as the bare panel did.
     */
    panelFooterEdge: Panel? = null,
    /**
     * Window chrome for the very bottom of that column, under whichever panel is lowest in it.
     *
     * Today the host's own actions - Sign Out, Settings, Tools, Search - when a TOP tab bar leaves
     * no bar to hold them and the right panel is open in front of where the floating cluster would
     * park. See `focusQuickActionsPlacement`. Renders nothing when there is nothing to put there.
     */
    panelFooter: @Composable () -> Unit = {},
) {
    // Process any pending panel opens (for two-phase transitions)
    // This is critical for JxBrowser-based plugins to avoid BrowserViewState conflicts
    ProcessPendingPanelOpen()

    // State for split panels - use provided or create new
    val actualSplitViewState =
        splitViewState ?: rememberSplitViewState(
            tabRegistry = tabsComponent.tabRegistry,
            windowId = tabsComponent.windowId,
            initialTabsComponent = tabsComponent,
        )

    // Perform any deferred "promote sidebar plugin to a main tab" request
    // (from the header's "Open as Tab" action or a drag-out onto the central area).
    ProcessPendingPromoteToTab(actualSplitViewState, panelComponentStore)

    // Focus the hosting tab when a hosted plugin's sidebar icon is clicked.
    ProcessPendingFocusHostedTab(actualSplitViewState)

    @Composable
    fun WithPanel(
        panel: Panel,
        isPanelVisible: Boolean = isVisible(panel),
        isMainVisible: Boolean = true,
        isRelative: Boolean = false,
        panelContent: @Composable BoxScope.() -> Unit = { SidePanel(panel, panelComponentStore) },
        mainContent: (@Composable BoxScope.() -> Unit)? = null,
    ) {
        BossResizablePanel(
            modifier = modifier,
            panel = panel,
            isPanelVisible = isPanelVisible,
            isMainVisible = isMainVisible,
            isRelative = isRelative,
            sideContent = panelContent,
            mainContent = mainContent,
        )
    }

    @Composable
    fun WithNestedPanel(
        panel: Panel,
        secondaryPanel: Panel = bottom,
        isFirstPanelVisible: Boolean = isVisible(panel.bottom),
        isLastPanelVisible: Boolean = isVisible(panel.top),
        isNestedRelative: Boolean = true,
        firstPanel: @Composable BoxScope.() -> Unit = { SidePanel(panel.bottom, panelComponentStore) },
        lastPanel: @Composable BoxScope.() -> Unit = { SidePanel(panel.top, panelComponentStore) },
        mainContent: @Composable BoxScope.() -> Unit,
    ) {
        WithPanel(
            panel,
            panelContent = {
                PanelColumn(column = panel, footerEdge = panelFooterEdge, footer = panelFooter) {
                    WithPanel(
                        secondaryPanel,
                        isPanelVisible = isFirstPanelVisible,
                        isMainVisible = isLastPanelVisible,
                        isRelative = isNestedRelative,
                        panelContent = firstPanel,
                        mainContent = lastPanel,
                    )
                }
            },
            mainContent = mainContent,
        )
    }

    WithPanel(
        bottom,
        // The one panel with no second half, wrapped the same way as the two columns above.
        panelContent = {
            PanelColumn(column = bottom, footerEdge = panelFooterEdge, footer = panelFooter) {
                SidePanel(bottom, panelComponentStore)
            }
        },
    ) {
        WithNestedPanel(
            left,
            // Painted before it is padded: an unpainted inset shows the raw native window surface
            // through, which reads as a white band at the top of the panel.
            firstPanel = { InsetSidePanel(left.bottom, panelComponentStore, leftPanelTopInset) },
            lastPanel = { InsetSidePanel(left.top, panelComponentStore, leftPanelTopInset) },
        ) {
            WithNestedPanel(right) {
                // Central tab area — also the drop target for a header drag-out (open as tab).
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { mainAreaBounds = it.boundsInWindow() },
                ) {
                    // Use the new split view panel
                    SplitViewPanel(
                        splitViewState = actualSplitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        verticalBarFooter = verticalBarFooter,
                        verticalBarBelowMap = verticalBarBelowMap,
                        verticalBarRailActions = verticalBarRailActions,
                        verticalBarTopInset = verticalBarTopInset,
                        onDrawerVisibleChange = onDrawerVisibleChange,
                        onBarRailedChange = onBarRailedChange,
                    )
                    DragTargetHighlight()
                }
            }
        }
    }
}

/**
 * The drop-target outline drawn over the central area while a panel header is dragged.
 *
 * While a header is dragged over the central area this highlights the resolved target region - a
 * whole panel for a centre drop, or the half where the new split would land - mirroring the
 * feedback a tab drag gives. Nothing is drawn unless a drag is in flight and the area has been
 * measured.
 *
 * Its own composable rather than an `if` inside [BossWindow], which is at detekt's length ceiling:
 * this is self-contained, reading only the component's own drag state.
 */
@Composable
private fun BossDraggableComponent.DragTargetHighlight() {
    val highlightRect = mainAreaHighlight
    val areaOrigin = mainAreaBounds
    if (highlightRect == null || areaOrigin == null || draggingItem == null) return

    val density = LocalDensity.current
    Box(
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        (highlightRect.left - areaOrigin.left).roundToInt(),
                        (highlightRect.top - areaOrigin.top).roundToInt(),
                    )
                }.size(
                    with(density) { highlightRect.width.toDp() },
                    with(density) { highlightRect.height.toDp() },
                ).background(Color.White.copy(alpha = 0.10f))
                .border(2.dp, Color.White.copy(alpha = 0.5f)),
    )
}

/**
 * A panel column, with window chrome under it when this is the column that carries it.
 *
 * The Column is what lets [footer] take a row out of the BOTTOM of the whole column - under
 * whichever of the two stacked panels is lowest in it - rather than inside one of them, where it
 * would move about with whatever the user happens to have open.
 *
 * With no footer to draw this is a Column around one `weight(1f)` child, which lays out exactly as
 * the bare panel did, so wrapping every column costs nothing in the common case.
 *
 * **The `[footerEdge] == [column]` gate lives here, not at the three call sites.** It was written
 * out three times, which made it three things that could disagree and, in a test, something to
 * hand-copy: a test that re-implements the predicate it means to pin passes just as happily when
 * one of the real copies names the wrong column.
 *
 * `internal` rather than private so `QuickActionsPanelFooterTest` and `HostActionsWiringTest` can
 * drive THIS layout and THIS gate rather than copies of them. What that still does not pin is
 * which [column] each call site passes - that is one token beside the `WithPanel` it belongs to,
 * and mounting the whole window is the only thing that would catch it.
 */
@Composable
internal fun PanelColumn(
    column: Panel,
    footerEdge: Panel?,
    footer: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f), content = content)
        if (footerEdge == column) footer()
    }
}

/**
 * A side panel with the macOS traffic-light clearance above it.
 *
 * Painted BEFORE it is padded. An unpainted inset shows the raw native window surface through,
 * which reads as a white band across the top of the panel - the same trap `WindowBarRow` documents
 * for the bar's own inset.
 */
@Composable
private fun BossDraggableComponent.InsetSidePanel(
    panel: Panel,
    panelComponentStore: PanelComponentStore,
    topInset: Dp,
) {
    Box(modifier = Modifier.background(BossTheme.colors.panel).padding(top = topInset)) {
        SidePanel(panel, panelComponentStore)
    }
}
