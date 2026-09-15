package ai.rever.boss.components.home

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Gap between tiles, horizontally and vertically. */
private val TileGap: Dp = 8.dp

/**
 * How many tile columns fit in [availableWidth].
 *
 * Pure and internal so the grid's reflow is pinned by a unit test rather than needing a display -
 * the same reason `showsInlineSearch` and `AuthScaffold.showsBrandPanel` are pure. Solves
 * `n * min + (n - 1) * gap <= available` for n, and never returns less than 1, so a panel
 * narrower than one tile gets a single squeezed column instead of a division by zero or an empty
 * grid.
 */
internal fun homeToolColumns(
    availableWidth: Dp,
    minTileWidth: Dp = HomeToolMinWidth,
    gap: Dp = TileGap,
): Int {
    if (availableWidth <= 0.dp) return 1
    val fits = ((availableWidth + gap) / (minTileWidth + gap)).toInt()
    return fits.coerceAtLeast(1)
}

/**
 * The tool grid: everything installed that can be opened, then everything the store offers.
 *
 * Wraps into as many columns as fit rather than scrolling sideways. The screen this replaces put
 * each group in a `Row(horizontalScroll(...))`, so with 33 plugins installed most tools sat past
 * the right edge with no affordance suggesting they were there.
 *
 * Laid out as chunked rows off a computed column count rather than `LazyVerticalGrid`, because
 * the grid sits inside the screen's vertical scroll - a lazy grid there has unbounded height and
 * throws - and because a pure [homeToolColumns] is testable in a way `GridCells.Adaptive` is not.
 * The tile count is bounded (one per registered tool plus one per store row), so nothing is
 * gained by laziness.
 */
@Composable
internal fun HomeToolGrid(
    tools: List<HomeTool>,
    installing: Set<String>,
    filter: HomeToolFilter,
    onSelectFilter: (HomeToolFilter) -> Unit,
    shortcutFor: (HomeTool) -> String?,
    onToolClick: (HomeTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = BossTheme.space
    // Only offer the filter once there is something in both buckets; with nothing installable
    // (an offline store, or everything already installed) "All / Installed / Available" is three
    // chips for one answer.
    val showFilter = tools.any { it.isInstalled } && tools.any { !it.isInstalled }
    // With the chips hidden there is no control to change the filter, so anything but ALL would
    // strand the grid: install the last available plugin while AVAILABLE is selected and the chips
    // disappear over an empty grid with no way back.
    val effective = if (showFilter) filter else HomeToolFilter.ALL
    val visible = tools.filter { effective.accepts(it) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(space.md),
    ) {
        if (showFilter) {
            FilterChips(selected = effective, onSelect = onSelectFilter)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = homeToolColumns(maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                visible.chunked(columns).forEach { rowTools ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                        rowTools.forEach { tool ->
                            HomeToolCard(
                                tool = tool,
                                state = tool.stateIn(installing),
                                shortcut = shortcutFor(tool),
                                onClick = { onToolClick(tool) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad the last row so its tiles keep the same width as every other
                        // row's instead of stretching across the leftover space.
                        repeat(columns - rowTools.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun HomeTool.stateIn(installing: Set<String>): HomeToolState =
    when {
        pluginId != null && pluginId in installing -> HomeToolState.INSTALLING
        launch is HomeToolLaunch.Install -> HomeToolState.INSTALLABLE
        launch is HomeToolLaunch.Recover -> HomeToolState.DISABLED
        else -> HomeToolState.READY
    }

@Composable
private fun FilterChips(
    selected: HomeToolFilter,
    onSelect: (HomeToolFilter) -> Unit,
) {
    val space = BossTheme.space
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Chips wrap for the same reason tiles do: a horizontally scrolling filter row hides the
        // filters it exists to offer.
        val perRow = homeToolColumns(maxWidth, minTileWidth = 96.dp, gap = space.sm)
        Column(verticalArrangement = Arrangement.spacedBy(space.sm)) {
            HomeToolFilter.entries.toList().chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                    row.forEach { option ->
                        FilterChip(
                            label = option.label,
                            isSelected = selected == option,
                            onClick = { onSelect(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BossTheme.colors
    val space = BossTheme.space
    Text(
        text = label,
        color = if (isSelected) colors.onSignal else colors.textSecondary,
        style = BossTheme.type.micro,
        modifier =
            Modifier
                .background(
                    color = if (isSelected) colors.signal else colors.panel,
                    shape = RoundedCornerShape(BossTheme.radius.button),
                ).then(
                    if (isSelected) {
                        Modifier
                    } else {
                        Modifier.border(1.dp, colors.line, RoundedCornerShape(BossTheme.radius.button))
                    },
                ).clickable { onClick() }
                .padding(horizontal = space.sm, vertical = space.xs),
    )
}
