package ai.rever.boss.components.dialogs

import ai.rever.boss.components.common.BossSearchBar
import ai.rever.boss.components.home.homeToolColumns
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dialog width, matching [NewTabDialog] so the two do not read as different kinds of thing. */
private val DIALOG_WIDTH = 520.dp

/** How strongly a hovered tile takes the signal colour. Enough to read as the target, not a fill. */
private const val TILE_HOVER_ALPHA = 0.22f

/** Gap between tiles, and the narrowest a tile may be before the grid drops a column. */
private val TILE_GAP = 8.dp
private val TILE_MIN_WIDTH = 110.dp

/**
 * Every tile is exactly this tall, whatever its label does.
 *
 * A Row stretches its children to the tallest of them, so one two-line label - "Run
 * Configurations" is the one that found this - made its whole row taller than the rows above and
 * below it, and the grid lost its rhythm at exactly the tile the eye was on. Fixing the height
 * and giving the label a fixed two lines ([LABEL_LINES]) means a long name changes nothing about
 * the shape of anything else.
 */
private val TILE_HEIGHT = 82.dp

/**
 * Lines reserved for a label, always - a one-line name is padded to two rather than centred.
 *
 * Reserving them is what keeps a wrapped label from resizing its tile; drawing them consistently
 * is what keeps every icon on the same baseline across a row.
 */
private const val LABEL_LINES = 2

/**
 * Rows shown before the grid scrolls.
 *
 * The bound is a whole number of rows rather than a round number of dp, because a dp bound cuts
 * whichever tile happens to straddle it and a half-drawn tile reads as a broken layout instead of
 * as "there is more below".
 */
private const val VISIBLE_ROWS = 4

/**
 * Every tool, searchable, for when the icon strips that normally hold them are not on screen.
 *
 * Opened by the launcher whose position [ai.rever.boss.app.toolLauncherPlacement] decides. The
 * contents do not depend on which strip is missing: this lists them all.
 *
 * **A grid of tiles rather than a list of rows**, because that is what this is - the place you go
 * to pick a tool by recognising it, the same way the Home screen's Tools section works. A list
 * sorts one column of text; a grid puts the icons where the eye can sweep them, and the icon is
 * how most of these are recognised. It reuses [homeToolColumns] so the two grids wrap on the same
 * rule rather than each inventing one.
 *
 * **Unfiltered by the hidden set, on purpose.** `getItemsForSlot` drops the panels a user hid from
 * their sidebar, and this is the one surface where that would be exactly wrong: the dialog exists
 * because tools are otherwise unreachable, so one hidden from a strip is precisely what somebody
 * would come here to find. Managing the hidden set stays with the customize menu.
 *
 * Clicking a tile goes through [BossDraggableComponent.handleSidebarItemClick], the same entry
 * point the sidebar icons and the More menu use, so a tool opened from here behaves exactly as it
 * does from its icon - custom `onClick` handlers included.
 */
@Composable
fun BossDraggableComponent.ToolLauncherDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }

    // Every slot, in the order they are drawn down the two rails, so the grid reads the way the
    // strips do rather than in registration order.
    //
    // Deliberately NOT remembered. itemsBySlot is a Compose state map, so reading it here is what
    // subscribes this dialog to a tool loading or unloading while it is open; a remember would
    // freeze the list at whatever was registered when the dialog opened.
    val allTools = allSidebarTools()
    val matches = allTools.filter { matchesToolQuery(it, query) }

    // Type-to-open, the way a launcher is expected to behave: the field has focus the moment the
    // dialog appears, so the first keystroke filters instead of being swallowed, and Enter takes
    // the top match. Without the focus the search bar is a box you have to click first, which is
    // the difference between a launcher and a dialog with a filter in it.
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Guarded, and the stake is higher here than in an ordinary dialog. `launcherKeys` is an
        // onPreviewKeyEvent on the Column, so it only fires while focus is inside this subtree:
        // if the request throws, the launcher loses type-to-filter, Enter AND Escape at once, and
        // a dialog that will not close on Escape is the worst of those three.
        //
        // requestFocus() on a FocusRequester whose node is not attached yet throws
        // IllegalStateException, and inside a BossDialog - which composes into its own window on
        // the heavyweight path - that ordering is not guaranteed. One frame is usually enough;
        // runCatching is what makes "usually" survivable.
        withFrameNanos { }
        runCatching { searchFocus.requestFocus() }
    }

    val openFirstMatch: () -> Unit = {
        // Nothing to open when the query matches nothing: Enter on an empty grid should do
        // nothing rather than close the dialog, which would look like it had opened something.
        matches.firstOrNull()?.let { tool ->
            handleSidebarItemClick(tool)
            onDismiss()
        }
        Unit
    }

    BossDialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .width(DIALOG_WIDTH)
                    .background(BossTheme.colors.panel, RoundedCornerShape(8.dp))
                    .border(1.dp, BossTheme.colors.line, RoundedCornerShape(8.dp))
                    .launcherKeys(onEscape = onDismiss, onEnter = openFirstMatch)
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BossSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search tools...",
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            )

            if (matches.isEmpty()) {
                Text(
                    text = if (allTools.isEmpty()) "No tools are loaded" else "No tools match \"$query\"",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                ToolGrid(tools = matches) { tool ->
                    handleSidebarItemClick(tool)
                    onDismiss()
                }
            }
        }
    }
}

