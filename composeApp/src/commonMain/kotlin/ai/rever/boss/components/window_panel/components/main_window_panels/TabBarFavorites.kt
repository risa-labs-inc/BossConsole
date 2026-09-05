package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.cache.loadHighQualityFavicon
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropTarget
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Plugin that owns bookmarks. The Favorites grid is its surface in the tab bar. */
const val BOOKMARKS_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.bookmarks"

/**
 * Side of one favourite tile.
 *
 * Sized to be a target and a landmark rather than a bullet point: at 30dp the first version read
 * as a row of dots, and a favourite you cannot pick out at a glance is not doing the job the
 * grid exists for. Four of these plus their gaps fit the 200dp bar's content width.
 */
private val FAVORITE_TILE_SIZE = 40.dp

/** The favicon inside a tile. Large enough that a site is recognisable, not just coloured. */
private val FAVORITE_ICON_SIZE = 22.dp

/** Corner radius of a tile. Rounded-square, the shape an app icon has. */
private val FAVORITE_TILE_RADIUS = 10.dp

/** Gap between tiles, in both directions. */
private val FAVORITE_TILE_GAP = 6.dp

/** Tiles per row before wrapping. Four 40dp tiles and three 6dp gaps fit inside a 200dp bar. */
private const val FAVORITES_PER_ROW = 4

/**
 * Arc's Favorites: an icon-only grid of bookmarks pinned above everything else in the sidebar.
 *
 * Icon-only is the point rather than a shortcut. These are the handful of places someone goes
 * constantly, so they are recognised by their favicon long before a title would be read, and a
 * grid of them costs four rows' height instead of twenty. Titles live in the tooltip.
 *
 * **Owned by the bookmarks plugin, not the host.** BOSS has no bookmark store of its own -
 * `BookmarkAPIAccess` returns null for everything when that plugin is absent - so this section
 * offers to install it rather than rendering an empty grid that could never fill. That offer goes
 * through the same prompt the install-time dependency flow uses, so it arrives with a working
 * Install button instead of sending the user to hunt in the Toolbox.
 *
 * FOUR states, and the third is the one that cost a round of "the button does nothing":
 *
 * | plugin | API reachable | shown |
 * |---|---|---|
 * | absent | - | offer to install it |
 * | installed | no | say it is not running, and do NOT offer to install it again |
 * | installed | yes, nothing saved | say how to save one |
 * | installed | yes, saved | the tiles |
 *
 * The middle row is not hypothetical: a plugin whose jar fails BinaryCompatibilityValidator is
 * installed, enabled, and disabled at load, so its API is unreachable while every "is it
 * installed" check says yes. Offering Install there raises no prompt - the installer correctly
 * reports it present - and the click does nothing, says nothing and logs nothing.
 *
 * @param bookmarks every bookmark across every collection, already flattened by the caller.
 * @param pluginInstalled whether the plugin is present, by the same predicate the Install button
 *   uses. Null when that cannot be determined, treated as "present" so a wiring gap shows the
 *   quieter message rather than offering an install that would go nowhere.
 * @param apiReachable whether the plugin is actually serving its API right now.
 * @param trailing the bar's collapse chevron or pin, rendered on the SAME line as this section's
 *   label. It lived on a row of its own first, which spent a whole line of a narrow bar on one
 *   16dp glyph; the header this section needed anyway is the natural place for it.
 */

/**
 * The section's label line, which also hosts the bar's one chrome control.
 *
 * @param trailing the collapse chevron or the pin, on the SAME line as the label. It lived on a
 *   row of its own first, which spent a whole line of a narrow bar on one 16dp glyph.
 * @param onOpen opens the Bookmarks panel, or null when there is no panel to open. The shelf
 *   shows four favourites; the label is the way to the rest of them, which is what a section
 *   header pointing at a plugin should do. Null leaves the label inert rather than clickable and
 *   silent - see [TabBarFavorites] for when.
 */
