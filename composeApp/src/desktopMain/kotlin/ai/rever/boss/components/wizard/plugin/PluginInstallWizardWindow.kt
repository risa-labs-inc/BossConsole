package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.ui.BossTheme
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
    val canContinueToTerminalSetup = state.canContinueToTerminalSetup()
    RunInstallation(currentStep, state, onInstallPlugins)

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
        WizardWindowContent(state, currentStep, canContinueToTerminalSetup, onDismiss, onComplete, onSetupBossTerm)
    }
}

@Composable
internal fun RunInstallation(
    currentStep: PluginInstallStep,
    state: PluginInstallWizardState,
    install: suspend (List<WizardPluginInfo>, (Float, String) -> Unit) -> Result<PluginInstallResult>,
) {
    // Do not key this effect on installationAttempted: startInstallation changes it and would
    // cancel its own installation coroutine as it leaves composition.
    LaunchedEffect(currentStep, state.installationRunId) {
        val shouldInstall =
            currentStep is PluginInstallStep.Installing &&
                !state.isInstalling &&
                !state.installationAttempted
        if (!shouldInstall) return@LaunchedEffect
        val selectedPlugins = state.getInstallationPlugins()
        if (selectedPlugins.isEmpty()) {
            state.completeInstallation(emptyList())
            state.goToNextStep()
            return@LaunchedEffect
        }
        state.startInstallation()
        install(selectedPlugins, state::updateProgress).fold(
            onSuccess = {
                state.completeInstallation(it.installedIds, it.failedPlugins)
                state.goToNextStep()
            },
            onFailure = { state.failInstallation(it.message ?: "Installation failed") },
        )
    }
}

@Composable
private fun WizardWindowContent(
    state: PluginInstallWizardState,
    currentStep: PluginInstallStep,
    bossTermReady: Boolean,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onSetupBossTerm: () -> Unit,
) {
    ProvideTextStyle(BossTheme.type.body) {
        Surface(Modifier.fillMaxSize(), color = BossTheme.colors.ink) {
            Box(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.fillMaxHeight().widthIn(max = 760.dp)) {
                    WizardHeader(currentStep, state.backAction(currentStep), currentStep.dismissAction(onDismiss))
                    Spacer(Modifier.height(22.dp))
                    Box(Modifier.weight(1f)) {
                        WizardStepContent(state, currentStep, bossTermReady, onComplete, onSetupBossTerm)
                    }
                    Spacer(Modifier.height(18.dp))
                    if (currentStep !is PluginInstallStep.Complete || !bossTermReady) {
                        WizardNavigation(
                            currentStep,
                            state.getSelectedPlugins().size,
                            state.selectedProfile != null,
                            state::goToNextStep,
                            onComplete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStepContent(
    state: PluginInstallWizardState,
    currentStep: PluginInstallStep,
    bossTermReady: Boolean,
    onComplete: () -> Unit,
    onSetupBossTerm: () -> Unit,
) {
    AnimatedContent(
        targetState = currentStep,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "wizard_step_content",
    ) { step ->
        when (step) {
            is PluginInstallStep.Welcome -> {
                WelcomeStepContent()
            }

            is PluginInstallStep.Profile -> {
                ProfileStepContent(
                    state.selectedProfile,
                    state::recommendedToolCount,
                    state::applyProfile,
                )
            }

            is PluginInstallStep.Review -> {
                ReviewStepContent(
                    state.availablePlugins,
                    state::isPluginSelected,
                    state::togglePlugin,
                    state.selectedProfile,
                )
            }

            is PluginInstallStep.Installing -> {
                InstallingStepContent(
                    state.installationProgress,
                    state.installationStatus,
                    state.installationError,
                    state.getInstallationPlugins(),
                    state::prepareInstallationRetry,
                )
            }

            is PluginInstallStep.Complete -> {
                CompleteStepContent(
                    state.installedPluginIds.size,
                    state.failedPlugins,
                    bossTermReady,
                    onSetupBossTerm,
                    onComplete,
                    onRetryFailed = state::retryFailedPlugins,
                )
            }
        }
    }
}

private fun PluginInstallWizardState.canContinueToTerminalSetup(): Boolean =
    canOfferTerminalSetup(
        terminalAlreadyInstalled = PluginPersistence.isInstalled(TERMINAL_TAB_PLUGIN_ID),
        installedPluginIds = installedPluginIds,
        failedPlugins = failedPlugins,
    )

/** The offer follows Terminal Tab's own result; an unrelated tool failure must not hide it. */
internal fun canOfferTerminalSetup(
    terminalAlreadyInstalled: Boolean = false,
    installedPluginIds: List<String>,
    failedPlugins: List<Pair<String, String>>,
): Boolean =
    (terminalAlreadyInstalled || TERMINAL_TAB_PLUGIN_ID in installedPluginIds) &&
        failedPlugins.none { it.first == TERMINAL_TAB_PLUGIN_ID }

private fun PluginInstallWizardState.backAction(step: PluginInstallStep): (() -> Unit)? =
    if (!wizardState.isFirstStep && !isInstalling && step !is PluginInstallStep.Complete) {
        ::goToPreviousStep
    } else {
        null
    }

private fun PluginInstallStep.dismissAction(onDismiss: () -> Unit): (() -> Unit)? =
    when (this) {
        is PluginInstallStep.Welcome, is PluginInstallStep.Profile, is PluginInstallStep.Review -> onDismiss
        is PluginInstallStep.Installing, is PluginInstallStep.Complete -> null
    }

private const val TERMINAL_TAB_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.terminaltab"