/**
 * Escape closes, Enter takes the top match.
 *
 * PREVIEW, not `onKeyEvent`: the search field has focus from the moment the dialog opens and
 * consumes both keys itself, so a bubbling handler here would never see either of them.
 */
private fun Modifier.launcherKeys(
    onEscape: () -> Unit,
    onEnter: () -> Unit,
): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.Escape -> {
                    onEscape()
                    true
                }

                Key.Enter, Key.NumPadEnter -> {
                    onEnter()
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

/**
 * The tiles, wrapped into as many columns as fit.
 *
 * A plain scrolling Column of chunked Rows rather than a LazyVerticalGrid: the whole point of the
 * dialog is that you can see the tools at once, so it is bounded at [VISIBLE_ROWS] rows and rarely
 * scrolls at all. A lazy grid inside a height-bounded dialog also has to be told its own height,
 * which is the awkward part of using one here for at most a few dozen tiles.
 */
@Composable
private fun ToolGrid(
    tools: List<SidebarItem>,
    onClick: (SidebarItem) -> Unit,
) {
    BoxWithConstraints(
        // Exactly VISIBLE_ROWS of tiles plus the gaps between them, so the scroll boundary always
        // falls between two rows.
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = TILE_HEIGHT * VISIBLE_ROWS + TILE_GAP * (VISIBLE_ROWS - 1)),
    ) {
        val columns = homeToolColumns(maxWidth, minTileWidth = TILE_MIN_WIDTH, gap = TILE_GAP)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            tools.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    row.forEach { tool ->
                        ToolTile(
                            tool = tool,
                            onClick = { onClick(tool) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad the last row so its tiles keep the width every other row's have, rather
                    // than stretching across the leftover space - the same thing HomeToolGrid does.
                    repeat(columns - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTile(
    tool: SidebarItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = BossTheme.colors

    Column(
        modifier =
            modifier
                .height(TILE_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) colors.signal.copy(alpha = TILE_HOVER_ALPHA) else colors.raised)
                .border(1.dp, if (hovered) colors.signal else colors.line, RoundedCornerShape(8.dp))
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Space-between rather than a fixed gap: the icon sits at the top of every tile and the
        // label block at the bottom of every tile, so both line up across a row whether the label
        // takes one line or two.
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = null,
            tint = if (hovered) colors.signalText else colors.textPrimary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = tool.label,
            color = if (hovered) colors.textPrimary else colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            minLines = LABEL_LINES,
            maxLines = LABEL_LINES,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Whether a tool belongs in the results for [query].
 *
 * Label and id both, because the two answer different questions: the label is what the user sees
 * on the icon's tooltip, and the id is what they will have read in a tool's own documentation or
 * in the Toolbox. An empty query matches everything, so the dialog opens as a full grid rather
 * than as an empty state waiting to be typed into.
 *
 * Pure so the matching is testable without composing a dialog.
 */
internal fun matchesToolQuery(
    item: SidebarItem,
    query: String,
): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    return item.label.contains(trimmed, ignoreCase = true) || item.id.contains(trimmed, ignoreCase = true)
}
