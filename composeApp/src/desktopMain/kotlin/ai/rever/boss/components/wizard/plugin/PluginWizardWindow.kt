package ai.rever.boss.components.wizard.plugin

import androidx.compose.runtime.Composable

/**
 * Desktop implementation - uses DialogWindow for a proper separate window.
 */
@Composable
actual fun PluginWizardWindow(
    state: PluginInstallWizardState,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onInstallPlugins: suspend (List<WizardPluginInfo>, (Float, String) -> Unit) -> Result<PluginInstallResult>,
) {
    PluginInstallWizardWindow(
        state = state,
        onDismiss = onDismiss,
        onComplete = onComplete,
        onInstallPlugins = onInstallPlugins,
    )
}
