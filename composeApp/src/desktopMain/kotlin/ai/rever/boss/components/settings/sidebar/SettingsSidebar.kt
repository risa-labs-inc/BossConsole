package ai.rever.boss.components.settings.sidebar

import ai.rever.boss.components.settings.search.SettingsSearchHit
import ai.rever.boss.components.settings.search.SettingsSearchResults
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.plugin.search.BossSearchBar
import ai.rever.boss.plugin.ui.BossThemes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings section enum with display metadata for BossTerm-style navigation.
 */
enum class SettingsSection(
    val displayName: String,
    val description: String,
    val icon: ImageVector,
) {
    FLUCK(
        displayName = "Browser",
        description = "Configure browser behavior, user agent, and link handling",
        icon = Icons.Outlined.Language,
    ),
    BROWSER_ENGINE(
        displayName = "Browser Engine",
        description = "Chromium engine version, downloads, and reinstall",
        icon = Icons.Outlined.Memory,
    ),
    DEFAULT_APPS(
        displayName = "Default Apps",
        description = "Links, markdown, shell scripts and code files BOSS opens",
        icon = Icons.Outlined.AppRegistration,
    ),
    BOSS_EDITOR(
        displayName = "BossEditor",
        description = "Scroll behavior, code folding, and bracket matching",
        icon = Icons.Outlined.Edit,
    ),
    LANGUAGE_SERVERS(
        displayName = "Language Servers",
        description = "LSP configuration, completions, and diagnostics",
        icon = Icons.Outlined.Hub,
    ),
    TERMINAL(
        displayName = "Terminal",
        description = "Shell configuration, colors, and startup behavior",
        icon = Icons.Outlined.Terminal,
    ),
    RUNNER(
        displayName = "Runner",
        description = "Run/stop behavior and terminal target options",
        icon = Icons.Outlined.PlayArrow,
    ),
    WORKSPACE(
        displayName = "Space",
        description = "Default layout and space templates",
        icon = Icons.Outlined.GridView,
    ),
    UPDATES(
        displayName = "Updates",
        description = "Auto-update preferences and version information",
        icon = Icons.Outlined.SystemUpdate,
    ),
    SECURITY(
        displayName = "Security",
        description = "WebAuthn, Touch ID, and authentication options",
        icon = Icons.Outlined.Security,
    ),
    KEYMAP(
        displayName = "Shortcuts",
        description = "Keyboard shortcuts and keymap presets",
        icon = Icons.Outlined.Keyboard,
    ),
    FOCUS_MODE(
        displayName = "Focus Mode",
        description = "Hide UI elements and minimize distractions",
        icon = Icons.Outlined.Visibility,
    ),
    THEME(
        displayName = "Theme",
        description = "App color theme - " + BossThemes.all.joinToString { it.name },
        icon = Icons.Outlined.Palette,
    ),
    WINDOW_APPEARANCE(
        displayName = "Appearance",
        description = "Title bar, tab sizing, and window decorations",
        icon = Icons.Outlined.DesktopWindows,
    ),
    SIDEBAR(
        displayName = "Sidebar",
        description = "Plugin icon limits and overflow behavior",
        icon = Icons.AutoMirrored.Outlined.ViewSidebar,
    ),
    PERFORMANCE(
        displayName = "Performance",
        description = "Memory and CPU monitoring settings",
        icon = Icons.Outlined.Speed,
    ),
    STARTUP(
        displayName = "Startup",
        description = "Launch behavior and initialization options",
        icon = Icons.Outlined.RocketLaunch,
    ),
    SCROLLBAR(
        displayName = "Scrollbars",
        description = "Scrollbar appearance and behavior settings",
        icon = Icons.Outlined.LinearScale,
    ),
    ADVANCED(
        displayName = "Advanced",
        description = "Process mode, microkernel, and self-healing settings",
        icon = Icons.Outlined.Science,
    ),
    ;

    companion object {
        val default = FLUCK
    }
}

private val NavRailWidth = 180.dp

@Composable
fun SettingsSidebar(
    selectedSection: SettingsSection,
    onSectionChange: (SettingsSection) -> Unit,
    pluginPages: List<ai.rever.boss.plugin.api.SettingsPageProvider> = emptyList(),
    selectedPluginPageId: String? = null,
    onPluginPageChange: (String) -> Unit = {},
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    hits: List<SettingsSearchHit> = emptyList(),
    selectedHitIndex: Int = 0,
    onHitPicked: (SettingsSearchHit) -> Unit = {},
    searchFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier =
            Modifier
                .width(NavRailWidth)
                .fillMaxHeight()
                .background(SurfaceColor),
    ) {
        // Pinned above the list rather than scrolled with it: the field is how you get out of a
        // long result list, so it must not be the thing that scrolls away.
        BossSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "Search settings",
            modifier =
                Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .then(searchFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        )

        if (query.isNotBlank()) {
            SettingsSearchResults(
                hits = hits,
                selectedIndex = selectedHitIndex,
                onPick = onHitPicked,
                modifier = Modifier.weight(1f),
            )
        } else {
            SectionRail(
                selectedSection = selectedSection,
                onSectionChange = onSectionChange,
                pluginPages = pluginPages,
                selectedPluginPageId = selectedPluginPageId,
                onPluginPageChange = onPluginPageChange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The nav rail as it has always been: every section, then plugin pages under a divider. */
@Composable
private fun SectionRail(
    selectedSection: SettingsSection,
    onSectionChange: (SettingsSection) -> Unit,
    pluginPages: List<ai.rever.boss.plugin.api.SettingsPageProvider>,
    selectedPluginPageId: String?,
    onPluginPageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier =
            modifier
                .verticalScroll(scrollState)
                .padding(bottom = 8.dp),
    ) {
        SettingsSection.entries.forEach { section ->
            val isSelected = selectedPluginPageId == null && section == selectedSection
            NavigationRailItem(
                displayName = section.displayName,
                icon = section.icon,
                isSelected = isSelected,
                onClick = { onSectionChange(section) },
            )
        }

        // Plugin-contributed settings pages (SettingsPageRegistry) under a
        // "Plugins" divider — fully dynamic, appear/disappear with plugins.
        if (pluginPages.isNotEmpty()) {
            Divider(
                color = TextSecondary.copy(alpha = 0.2f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                text = "PLUGINS",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            pluginPages.forEach { page ->
                NavigationRailItem(
                    displayName = page.displayName,
                    icon = page.icon,
                    isSelected = page.pageId == selectedPluginPageId,
                    onClick = { onPluginPageChange(page.pageId) },
                )
            }
        }
    }
}

/**
 * Single navigation rail item with BossTerm-style selection indicator.
 */
@Composable
private fun NavigationRailItem(
    displayName: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (isSelected) AccentColor.copy(alpha = 0.15f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Selection indicator bar (3dp wide, 20dp tall)
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isSelected) AccentColor else Color.Transparent),
        )

        Icon(
            imageVector = icon,
            contentDescription = displayName,
            tint = if (isSelected) AccentColor else TextSecondary,
            modifier = Modifier.size(18.dp),
        )

        Text(
            text = displayName,
            color = if (isSelected) AccentColor else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
