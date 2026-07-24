package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.wizard.WizardStepIndicator
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.launch

/**
 * Desktop-specific plugin installation wizard that opens in a separate window.
 *
 * This is a proper window (DialogWindow) rather than an overlay dialog,
 * similar to BossTerm's onboarding wizard.
 *
 * @param state The wizard state
 * @param onDismiss Callback when the wizard window should be closed
 * @param onComplete Callback when installation is complete
 * @param onInstallPlugins Callback to perform the actual plugin installation
 */
@Composable
fun PluginInstallWizardWindow(
    state: PluginInstallWizardState,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onInstallPlugins: suspend (List<WizardPluginInfo>, (Float, String) -> Unit) -> Result<PluginInstallResult>
) {
    val currentStep = state.wizardState.currentStep

    // Handle installation when we reach the Installing step
    // Use installationAttempted flag to prevent re-triggering (fixes race condition)
    LaunchedEffect(currentStep) {
        if (currentStep is PluginInstallStep.Installing && !state.isInstalling && !state.installationAttempted) {
            val selectedPlugins = state.getSelectedPlugins()
            if (selectedPlugins.isEmpty()) {
                // No plugins selected, skip to complete
                state.completeInstallation(emptyList())
                state.goToNextStep()
            } else {
                state.startInstallation()
                val result = onInstallPlugins(selectedPlugins) { progress, status ->
                    state.updateProgress(progress, status)
                }
                result.fold(
                    onSuccess = { installResult ->
                        state.completeInstallation(installResult.installedIds, installResult.failedPlugins)
                        state.goToNextStep()
                    },
                    onFailure = { error ->
                        state.failInstallation(error.message ?: "Installation failed")
                    }
                )
            }
        }
    }

    DialogWindow(
        onCloseRequest = {
            // Allow users to close the wizard window
            onDismiss()
        },
        title = "BOSS Plugin Setup",
        resizable = false,
        state = rememberDialogState(size = DpSize(700.dp, 600.dp))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BossTheme.colors.panel
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    WizardHeader(
                        currentStep = currentStep,
                        onBack = if (!state.wizardState.isFirstStep && !state.isInstalling && currentStep !is PluginInstallStep.Complete) {
                            { state.goToPreviousStep() }
                        } else null,
                        onDismiss = null  // Remove cross button - users must complete wizard or skip
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Step indicator (hidden during Installing and Complete)
                    if (currentStep !is PluginInstallStep.Installing && currentStep !is PluginInstallStep.Complete) {
                        WizardStepIndicator(
                            currentStep = state.wizardState.currentVisibleStepIndex + 1,
                            totalSteps = state.wizardState.totalVisibleSteps,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // Content
                    Box(modifier = Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = currentStep,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "wizard_step_content"
                        ) { step ->
                            when (step) {
                                is PluginInstallStep.Welcome -> WelcomeStepContent()
                                is PluginInstallStep.EssentialPlugins,
                                is PluginInstallStep.DeveloperPlugins,
                                is PluginInstallStep.ProductivityPlugins,
                                is PluginInstallStep.AutomationPlugins,
                                is PluginInstallStep.AdminPlugins,
                                is PluginInstallStep.OtherPlugins -> {
                                    step.category?.let { category ->
                                        CategoryStepContent(
                                            category = category,
                                            plugins = state.getPluginsForCategory(category),
                                            isPluginSelected = { state.isPluginSelected(it) },
                                            onTogglePlugin = { state.togglePlugin(it) },
                                            onSelectAll = { state.selectAllInCategory(category) },
                                            onDeselectAll = { state.deselectAllInCategory(category) }
                                        )
                                    }
                                }
                                is PluginInstallStep.Installing -> InstallingStepContent(
                                    progress = state.installationProgress,
                                    status = state.installationStatus,
                                    error = state.installationError,
                                    onRetry = {
                                        state.reset()
                                    }
                                )
                                is PluginInstallStep.Complete -> CompleteStepContent(
                                    installedCount = state.installedPluginIds.size,
                                    failedPlugins = state.failedPlugins
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Navigation buttons
                    WizardNavigation(
                        currentStep = currentStep,
                        isInstalling = state.isInstalling,
                        hasSelectedPlugins = state.hasSelectedPlugins(),
                        onBack = { state.goToPreviousStep() },
                        onNext = { state.goToNextStep() },
                        onSkip = {
                            // Skip all remaining category steps and go to Installing
                            state.skipToInstalling()
                        },
                        onFinish = onComplete
                    )
                }
            }
        }
    }
}
