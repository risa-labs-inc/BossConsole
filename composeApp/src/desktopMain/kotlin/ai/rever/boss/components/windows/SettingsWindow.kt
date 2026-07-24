package ai.rever.boss.components.windows

import ai.rever.boss.components.settings.sections.*
import ai.rever.boss.components.settings.keymap.EditableKeymapSettings
import ai.rever.boss.components.plugin.registries.SettingsPageRegistryImpl
import ai.rever.boss.components.settings.sidebar.SettingsSection
import ai.rever.boss.components.settings.sidebar.SettingsSidebar
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BackgroundColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BorderColor
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.config.BrowserEngineSettingsManager
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.updater.UpdateSettingsSection
import ai.rever.boss.utils.DisplayUtils
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.scrollbar.ScrollbarSettingsManager
import ai.rever.boss.startup.StartupSettingsManager
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import BossTheme
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

@Composable
actual fun SettingsWindow(
    onClose: () -> Unit,
    initialSection: String?
) {
    var isOpen by remember { mutableStateOf(true) }

    if (isOpen) {
        Window(
            onCloseRequest = {
                isOpen = false
                onClose()
            },
            title = "BOSS Settings",
            state = rememberWindowState(
                size = DisplayUtils.calculateSettingsWindowSize(),
                position = WindowPosition.Aligned(Alignment.Center)
            )
        ) {
            BossTheme {
                SettingsContent(initialSection = initialSection)
            }
        }
    }
}

@Composable
private fun SettingsContent(initialSection: String? = null) {
    // Convert initial section string to enum, defaulting to FLUCK. A string
    // that instead matches a plugin page id (SettingsPageRegistry) deep-
    // navigates to that page.
    val startSection = remember(initialSection) {
        initialSection?.let { name ->
            SettingsSection.entries.find { it.name.equals(name, ignoreCase = true) }
        } ?: SettingsSection.FLUCK
    }
    var selectedSection by remember { mutableStateOf(startSection) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Plugin-contributed settings pages: reactive to plugin lifecycle + RBAC.
    val registryPages by SettingsPageRegistryImpl.pages.collectAsState()
    val registryAccess by SettingsPageRegistryImpl.access.collectAsState()
    val pluginPages = remember(registryPages, registryAccess) {
        SettingsPageRegistryImpl.visiblePages()
    }
    var selectedPluginPageId by remember(initialSection) {
        mutableStateOf(
            initialSection?.takeIf { candidate ->
                SettingsSection.entries.none { it.name.equals(candidate, ignoreCase = true) } &&
                    SettingsPageRegistryImpl.visiblePage(candidate) != null
            }
        )
    }
    // If the selected page's plugin is disabled/unloaded, fall back to sections.
    LaunchedEffect(pluginPages) {
        if (selectedPluginPageId != null && pluginPages.none { it.pageId == selectedPluginPageId }) {
            selectedPluginPageId = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BackgroundColor
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // Left navigation rail
            SettingsSidebar(
                selectedSection = selectedSection,
                onSectionChange = {
                    selectedPluginPageId = null
                    selectedSection = it
                },
                pluginPages = pluginPages,
                selectedPluginPageId = selectedPluginPageId,
                onPluginPageChange = { selectedPluginPageId = it }
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(BorderColor)
            )

            // Right content area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(BackgroundColor)
            ) {
                // Content with scrolling
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val pluginPage = selectedPluginPageId?.let { id -> pluginPages.firstOrNull { it.pageId == id } }
                    if (pluginPage != null) {
                        PluginSettingsPageArea(
                            page = pluginPage,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        SettingsContentArea(
                            section = selectedSection,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Footer with auto-save message and reset button
                SettingsFooter(
                    onResetClick = { showResetConfirmation = true }
                )
            }
        }
    }

    // Reset confirmation dialog
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = {
                Text(
                    text = "Reset Settings?",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "This will reset all settings to their default values. This action cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            // Reset all settings managers to defaults
                            BrowserEngineSettingsManager.resetToDefault()
                            PerformanceSettingsManager.resetToDefault()
                            FocusModeSettingsManager.resetToDefault()
                            RunnerSettingsManager.resetToDefault()
                            ScrollbarSettingsManager.resetToDefault()
                            StartupSettingsManager.resetToDefault()
                            TerminalLinkSettingsManager.resetToDefault()
                        }
                        showResetConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = BossThemeController.current.colors.alert
                    )
                ) {
                    // On an alert fill, use the on-fill ink token, not TextPrimary.
                    Text("Reset", color = BossThemeController.current.colors.onSignal)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmation = false }
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            backgroundColor = SurfaceColor,
            contentColor = TextPrimary
        )
    }
}

