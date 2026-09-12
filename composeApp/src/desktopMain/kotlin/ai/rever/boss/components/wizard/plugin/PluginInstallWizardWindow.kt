package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.terminal.TerminalAPIAccess
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

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
    onSetupBossTerm: () -> Unit,
    onInstallPlugins: suspend (List<WizardPluginInfo>, (Float, String) -> Unit) -> Result<PluginInstallResult>,
) {
    val currentStep = state.wizardState.currentStep
    val terminalInstalled =
        TERMINAL_TAB_PLUGIN_ID in state.installedPluginIds &&
            state.failedPlugins.none { it.first == TERMINAL_TAB_PLUGIN_ID }
    val canContinueToTerminalSetup =
        terminalInstalled && state.failedPlugins.isEmpty() && TerminalAPIAccess.getProvider() != null

    // Do not key this effect on installationAttempted: startInstallation changes that
    // value and would cancel its own installation coroutine as it leaves composition.
    LaunchedEffect(currentStep, state.installationRunId) {
        if (currentStep is PluginInstallStep.Installing && !state.isInstalling && !state.installationAttempted) {
            val selectedPlugins = state.getSelectedPlugins()
            if (selectedPlugins.isEmpty()) {
                // No plugins selected, skip to complete
                state.completeInstallation(emptyList())
                state.goToNextStep()
            } else {
                state.startInstallation()
                val result =
                    onInstallPlugins(selectedPlugins) { progress, status ->
                        state.updateProgress(progress, status)
                    }
                result.fold(
                    onSuccess = { installResult ->
                        state.completeInstallation(installResult.installedIds, installResult.failedPlugins)
                        state.goToNextStep()
                    },
                    onFailure = { error ->
                        state.failInstallation(error.message ?: "Installation failed")
                    },
                )
            }
        }
    }

    DialogWindow(
        onCloseRequest = {
            // Cancelling this composition also cancels the observing installation coroutine.
            // Keep the window alive until this batch reaches a terminal state.
            if (currentStep !is PluginInstallStep.Installing || state.installationError != null) onDismiss()
        },
        title = "BOSS Toolbox Setup",
        resizable = false,
        state = rememberDialogState(size = DpSize(900.dp, 650.dp)),
        icon = BossWindowIcon.painter,
    ) {
        ApplyBossWindowIcon(window)
        ProvideTextStyle(BossTheme.type.body) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = BossTheme.colors.ink,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight().widthIn(max = 760.dp),
                    ) {
                        WizardHeader(
                            currentStep = currentStep,
                            onBack =
                                if (!state.wizardState.isFirstStep && !state.isInstalling && currentStep !is PluginInstallStep.Complete) {
                                    { state.goToPreviousStep() }
                                } else {
                                    null
                                },
                            onDismiss =
                                if (
                                    currentStep is PluginInstallStep.Welcome ||
                                    currentStep is PluginInstallStep.Profile ||
                                    currentStep is PluginInstallStep.Review
                                ) {
                                    onDismiss
                                } else {
                                    null
                                },
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = currentStep,
                                transitionSpec = {
                                    fadeIn() togetherWith fadeOut()
                                },
                                label = "wizard_step_content",
                            ) { step ->
                                when (step) {
                                    is PluginInstallStep.Welcome -> {
                                        WelcomeStepContent()
                                    }

                                    is PluginInstallStep.Profile -> {
                                        ProfileStepContent(
                                            selectedProfile = state.selectedProfile,
                                            toolCount = state::recommendedToolCount,
                                            onSelectProfile = state::applyProfile,
                                        )
                                    }

                                    is PluginInstallStep.Review -> {
                                        ReviewStepContent(
                                            plugins = state.availablePlugins,
                                            isPluginSelected = state::isPluginSelected,
                                            onTogglePlugin = state::togglePlugin,
                                            selectedProfile = state.selectedProfile,
                                        )
                                    }

                                    is PluginInstallStep.Installing -> {
                                        InstallingStepContent(
                                            progress = state.installationProgress,
                                            status = state.installationStatus,
                                            error = state.installationError,
                                            plugins = state.getSelectedPlugins(),
                                            onRetry = state::prepareInstallationRetry,
                                        )
                                    }

                                    is PluginInstallStep.Complete -> {
                                        CompleteStepContent(
                                            installedCount = state.installedPluginIds.size,
                                            failedPlugins = state.failedPlugins,
                                            bossTermReady = canContinueToTerminalSetup,
                                            onSetupBossTerm = onSetupBossTerm,
                                            onFinish = onComplete,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        if (!(currentStep is PluginInstallStep.Complete && canContinueToTerminalSetup)) {
                            WizardNavigation(
                                currentStep = currentStep,
                                selectedCount = state.getSelectedPlugins().size,
                                profileSelected = state.selectedProfile != null,
                                onNext = { state.goToNextStep() },
                                onFinish = onComplete,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val TERMINAL_TAB_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.terminaltab"