@Composable
private fun FavoritesHeader(
    trailing: @Composable () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FAVORITES",
            // Brightens under the pointer, so a label that does something looks different from
            // the section headings that do not.
            color = if (hovered && onOpen != null) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (onOpen == null) {
                            Modifier
                        } else {
                            Modifier.hoverable(interactionSource).clickable(onClick = onOpen)
                        },
                    ).padding(start = 4.dp),
        )
        trailing()
    }
}

@Composable
fun TabBarFavorites(
    bookmarks: List<Bookmark>,
    pluginInstalled: Boolean?,
    apiReachable: Boolean,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onInstallPlugin: () -> Unit,
    trailing: @Composable () -> Unit = {},
    /**
     * The drag system, so a tab can be dropped here to bookmark it.
     *
     * Null for a bar that is not a drop target - the hover-reveal drawer, whose coordinates
     * belong to another window entirely. The shelf then registers nothing and offers nothing,
     * which is the honest answer: a drop there could only land somewhere wrong.
     */
    tabDragComponent: TabDraggableComponent? = null,
) {
    // Dropping a tab here bookmarks it, leaving it open where it is. The shelf is the only drop
    // in the bar that does not move the tab, which is why it reads as a shelf rather than as
    // another place tabs live.
    // Reveal goes through MenuActionsHandler rather than the sidebar model, because the bar has
    // no route to that model - it is four layers up in the scaffold - and this is the same event
    // the View menu's own Reveal Plugin raises. The handler ends in
    // `draggablePanelComponent.activatePlugin`, i.e. exactly what clicking the plugin's sidebar
    // icon does.
    val windowId = LocalWindowId.current

    val isDropTarget = tabDragComponent?.dropTarget is TabDropTarget.Favorites
    val borderColor = if (isDropTarget) BossTheme.colors.signal else Color.Transparent

    DisposableEffect(tabDragComponent) {
        onDispose { tabDragComponent?.registerFavoritesBounds(null) }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    tabDragComponent?.registerFavoritesBounds(coordinates.boundsInWindow())
                }.border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        FavoritesHeader(
            trailing = trailing,
            // Only where there is something to open. Not installed, or installed and not serving
            // its API, and the panel would not appear - a header that swallowed the click and did
            // nothing is the silent no-op the two states below exist to avoid. Those states carry
            // their own Install button and their own explanation.
            onOpen =
                windowId?.takeIf { pluginInstalled == true && apiReachable }?.let { id ->
                    // PanelIds.BOOKMARKS, not BOOKMARKS_PLUGIN_ID. The reveal event resolves a
                    // PANEL id, and the two ids for this plugin look enough alike that passing the
                    // plugin one matched nothing and did nothing - which is exactly how this
                    // shipped inert the first time.
                    { MenuActionsHandler.triggerRevealPlugin(id, PanelIds.BOOKMARKS.panelId) }
                },
        )

        when {
            pluginInstalled == false -> {
                FavoritesEmptyState(
                    headline = "Add Favorites",
                    body = "Bookmarks keep your most used pages one click away",
                    actionLabel = "Install Bookmarks",
                    onAction = onInstallPlugin,
                )
            }

            !apiReachable -> {
                FavoritesEmptyState(
                    headline = "Bookmarks unavailable",
                    // No action button. The fix is a plugin update or a look at the log, neither
                    // of which this shelf can do, and an Install button here would be the silent
                    // no-op that made this state worth distinguishing at all.
                    body = "The Bookmarks plugin is installed but not running. Check the Toolbox",
                    actionLabel = null,
                    onAction = {},
                )
            }

            bookmarks.isEmpty() -> {
                FavoritesEmptyState(
                    headline = "No Favorites yet",
                    // Names the exact menu item, because a hint that only says the feature exists
                    // leaves someone looking for a control that is two levels into a context menu.
                    body = "Right-click a tab and choose Bookmark to keep it here",
                    actionLabel = null,
                    onAction = {},
                )
            }

            else -> {
                FavoritesGrid(bookmarks = bookmarks, onOpen = onOpen, onRemove = onRemove)
            }
        }
    }
}