/**
 * Content area for a plugin-contributed settings page: same header treatment
 * as built-in sections. Page content renders inside a
 * [ai.rever.boss.plugin.sandbox.ui.PluginExtensionBoundary] — a crash
 * attributed to the owning plugin replaces the page with an error notice
 * instead of killing the settings window. Pages with [SettingsPageProvider
 * .selfScrolling] own their scrolling (LazyColumn-friendly, mirroring the
 * embedded built-in sections); others get the host's vertical scroll.
 */
@Composable
private fun PluginSettingsPageArea(
    page: ai.rever.boss.plugin.api.SettingsPageProvider,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    val columnModifier = if (page.selfScrolling) {
        modifier.padding(20.dp)
    } else {
        modifier
            .verticalScroll(scrollState)
            .padding(20.dp)
    }
    Column(modifier = columnModifier) {
        Text(
            text = page.displayName,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = page.description,
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        val pageBoundary: @Composable () -> Unit = {
            ai.rever.boss.plugin.sandbox.ui.PluginExtensionBoundary(
                pluginId = ai.rever.boss.components.plugin.registries.owningPluginId(page),
                surface = "settings page ${page.pageId}",
                fallback = { error ->
                    Text(
                        text = "This settings page crashed: ${error.message ?: error::class.simpleName}. " +
                            "Reload the plugin to try again.",
                        color = BossThemeController.current.colors.alert,
                        fontSize = 13.sp
                    )
                }
            ) {
                page.Content()
            }
        }
        if (page.selfScrolling) {
            // Bounded height so the page's own LazyColumn/scroll can fill it.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                pageBoundary()
            }
        } else {
            pageBoundary()
        }
    }
}

/**
 * Content area displaying the selected category's settings with header.
 */
@Composable
private fun SettingsContentArea(
    section: SettingsSection,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Sections that embed external panels or use LazyColumn (can't nest in verticalScroll)
    val embeddedPanelSections = setOf(
        SettingsSection.TERMINAL,
        SettingsSection.BOSS_EDITOR,
        SettingsSection.KEYMAP  // Uses LazyColumn for shortcuts list
    )
    val isEmbeddedPanel = section in embeddedPanelSections

    if (isEmbeddedPanel) {
        // Embedded panels handle their own scrolling but get consistent header styling
        Column(
            modifier = modifier.padding(20.dp)
        ) {
            // Category header (same as regular sections)
            Text(
                text = section.displayName,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = section.description,
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // Embedded panel content (handles its own scrolling)
            Box(modifier = Modifier.weight(1f)) {
                when (section) {
                    SettingsSection.TERMINAL -> TerminalSettings()
                    SettingsSection.BOSS_EDITOR -> BossEditorSettings()
                    SettingsSection.KEYMAP -> EditableKeymapSettings()
                    else -> {}
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .verticalScroll(scrollState)
                .padding(20.dp)
        ) {
            // Category header
            Text(
                text = section.displayName,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = section.description,
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // Category-specific content
            when (section) {
                SettingsSection.FLUCK -> FluckBrowserSettings()
                SettingsSection.BROWSER_ENGINE -> BrowserEngineSettings()
                SettingsSection.RUNNER -> RunnerSettings()
                SettingsSection.WORKSPACE -> WorkspaceSettings()
                SettingsSection.LLM_PROVIDERS -> LLMProvidersSettings()
                SettingsSection.UPDATES -> UpdatesSettings()
                SettingsSection.SECURITY -> SecuritySettings()
                SettingsSection.LANGUAGE_SERVERS -> LspSettings()
                SettingsSection.FOCUS_MODE -> FocusModeSettings()
                SettingsSection.WINDOW_APPEARANCE -> WindowAppearanceSettings()
                SettingsSection.PERFORMANCE -> PerformanceSettings()
                SettingsSection.STARTUP -> StartupSettingsSection()
                SettingsSection.SCROLLBAR -> ScrollbarSettings()
                SettingsSection.SIDEBAR -> SidebarSettings()
                SettingsSection.ADVANCED -> AdvancedSettings()
                SettingsSection.THEME -> ThemeSettings()
                else -> {}
            }
        }
    }
}

/**
 * Footer with auto-save message and reset button.
 */
@Composable
private fun SettingsFooter(
    onResetClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Changes are saved automatically",
                color = TextMuted,
                fontSize = 12.sp
            )
            TextButton(
                onClick = onResetClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = TextSecondary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset to Defaults", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun UpdatesSettings() {
    UpdateSettingsSection()
}
