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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What a drag would do to one panel, as far as that panel's highlight is concerned.
 *
 * Pure and coarse on purpose: three answers where the drop target has a panel id, an orientation
 * and an insertion index. Collapsing them here is what lets the overlay subscribe to something
 * that changes a handful of times in a drag rather than to the target itself, whose index moves
 * every time the pointer crosses a tab row in a bar.
 */
internal enum class PanelDropHighlight { SPLIT_VERTICAL, SPLIT_HORIZONTAL, CENTRE }

private fun panelDropHighlightFor(
    dropTarget: TabDropTarget?,
    panelId: String,
): PanelDropHighlight? =
    when {
        dropTarget is TabDropTarget.SplitPanel && dropTarget.panelId == panelId -> {
            when (dropTarget.orientation) {
                SplitOrientation.VERTICAL -> PanelDropHighlight.SPLIT_VERTICAL
                SplitOrientation.HORIZONTAL -> PanelDropHighlight.SPLIT_HORIZONTAL
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

    Box(modifier = Modifier.fillMaxSize().padding(start = leadingInset)) {
        // Both edges of an axis light up together: a vertical split takes the left AND the right.
        when (highlight) {
            PanelDropHighlight.SPLIT_VERTICAL -> {
                DropZoneBand(Alignment.CenterStart, acrossWidth = true)
                DropZoneBand(Alignment.CenterEnd, acrossWidth = true)
            }

            PanelDropHighlight.SPLIT_HORIZONTAL -> {
                DropZoneBand(Alignment.TopCenter, acrossWidth = false)
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