/** The tiles, wrapped [FAVORITES_PER_ROW] to a row. */
@Composable
private fun FavoritesGrid(
    bookmarks: List<Bookmark>,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FAVORITE_TILE_GAP)) {
        bookmarks.chunked(FAVORITES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(FAVORITE_TILE_GAP)) {
                row.forEach { bookmark ->
                    FavoriteTile(
                        bookmark = bookmark,
                        onOpen = { onOpen(bookmark) },
                        onRemove = { onRemove(bookmark) },
                    )
                }
            }
        }
    }
}

/**
 * One favourite: its favicon, its title as a tooltip, and a right-click menu to remove it.
 *
 * **The icon comes from `loadHighQualityFavicon` and nothing else.** It resolves the page's own
 * cached favicon FIRST and only asks Google about the host when there is none - the order that is
 * the whole correctness of these tiles, and it lives in the service so the dashboard cards and
 * the capture picker get it too. The earlier version of this tile fetched first, and a guess
 * about a host overwrote a known-correct per-page icon: every Google-property favourite came out
 * as the same "G", because Google resolves subdomains to their parent.
 *
 * Reading the standard cache here as well would undo the point twice over - once as a second read
 * of what the service already tried, and once because `LaunchedEffect` does not leave the
 * composition dispatcher, so the PNG decode would land on the UI thread. The service does its own
 * work on an IO dispatcher.
 *
 * The letter shows until the icon resolves, so the grid has its final shape on the first frame and
 * does not reflow.
 */
@Composable
private fun FavoriteTile(
    bookmark: Bookmark,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = BossTheme.colors
    val config = bookmark.tabConfig
    val title = config.title.ifBlank { config.url.orEmpty() }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    // Keyed on the bookmark's identity, so a re-render does not re-fetch and a changed bookmark
    // does.
    var icon by remember(config.url, config.faviconCacheKey) {
        mutableStateOf<TabIcon.Image?>(null)
    }
    LaunchedEffect(config.url, config.faviconCacheKey) {
        // A bookmark on something that was never a page - a terminal, a file - has a null or blank
        // url and so no host to guess from, which leaves it its cached icon or its letter.
        //
        // Unguarded on purpose: loadHighQualityFavicon does not throw, and a runCatching here
        // would swallow the cancellation this effect's disposal raises.
        icon = loadHighQualityFavicon(config.url, config.faviconCacheKey)
    }

    HoverTooltipBox(
        text = title,
        placement = TooltipPlacement.END,
        modifier =
            Modifier
                .size(FAVORITE_TILE_SIZE)
                .clip(RoundedCornerShape(FAVORITE_TILE_RADIUS))
                // Hover lifts the tile rather than the icon, so the whole target reads as live.
                .background(if (hovered) colors.signalWash else colors.raised)
                .hoverable(interactionSource)
                .contextMenu(
                    items =
                        listOf(
                            ContextMenuItem("Open", onClick = onOpen),
                            ContextMenuItem("Remove from Favorites", onClick = onRemove),
                        ),
                ).clickable(onClick = onOpen),
    ) {
        val image = icon
        if (image != null) {
            Image(
                painter = image.painter,
                contentDescription = title,
                modifier = Modifier.size(FAVORITE_ICON_SIZE).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Text(
                text = title.take(1).uppercase(),
                color = colors.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The dashed placeholder that stands where the grid will be, after Arc's own.
 *
 * It occupies the section rather than collapsing it, so Favorites has a fixed home at the top of
 * the bar instead of appearing out of nowhere the first time something is saved.
 */
@Composable
private fun FavoritesEmptyState(
    headline: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val colors = BossTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = headline,
            color = colors.textPrimary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = body,
            color = colors.textSecondary,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.signal)
                        .clickable(onClick = onAction)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = actionLabel,
                    // onSignal, NOT signalText. They are easy to confuse and only one is right
                    // here: signalText is amber, for a signal-coloured glyph on the normal
                    // background; onSignal is the dark ink meant to sit ON an amber fill. Using
                    // signalText here would paint amber on amber.
                    color = colors.onSignal,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
