package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.performance.PerformanceState
import kotlinx.coroutines.launch
import ai.rever.boss.components.bars.getBarScrollbarConfig
import ai.rever.boss.components.bars.horizontalScrollWithScrollbar
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.Project
import ai.rever.boss.components.plugin.registries.StatusBarRegistryImpl
import ai.rever.boss.components.plugin.registries.owningPluginId
import ai.rever.boss.plugin.api.StatusBarAlignment
import ai.rever.boss.plugin.sandbox.ui.PluginExtensionBoundary
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState


@Composable
fun BossBottomBar(tabsComponent: BossTabsComponent? = null) {
    Divider(color = BossTheme.colors.line)
    HorizontalBar(height = 30.dp) {
        HorizontalBarRow {
            BossLeftBottomBar(tabsComponent)
            PluginStatusBarItems(StatusBarAlignment.LEFT)
            Spacer(modifier = Modifier.weight(0.1f))
            PluginStatusBarItems(StatusBarAlignment.RIGHT)
            BossRightBottomBar()
        }
    }
}

/**
 * Plugin-contributed status-bar widgets (StatusBarRegistry) for one alignment
 * group. Each widget renders inside a [PluginExtensionBoundary]: a crash
 * attributed to the owning plugin collapses that widget to a compact error
 * marker instead of corrupting the status bar (or, for a plugin with no
 * other boundary, escalating to the app-level CrashHandler).
 */
@Composable
private fun PluginStatusBarItems(alignment: StatusBarAlignment) {
    val items by StatusBarRegistryImpl.items.collectAsState()
    val access by StatusBarRegistryImpl.access.collectAsState()
    val visible = remember(items, access, alignment) {
        StatusBarRegistryImpl.visibleItems(alignment)
    }
    visible.forEach { provider ->
        key(provider.itemId) {
            PluginExtensionBoundary(
                pluginId = owningPluginId(provider),
                surface = "status item ${provider.itemId}"
            ) {
                provider.Content()
            }
        }
    }
}

@Composable
fun RightArrow() {
    Icon(imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        modifier = Modifier.size(18.dp),
        contentDescription = "Right Arrow",
        tint = BossTheme.colors.textSecondary)
}

@Composable
fun RowScope.BossLeftBottomBar(tabsComponent: BossTabsComponent? = null) {
    Column(modifier = Modifier.weight(2f).padding(horizontal = 8.dp)) {
        Row(
            modifier = Modifier
                .horizontalScrollWithScrollbar(
                    rememberScrollState(),
                    scrollbarConfig = getBarScrollbarConfig()
                )
            ,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Collect state once at the top level to avoid multiple subscriptions (per-window)
            val windowProjectState = LocalWindowProjectState.current
            val currentProject by windowProjectState?.selectedProject?.collectAsState()
                ?: remember { mutableStateOf(Project("No Project", "", 0L)) }

            if (tabsComponent != null) {
                val tabsState by tabsComponent.tabsState.subscribeAsState()
                val activeTab = tabsState.activeTab

                when (activeTab) {
                    is EditorTabInfo -> {
                        // Show file path from project root
                        val projectRoot = currentProject.path.let {
                            if (it.endsWith("/")) it else "$it/"
                        }
                        val relativePath = activeTab.filePath.removePrefix(projectRoot)
                        val pathParts = relativePath.split("/")

                        pathParts.forEachIndexed { index, part ->
                            if (part.isNotEmpty()) {
                                BossActionButton(
                                    text = part,
                                    color = BossTheme.colors.textSecondary,
                                    onClick = {}
                                )
                                if (index < pathParts.lastIndex && pathParts[index + 1].isNotEmpty()) {
                                    RightArrow()
                                }
                            }
                        }
                    }
                    is FluckTabInfo -> {
                        // Show current URL
                        Text(
                            text = activeTab.currentUrl,
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    is TerminalTabInfo -> {
                        // Show terminal title (e.g., "user@hostname:/path")
                        Text(
                            text = activeTab.title,
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    null -> {
                        // Explicitly handle null case (no tab active)
                        Text(
                            text = "${currentProject.name} | Ready",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    else -> {
                        // Handle unknown tab types
                        Text(
                            text = "${currentProject.name} | ${activeTab.title}",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            } else {
                // Show minimal content if no tabs component (shouldn't happen in normal use)
                Text(
                    text = "Ready",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun BossRightBottomBar() {
    val windowId = LocalWindowId.current
    val scope = rememberCoroutineScope()

    // Status message (temporary messages like "Workspace Saved")
    val statusMessage by StatusMessageManager.currentMessage.collectAsState()
    statusMessage?.let { message ->
        Text(
            text = message,
            color = BossTheme.colors.ok, // Green color for success
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }

    // Performance indicator (shows memory/CPU usage)
    val showIndicator = PerformanceState.shouldShowIndicator()
    if (showIndicator) {
        val snapshot = PerformanceState.currentSnapshot()
        val health = PerformanceState.currentHealth()
        PerformanceIndicator(
            snapshot = snapshot,
            health = health,
            onClick = { PerformanceState.togglePerformancePanel() }
        )
    }

    BossActionButton(
        imageVector = Icons.Outlined.Info,
        text = "Console",
        color = BossTheme.colors.textSecondary,
        onClick = {
            // Toggle Console panel (PanelId "console" with order 14)
            windowId?.let { wid ->
                scope.launch {
                    PanelEventBus.togglePanel(PanelId("console", 14), sourceWindowId = wid)
                }
            }
        }
    )
}
