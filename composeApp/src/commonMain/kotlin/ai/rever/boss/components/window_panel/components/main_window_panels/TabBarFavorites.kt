package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.cache.loadFaviconFromCache
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Plugin that owns bookmarks. The Favorites grid is its surface in the tab bar. */
const val BOOKMARKS_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.bookmarks"

/** Side of one favourite tile. Icon-only, so this is the icon plus a little breathing room. */
private val FAVORITE_TILE_SIZE = 30.dp

/** The favicon inside a tile. */
private val FAVORITE_ICON_SIZE = 16.dp

/** Tiles per row before wrapping. Five fits the default 200dp bar with margins to spare. */
private const val FAVORITES_PER_ROW = 5

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
 */
@Composable
fun TabBarFavorites(
    bookmarks: List<Bookmark>,
    pluginInstalled: Boolean?,
    apiReachable: Boolean,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onInstallPlugin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        bookmarks.chunked(FAVORITES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
 * The favicon comes from the same cache a restored tab's does, keyed off the bookmark's saved
 * `TabConfig`, so a bookmarked page shows the icon it had rather than a generic placeholder.
 * A bookmark with no cached favicon - a terminal, an editor file, a page bookmarked before the
 * cache had it - falls back to the first letter of its title, which still distinguishes tiles
 * from each other.
 */
@Composable
private fun FavoriteTile(
    bookmark: Bookmark,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = BossTheme.colors
    val title = bookmark.tabConfig.title.ifBlank { bookmark.tabConfig.url.orEmpty() }
    val favicon = loadFaviconFromCache(bookmark.tabConfig.faviconCacheKey)

    HoverTooltipBox(
        text = title,
        placement = TooltipPlacement.END,
        modifier =
            Modifier
                .size(FAVORITE_TILE_SIZE)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.raised)
                .contextMenu(
                    items =
                        listOf(
                            ContextMenuItem("Open", onClick = onOpen),
                            ContextMenuItem("Remove from Favorites", onClick = onRemove),
                        ),
                ).clickable(onClick = onOpen),
    ) {
        val image = favicon as? TabIcon.Image
        if (image != null) {
            Image(
                painter = image.asPainter(),
                contentDescription = title,
                modifier = Modifier.size(FAVORITE_ICON_SIZE),
            )
        } else {
            Text(
                text = title.take(1).uppercase(),
                color = colors.textSecondary,
                fontSize = 12.sp,
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
