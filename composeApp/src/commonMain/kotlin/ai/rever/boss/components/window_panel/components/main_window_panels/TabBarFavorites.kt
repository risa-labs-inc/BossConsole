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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val BOOKMARKS_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.bookmarks"

/** A bounded shortcut shelf. The caller supplies only bookmarks marked as favorites. */
@Composable
fun TabBarFavorites(
    bookmarks: List<Bookmark>,
    pluginInstalled: Boolean?,
    apiReachable: Boolean,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onInstallPlugin: () -> Unit,
    trailing: @Composable () -> Unit = {},
    tabDragComponent: TabDraggableComponent? = null,
    onEdit: ((Bookmark) -> Unit)? = null,
    onDelete: ((Bookmark) -> Unit)? = null,
    onOpenAll: (() -> Unit)? = null,
    onOpenNew: ((Bookmark) -> Unit)? = null,
) {
    val windowId = LocalWindowId.current
    val openAll =
        onOpenAll ?: windowId?.takeIf { pluginInstalled == true && apiReachable }?.let { id ->
            { MenuActionsHandler.triggerRevealPlugin(id, PanelIds.BOOKMARKS.panelId) }
        }
    val borderColor =
        if (tabDragComponent?.dropTarget is TabDropTarget.Favorites) BossTheme.colors.signal else Color.Transparent
    DisposableEffect(tabDragComponent) {
        onDispose { tabDragComponent?.registerFavoritesBounds(null) }
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { tabDragComponent?.registerFavoritesBounds(it.boundsInWindow()) }
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        FavoritesHeader(trailing, openAll.takeIf { apiReachable })
        when {
            pluginInstalled == false -> {
                FavoritesEmptyState(
                    "Add Favorites",
                    "Save bookmarks for pages, files and terminals you use often.",
                    "Install Bookmarks",
                    onInstallPlugin,
                )
            }

            !apiReachable -> {
                FavoritesEmptyState(
                    "Bookmarks unavailable",
                    "The Bookmarks plugin is installed but not running. Check the Toolbox.",
                )
            }

            bookmarks.isEmpty() -> {
                Text(
                    "Right-click a tab to add a favorite.",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .border(1.dp, BossTheme.colors.line, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                )
            }

            else -> {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        FavoriteRow(bookmark, onOpen, onRemove, onEdit, onDelete, onOpenNew)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesHeader(
    trailing: @Composable () -> Unit,
    onOpenAll: (() -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "FAVORITES",
            color = BossTheme.colors.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (onOpenAll != null) {
            HoverTooltipBox(text = "All Bookmarks") {
                IconButton(onClick = onOpenAll, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Bookmarks,
                        contentDescription = "All Bookmarks",
                        tint = BossTheme.colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        trailing()
    }
}

private fun favoriteSubtitle(bookmark: Bookmark): String {
    val config = bookmark.tabConfig
    val location = config.filePath?.takeIf { it.isNotBlank() } ?: config.workingDirectory?.takeIf { it.isNotBlank() }
    return location ?: config.url
        ?.let { url ->
            url
                .substringAfter("://")
                .substringBefore('/')
                .substringAfterLast('@')
                .substringBefore('?')
                .substringBefore('#')
        }.orEmpty()
}

private fun favoriteTypeIcon(bookmark: Bookmark): ImageVector =
    when {
        bookmark.tabConfig.type.contains("terminal", ignoreCase = true) -> Icons.Outlined.Terminal
        bookmark.tabConfig.filePath != null -> Icons.AutoMirrored.Outlined.InsertDriveFile
        bookmark.tabConfig.url != null -> Icons.Outlined.Language
        else -> Icons.Outlined.Extension
    }

@Composable
private fun FavoriteRow(
    bookmark: Bookmark,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onEdit: ((Bookmark) -> Unit)?,
    onDelete: ((Bookmark) -> Unit)?,
    onOpenNew: ((Bookmark) -> Unit)?,
) {
    val config = bookmark.tabConfig
    val subtitle = favoriteSubtitle(bookmark)
    val title = config.title.ifBlank { subtitle.ifBlank { "Saved tab" } }
    val terminal = config.type.contains("terminal", ignoreCase = true)
    val icon = favoriteTypeIcon(bookmark)
    var favicon by remember(config.url, config.faviconCacheKey) { mutableStateOf<TabIcon.Image?>(null) }
    LaunchedEffect(config.url, config.faviconCacheKey) {
        if (!config.url.isNullOrBlank()) {
            favicon = loadHighQualityFavicon(config.url, config.faviconCacheKey)
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (hovered) BossTheme.colors.signal else BossTheme.colors.textPrimary
    val openLabel = if (terminal) "Open New Terminal" else "Open"
    val menu =
        buildList {
            add(ContextMenuItem(openLabel, onClick = { onOpen(bookmark) }))
            if (!terminal && onOpenNew != null) {
                add(ContextMenuItem("Open in New Tab", onClick = { onOpenNew(bookmark) }))
            }
            onEdit?.let { add(ContextMenuItem("Edit Bookmark", onClick = { it(bookmark) })) }
            add(ContextMenuItem("Remove from Favorites", onClick = { onRemove(bookmark) }))
            onDelete?.let { add(ContextMenuItem("Delete Bookmark", onClick = { it(bookmark) })) }
        }
    HoverTooltipBox(
        text =
            listOfNotNull(
                title,
                config.url ?: subtitle,
                if (terminal) "Open New Terminal" else null,
                config.initialCommand?.takeIf { terminal && it.isNotBlank() }?.let { "Startup command: $it" },
            ).filter { it.isNotBlank() }.joinToString("\n"),
        placement = TooltipPlacement.END,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .hoverable(interaction)
                    .contextMenu(items = menu)
                    .clickable(role = Role.Button, onClickLabel = openLabel) { onOpen(bookmark) }
                    .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FavoriteRowContent(title, subtitle, tint, icon, favicon) { onRemove(bookmark) }
        }
    }
}

@Composable
private fun RowScope.FavoriteRowContent(
    title: String,
    subtitle: String,
    tint: Color,
    icon: ImageVector,
    image: TabIcon.Image?,
    onRemove: () -> Unit,
) {
    if (image != null) {
        Image(image.painter, contentDescription = null, modifier = Modifier.size(18.dp))
    } else {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
    Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(title, color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = tint,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
        Icon(
            Icons.Default.Star,
            contentDescription = "Remove $title from Favorites",
            tint = BossTheme.colors.signal,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FavoritesEmptyState(
    headline: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(headline, color = BossTheme.colors.textPrimary, fontSize = 12.sp)
        Text(body, color = BossTheme.colors.textSecondary, fontSize = 11.sp)
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = BossTheme.colors.signal, fontSize = 11.sp) }
        }
    }
}
