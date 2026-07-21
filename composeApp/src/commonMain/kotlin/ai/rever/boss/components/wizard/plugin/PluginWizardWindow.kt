package ai.rever.boss.components.wizard.plugin

import androidx.compose.runtime.Composable

/**
 * Platform-specific plugin wizard window.
 * On desktop, this shows a proper DialogWindow.
 * On other platforms, this shows a Dialog overlay.
 */
@Composable
expect fun PluginWizardWindow(
    state: PluginInstallWizardState,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onInstallPlugins: suspend (List<WizardPluginInfo>, (Float, String) -> Unit) -> Result<PluginInstallResult>
)
