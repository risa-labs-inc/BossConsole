package ai.rever.boss.components.overlays

// Deliberately not in `components.window_panel` beside its one caller: that package has
// underscores in its name, which detekt's PackageNaming rule rejects for anything new. It is an
// overlay, so this is where it belongs anyway. `internal` is module-wide, so SplitView reaches it.

import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropTarget
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * What a drag would do to one panel, as far as that panel's highlight is concerned.
 *
 * Pure and coarse on purpose: five answers where the drop target has a panel id, an orientation
 * and an insertion index. Collapsing them here is what lets the overlay subscribe to something
 * that changes a handful of times in a drag rather than to the target itself, whose index moves
 * every time the pointer crosses a tab row in a bar.
 */
internal enum class PanelDropHighlight { LEFT, RIGHT, TOP, BOTTOM, CENTRE }

internal fun panelDropHighlightFor(
    dropTarget: TabDropTarget?,
    panelId: String,
): PanelDropHighlight? =
    when {
        dropTarget is TabDropTarget.SplitPanel && dropTarget.panelId == panelId -> {
            when (dropTarget.orientation) {
                SplitOrientation.VERTICAL -> {
                    if (dropTarget.placeBefore) PanelDropHighlight.LEFT else PanelDropHighlight.RIGHT
                }

                SplitOrientation.HORIZONTAL -> {
                    if (dropTarget.placeBefore) PanelDropHighlight.TOP else PanelDropHighlight.BOTTOM
                }
            }
        }

        dropTarget is TabDropTarget.ExistingPanel && dropTarget.panelId == panelId -> {
            PanelDropHighlight.CENTRE
        }

        else -> {
            null
        }
    }

/**
 * Overlay that shows drop zone highlights on panel edges during drag operations.
 */
@Composable
internal fun PanelDropZoneOverlay(
    panelId: String,
    tabDragComponent: TabDraggableComponent,
    leadingInset: Dp = 0.dp,
) {
    val highlight by
        remember(tabDragComponent, panelId) {
            derivedStateOf { panelDropHighlightFor(tabDragComponent.dropTarget, panelId) }
        }

    val density = LocalDensity.current.density
    var region by remember { mutableStateOf<IntRect?>(null) }
    Box(
        Modifier.fillMaxSize().padding(start = leadingInset).onGloballyPositioned {
            val bounds = it.boundsInWindow()
            region =
                IntRect(
                    (bounds.left / density).roundToInt(),
                    (bounds.top / density).roundToInt(),
                    (bounds.right / density).roundToInt(),
                    (bounds.bottom / density).roundToInt(),
                )
        },
    ) {
        // Only mount a native window during an active highlight. The drag's source retains
        // the mouse grab, as it does for OverlayGhost; this overlay takes no keyboard focus.
        val bounds = region
        if (highlight != null && overlayCornerIsHeavyweight() && bounds != null) {
            val size = DpSize(bounds.width.dp, bounds.height.dp)
            OverlayCorner(Alignment.TopStart, size, regionInWindow = bounds) {
                Box(Modifier.size(size)) { PanelDropHighlightContent(highlight) }
            }
        } else {
            PanelDropHighlightContent(highlight)
        }
    }
}

@Composable
private fun BoxScope.PanelDropHighlightContent(highlight: PanelDropHighlight?) {
    when (highlight) {
        PanelDropHighlight.LEFT -> {
            DropZoneBand(Alignment.CenterStart, acrossWidth = true)
        }

        PanelDropHighlight.RIGHT -> {
            DropZoneBand(Alignment.CenterEnd, acrossWidth = true)
        }

        PanelDropHighlight.TOP -> {
            DropZoneBand(Alignment.TopCenter, acrossWidth = false)
        }

        PanelDropHighlight.BOTTOM -> {
            DropZoneBand(Alignment.BottomCenter, acrossWidth = false)
        }

        // "Add to this panel", which is the whole panel rather than one of its edges - and so
        // a fainter wash, since it covers content the user is still meant to read.
        PanelDropHighlight.CENTRE -> {
            Box(modifier = Modifier.fillMaxSize().alpha(0.15f).background(BossTheme.colors.signal))
        }

        null -> {}
    }
}

/**
 * One band of signal along a panel edge: where letting go would open a split.
 *
 * [acrossWidth] false gives a full-width band 60dp tall (a top or bottom edge), true a full-height
 * band 60dp wide (a left or right one). The 60dp matches `PanelDropZones.fromBounds`'s default
 * `edgeSize`, which is what actually decides the drop - the band is only its picture.
 */
@Composable
private fun BoxScope.DropZoneBand(
    alignment: Alignment,
    acrossWidth: Boolean,
) {
    Box(
        modifier =
            Modifier
                .align(alignment)
                .then(
                    if (acrossWidth) {
                        Modifier.width(60.dp).fillMaxHeight()
                    } else {
                        Modifier.fillMaxWidth().height(60.dp)
                    },
                ).alpha(0.3f)
                .background(BossTheme.colors.signal),
    )
}
