package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.cache.loadFaviconFromCache
import ai.rever.boss.cache.loadHighQualityFavicon
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
@Composable
fun TabBarFavorites(
    bookmarks: List<Bookmark>,
    pluginInstalled: Boolean?,
    apiReachable: Boolean,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onInstallPlugin: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FAVORITES",
                color = BossTheme.colors.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            trailing()
        }

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
 * **The icon is fetched, not just read from cache.** The first version read
 * `loadFaviconFromCache(faviconCacheKey)` synchronously during composition, and every tile came
 * out as a letter: a bookmark saved from anything but a browser tab has no cache key at all, and
 * one saved before its favicon was cached has a key that misses. `loadHighQualityFavicon` starts
 * from the URL instead and returns a 128px icon, which is also the right source for a tile this
 * size - a 16px favicon scaled to 22dp is visibly soft.
 *
 * Loaded in a LaunchedEffect rather than inline, because it touches the disk and the network and
 * composition is the wrong thread for either. The letter shows until it resolves, so the grid has
 * its final shape on the first frame and does not reflow.
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
        val url = config.url
        icon =
            if (url.isNullOrBlank()) {
                // Not a page - a terminal, a file. Nothing to fetch; the cache key is the only
                // chance, and usually absent too.
                runCatching { loadFaviconFromCache(config.faviconCacheKey) }.getOrNull()
            } else {
                runCatching { loadHighQualityFavicon(url, config.faviconCacheKey) }.getOrNull()
                    ?: runCatching { loadFaviconFromCache(config.faviconCacheKey) }.getOrNull()
            }
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
