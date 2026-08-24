package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.AvailablePluginUpdate
import ai.rever.boss.components.plugin.PanelMenuActions
import ai.rever.boss.components.plugin.PluginBuildInfo
import ai.rever.boss.components.plugin.PluginBuildTag
import ai.rever.boss.components.plugin.panelMenuItems
import ai.rever.boss.layout.BossChrome
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Upgrade
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val UpdateBadgeColor: Color get() = BossThemeColors.SuccessColor

@Composable
fun BossPanelTopBar(
    title: String?,
    isHovered: Boolean,
    onReloadPlugin: (() -> Unit)? = null,
    onOpenAsTab: (() -> Unit)? = null,
    onCheckForUpdates: (() -> Unit)? = null,
    onOpenEvolver: (() -> Unit)? = null,
    onReportIssue: (() -> Unit)? = null,
    onUninstallPlugin: (() -> Unit)? = null,
    uninstallEnabled: Boolean = true,
    onMinimize: () -> Unit,
    updateAvailable: AvailablePluginUpdate? = null,
    onUpdateClick: (() -> Unit)? = null,
    buildInfo: PluginBuildInfo? = null,
    onBuildTagClick: (() -> Unit)? = null,
    panelId: PanelId? = null,
    windowId: String? = null,
    dragModifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    // One menu definition, shared by the "…" kebab, the right-click context menu here and the
    // sidebar rail icon's right-click menu, so all three offer identical options. See panelMenuItems.
    val menuItems =
        panelMenuItems(
            panelId = panelId,
            windowId = windowId,
            actions =
                PanelMenuActions(
                    buildInfo = buildInfo,
                    updateAvailable = updateAvailable,
                    installStoreVersion = onBuildTagClick,
                    reloadPanel = onReloadPlugin,
                    checkForUpdates = onCheckForUpdates,
                    openEvolver = onOpenEvolver,
                    reportIssue = onReportIssue,
                    uninstallPlugin = onUninstallPlugin,
                    uninstallEnabled = uninstallEnabled,
                ),
            onOpenAsTab = onOpenAsTab,
            onMinimize = onMinimize,
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(BossChrome.dimens.panelTopBarHeight)
                .background(BossTheme.colors.raised)
                .then(dragModifier)
                .contextMenu(items = menuItems),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(8.dp))

        // Title and tag are one group, and the group takes all the free space. This has to be a
        // nested Row rather than a weighted title beside a weighted spacer: two weights in one Row
        // split the free space 1:1, and because the title is fill = false, the half it did not use
        // was laid out AFTER the trailing controls - so Minimize and the kebab sat short of the
        // right edge by half the title's unused width, drifting further in the shorter the title.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title ?: "",
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        // Give way rather than grow: an unbounded title in a narrow side panel would push
                        // the build tag - the entire signal - off the end of the row. fill = false so a
                        // short title still hugs its text and the tag sits next to it, not at the edge.
                        .weight(1f, fill = false),
            )

            // Next to the name, not out at the edge: the tag qualifies which build of this panel you
            // are looking at, so it belongs with the thing it qualifies. Not hover-gated - a panel
            // running unreleased code should say so whether or not the pointer is over it.
            if (buildInfo?.isTagged == true) {
                Spacer(modifier = Modifier.width(6.dp))
                PluginBuildTag(
                    info = buildInfo,
                    onClick = onBuildTagClick,
                )
            }
        }

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
