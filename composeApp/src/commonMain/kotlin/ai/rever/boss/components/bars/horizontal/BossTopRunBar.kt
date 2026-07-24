package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.icons.LanguageIcons
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.run.Language
import ai.rever.boss.run.RunConfiguration
import ai.rever.boss.run.RunConfigurationManager
import ai.rever.boss.run.RunnerTerminalService
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowRunnerState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal
import kotlinx.coroutines.launch

/**
 * Run bar component for the top bar.
 * Shows run configuration selector, run/re-run button, and stop button.
 *
 * Issue #347: Square buttons with run/stop state management
 * - Idle state: Run (green), Stop (grayed out)
 * - Running state: Re-run (green), Stop (red active)
 *
 * IntelliJ-style behavior: Only shows previously run configurations (run history),
 * not auto-detected configurations. Auto-detection is handled by a separate plugin.
 */
@Composable
fun BossTopRunBar() {
    val scope = rememberCoroutineScope()
    // Issue #498: Get window ID for multi-window support
    val windowId = LocalWindowId.current ?: return
    val windowRunnerState = LocalWindowRunnerState.current ?: return
    val settings by RunConfigurationManager.currentSettings.collectAsState()
    val selectedConfig by windowRunnerState.selectedConfiguration.collectAsState()

    // Get run history - configurations that have been explicitly run
    val runHistory = settings.configurations

    // Issue #498: Observe configToWindows to trigger recomposition when window-config mappings change
    // This ensures the stop button updates immediately when a run starts in any window
    val configToWindows by RunnerTerminalService.configToWindows.collectAsState()
    val isSelectedConfigRunning =
        selectedConfig?.let { config ->
            configToWindows[config.id]?.contains(windowId) == true
        } ?: false

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Configuration selector dropdown - shows only run history (IntelliJ style)
        RunConfigurationSelector(
            selectedConfig = selectedConfig,
            runHistory = runHistory,
            isConfigRunning = { configId ->
                // Issue #498: Window-scoped check for running state using observed StateFlow
                configToWindows[configId]?.contains(windowId) == true
            },
            onSelect = { config ->
                windowRunnerState.selectConfiguration(config)
            },
            onRun = { config ->
                scope.launch {
                    windowRunnerState.selectConfiguration(config)
                    RunnerTerminalService.openRunnerTerminal(config, windowId)
                    RunConfigurationManager.addConfiguration(config)
                }
            },
            onRerun = { config ->
                scope.launch {
                    windowRunnerState.selectConfiguration(config)
                    RunnerTerminalService.rerunRunner(config, windowId)
                }
            },
            onStop = { config ->
                scope.launch {
                    windowRunnerState.selectConfiguration(config)
                    RunnerTerminalService.stopRunner(windowId, config.id)
                }
            },
            onDelete = { config ->
                scope.launch {
                    RunConfigurationManager.removeConfiguration(config.id)
                }
            },
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Run/Re-run button (square, green)
        RunSquareButton(
            icon = if (isSelectedConfigRunning) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
            backgroundColor = BossTheme.colors.ok, // run green
            enabled = selectedConfig != null,
            contentDescription = if (isSelectedConfigRunning) "Re-run" else "Run",
            onClick = {
                selectedConfig?.let { config ->
                    scope.launch {
                        if (isSelectedConfigRunning) {
                            // Re-run: stop and run again
                            RunnerTerminalService.rerunRunner(config, windowId)
                        } else {
                            // First run
                            RunnerTerminalService.openRunnerTerminal(config, windowId)
                        }
                        // Also add to run history
                        RunConfigurationManager.addConfiguration(config)
                    }
                }
            },
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Stop button (square, red when active, gray when disabled)
        RunSquareButton(
            icon = Icons.Outlined.Stop,
            backgroundColor = BossTheme.colors.alert, // stop red
            enabled = isSelectedConfigRunning,
            contentDescription = "Stop",
            onClick = {
                selectedConfig?.let { config ->
                    scope.launch {
                        RunnerTerminalService.stopRunner(windowId, config.id)
                    }
                }
            },
        )
    }
}

/**
 * Square button for run/stop actions.
 * Issue #347: Square button styling with icon only.
 */
@Composable
private fun RunSquareButton(
    icon: ImageVector,
    backgroundColor: Color,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Calculate colors based on state
    val bgColor =
        when {
            !enabled -> BossTheme.colors.raised

            // Muted background when disabled
            isHovered -> backgroundColor.copy(alpha = 0.9f)

            // Slightly darker on hover
            else -> backgroundColor
        }
    val iconColor =
        when {
            !enabled -> BossTheme.colors.textMuted

            // Muted icon when disabled
            else -> BossTheme.colors.onSignal
        }

    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor)
                .hoverable(interactionSource)
                .then(
                    if (enabled) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Dropdown selector for run configurations.
 * IntelliJ-style: Only shows run history (previously executed configurations).
 * - Not running: [Play] [Delete]
 * - Running: [Rerun] [Stop]
 * Clicking any action button also selects that configuration.
 *
 * Issue #498: Uses window-scoped running check instead of global set.
 */
@Composable
private fun RunConfigurationSelector(
    selectedConfig: RunConfiguration?,
    runHistory: List<RunConfiguration>,
    isConfigRunning: (String) -> Boolean,
    onSelect: (RunConfiguration) -> Unit,
    onRun: (RunConfiguration) -> Unit,
    onRerun: (RunConfiguration) -> Unit,
    onStop: (RunConfiguration) -> Unit,
    onDelete: (RunConfiguration) -> Unit,
) {
    // Build context menu items from run history only
    val contextMenuItems =
        buildList {
            if (runHistory.isNotEmpty()) {
                // Show run history with action buttons based on running state
                runHistory.forEach { config ->
                    val isRunning = isConfigRunning(config.id)
                    add(
                        ContextMenuItem(
                            text = config.name,
                            icon = getLanguageIcon(config.language),
                            onClick = { onSelect(config) },
                            // Primary action: Play (not running) or Rerun (running)
                            trailingIcon = if (isRunning) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                            trailingIconColor = BossTheme.colors.ok, // Green for both play and rerun
                            onTrailingClick = { if (isRunning) onRerun(config) else onRun(config) },
                            // Secondary action: Delete (not running) or Stop (running)
                            secondaryTrailingIcon = if (isRunning) Icons.Outlined.Stop else Icons.Outlined.Close,
                            secondaryTrailingIconColor = if (isRunning) BossTheme.colors.alert else BossTheme.colors.textSecondary,
                            onSecondaryTrailingClick = { if (isRunning) onStop(config) else onDelete(config) },
                        ),
                    )
                }
            } else {
                // No run history yet
                add(
                    ContextMenuItem(
                        text = "No run history",
                        icon = null,
                        onClick = {},
                    ),
                )
                add(
                    ContextMenuItem(
                        text = "Run a file to add it here",
                        icon = null,
                        onClick = {},
                    ),
                )
            }
        }

    BossActionButton(
        leftIcon = selectedConfig?.let { getLanguageIcon(it.language) } ?: Icons.Outlined.Code,
        text = selectedConfig?.name ?: "Run History",
        contextMenuItems = contextMenuItems,
        hintText = selectedConfig?.let { "Configuration: ${it.filePath}" } ?: "Select from run history",
    )
}

/**
 * Get the appropriate icon for a programming language.
 * Uses official brand icons from LanguageIcons.
 */
private fun getLanguageIcon(language: Language): ImageVector =
    when (language) {
        Language.KOTLIN -> LanguageIcons.kotlin
        Language.JAVA -> LanguageIcons.java
        Language.PYTHON -> LanguageIcons.python
        Language.JAVASCRIPT -> LanguageIcons.javascript
        Language.TYPESCRIPT -> LanguageIcons.typescript
        Language.GO -> LanguageIcons.go
        Language.RUST -> LanguageIcons.rust
        Language.UNKNOWN -> FeatherIcons.Terminal
    }
