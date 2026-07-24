package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.AvailablePluginUpdate
import ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Upgrade
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val UpdateBadgeColor: Color get() = BossThemeColors.SuccessColor

@Composable
fun BossPanelTopBar(
    title: String?,
    isHovered: Boolean,
    onReset: (() -> Unit)? = null,
    onReloadPlugin: (() -> Unit)? = null,
    onOpenAsTab: (() -> Unit)? = null,
    onCheckForUpdates: (() -> Unit)? = null,
    onOpenEvolver: (() -> Unit)? = null,
    onReportIssue: (() -> Unit)? = null,
    onMinimize: () -> Unit,
    updateAvailable: AvailablePluginUpdate? = null,
    onUpdateClick: (() -> Unit)? = null,
    panelId: PanelId? = null,
    windowId: String? = null,
    dragModifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    // Plugin-contributed menu items for this panel (PanelMenuRegistry). The
    // registry map and RBAC snapshot trigger a re-query, so items track
    // plugin lifecycle and role changes. Contributions change their item set
    // by re-registering (items() must stay cheap — see PanelMenuContribution).
    val contributions by PanelMenuRegistryImpl.contributions.collectAsState()
    val access by PanelMenuRegistryImpl.access.collectAsState()
    val pluginEntries =
        if (panelId != null) {
            remember(panelId, contributions, access) {
                PanelMenuRegistryImpl.itemsFor(panelId)
            }
        } else {
            emptyList()
        }

    // One menu definition, shared by the "…" kebab and the right-click context menu,
    // so both offer identical options. Plugin items render between the
    // built-ins and Minimize.
    val menuItems =
        buildList {
            onReset?.let { cb -> add(ContextMenuItem(text = "Restart Panel", icon = Icons.Outlined.RestartAlt, onClick = cb)) }
            onReloadPlugin?.let { cb -> add(ContextMenuItem(text = "Reload Plugin", icon = Icons.Outlined.Refresh, onClick = cb)) }
            onCheckForUpdates?.let { cb -> add(ContextMenuItem(text = "Check for Updates", icon = Icons.Outlined.Upgrade, onClick = cb)) }
            onOpenEvolver?.let { cb -> add(ContextMenuItem(text = "Open Evolver", icon = Icons.Outlined.MonitorHeart, onClick = cb)) }
            onReportIssue?.let { cb -> add(ContextMenuItem(text = "Report Issue", icon = Icons.Outlined.BugReport, onClick = cb)) }
            onOpenAsTab?.let { cb -> add(ContextMenuItem(text = "Open as Tab", icon = Icons.Outlined.Tab, onClick = cb)) }
            if (pluginEntries.isNotEmpty() && panelId != null) {
                add(ContextMenuItem(isDivider = true))
                for ((contribution, item) in pluginEntries) {
                    if (!item.enabled) continue
                    add(
                        ContextMenuItem(text = item.label, icon = item.icon, onClick = {
                            PanelMenuRegistryImpl.onItemClick(contribution, panelId, item.id, windowId)
                        }),
                    )
                }
            }
            add(ContextMenuItem(text = "Minimize", icon = Icons.Outlined.Remove, onClick = onMinimize))
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(BossTheme.colors.raised)
                .then(dragModifier)
                .contextMenu(items = menuItems),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = title ?: "",
            color = BossThemeColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier =
                Modifier
                    .align(Alignment.CenterVertically),
        )

        Spacer(modifier = Modifier.weight(1f))

        // "Update available" badge — always visible (not hover-gated) when a compatible update
        // exists for this plugin. Clicking it prompts to update.
        if (updateAvailable != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onUpdateClick?.invoke() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Upgrade,
                    contentDescription = "Update available: v${updateAvailable.currentVersion} → v${updateAvailable.newVersion}",
                    tint = UpdateBadgeColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Update",
                    color = UpdateBadgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // State for dropdown menu (moved outside AnimatedVisibility to be accessible in condition)
        var showMenu by remember { mutableStateOf(false) }
        val buttonHeightRef = remember { intArrayOf(0) }

        AnimatedVisibility(
            visible = isHovered || showMenu, // Keep visible while menu is open
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(modifier = Modifier.padding(end = 2.dp)) {
                content?.invoke()

                // More button — opens the same menu as right-click
                Box(
                    modifier =
                        Modifier.onGloballyPositioned { coordinates ->
                            buttonHeightRef[0] = coordinates.size.height
                        },
                ) {
                    BossActionButton(
                        imageVector = Icons.Outlined.MoreVert,
                        text = "More",
                        color = BossThemeColors.TextPrimary,
                        onClick = { showMenu = true },
                    )

                    if (showMenu) {
                        ContextMenu(
                            items = menuItems,
                            offset = IntOffset(0, buttonHeightRef[0]),
                            onDismissRequest = { showMenu = false },
                        )
                    }
                }

                BossActionButton(
                    imageVector = Icons.Outlined.Remove,
                    text = "Minimize",
                    color = BossThemeColors.TextPrimary,
                    onClick = onMinimize,
                )
            }
        }
    }
}
