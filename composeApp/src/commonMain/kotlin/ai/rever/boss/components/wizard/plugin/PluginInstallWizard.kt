package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.wizard.CheckboxCard
import ai.rever.boss.components.wizard.WizardNote
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun WizardHeader(
    currentStep: PluginInstallStep,
    onBack: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = BossTheme.colors.textSecondary,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentStep.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary,
            )
            currentStep.category?.let { category ->
                Text(
                    text = category.description,
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary,
                )
            }
        }

        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = BossTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
internal fun WelcomeStepContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.HomeRepairService,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = BossTheme.colors.signal,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Welcome to the BOSS Toolbox",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Customize your workspace by selecting the tools you need.\nWe'll help you get started with some recommended essentials.",
            fontSize = 14.sp,
            color = BossTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BossTheme.colors.raised)
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Rocket,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = BossTheme.colors.signal,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Essential tools will be pre-selected for you",
                fontSize = 13.sp,
                color = BossTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun CategoryStepContent(
    category: PluginCategory,
    plugins: List<WizardPluginInfo>,
    isPluginSelected: (String) -> Boolean,
    onTogglePlugin: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Select All / Deselect All buttons
        if (plugins.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onSelectAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signal),
                ) {
                    Text("Select All", fontSize = 12.sp)
                }
                TextButton(
                    onClick = onDeselectAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
                ) {
                    Text("Deselect All", fontSize = 12.sp)
                }
            }
        }

        // Plugin list
        if (plugins.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No tools available in this category",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(plugins, key = { it.id }) { plugin ->
                    CheckboxCard(
                        title = plugin.name,
                        description = plugin.description,
                        icon = plugin.icon,
                        isChecked = isPluginSelected(plugin.id),
                        onCheckedChange = { onTogglePlugin(plugin.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Note about Plugin Manager
        WizardNote(
            text = "You can manage these tools later in the Toolbox",
        )
    }
}

@Composable
internal fun InstallingStepContent(
    progress: Float,
    status: String,
    error: String?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (error != null) {
            // Error state
            Text(
                text = "Installation Failed",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.8f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BossTheme.colors.raised)
                        .padding(16.dp),
            ) {
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = BossTheme.colors.alert,
                    lineHeight = 20.sp,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRetry,
                colors =
                    ButtonDefaults.buttonColors(
                        backgroundColor = BossTheme.colors.signal,
                        contentColor = BossTheme.colors.onSignal,
                    ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Try Again")
            }
        } else {
            // Installing state
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = BossTheme.colors.signal,
                strokeWidth = 4.dp,
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Installing Tools",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = status,
                fontSize = 14.sp,
                color = BossTheme.colors.textSecondary,
            )

            Spacer(modifier = Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = progress,
                modifier =
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                color = BossTheme.colors.signal,
                backgroundColor = BossTheme.colors.raised,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${(progress * 100).toInt()}%",
                fontSize = 12.sp,
                color = BossTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun CompleteStepContent(
    installedCount: Int,
    failedPlugins: List<Pair<String, String>> = emptyList(),
) {
    val hasFailures = failedPlugins.isNotEmpty()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = if (hasFailures) "Partial Success" else "Success",
            modifier = Modifier.size(72.dp),
            tint = if (hasFailures) BossTheme.colors.warn else BossTheme.colors.ok,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (hasFailures) "Installation Complete" else "You're All Set!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text =
                if (installedCount > 0) {
                    "$installedCount tool${if (installedCount > 1) "s" else ""} installed successfully"
                } else {
                    "No tools were selected for installation"
                },
            fontSize = 14.sp,
            color = BossTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        // Show failed plugins if any
        if (hasFailures) {
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BossTheme.colors.raised)
                        .padding(12.dp),
            ) {
                Column {
                    Text(
                        text = "${failedPlugins.size} tool${if (failedPlugins.size > 1) "s" else ""} failed to install:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossTheme.colors.warn,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    failedPlugins.forEach { (pluginId, error) ->
                        Text(
                            text = "\u2022 $pluginId: $error",
                            fontSize = 12.sp,
                            color = BossTheme.colors.textSecondary,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "You can retry installing these tools from the Toolbox",
                fontSize = 13.sp,
                color = BossTheme.colors.textSecondary.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        } else {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "You can install more tools anytime from the Toolbox",
                fontSize = 13.sp,
                color = BossTheme.colors.textSecondary.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
internal fun WizardNavigation(
    currentStep: PluginInstallStep,
    isInstalling: Boolean,
    hasSelectedPlugins: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (currentStep) {
            is PluginInstallStep.Welcome -> {
                Button(
                    onClick = onNext,
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                        ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("Get Started", fontWeight = FontWeight.Medium)
                }
            }

            is PluginInstallStep.EssentialPlugins,
            is PluginInstallStep.DeveloperPlugins,
            is PluginInstallStep.ProductivityPlugins,
            is PluginInstallStep.AutomationPlugins,
            is PluginInstallStep.AdminPlugins,
            is PluginInstallStep.OtherPlugins,
            -> {
                // Skip remaining steps button
                if (currentStep.canSkip) {
                    TextButton(
                        onClick = onSkip,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = BossTheme.colors.textSecondary,
                            ),
                    ) {
                        Text("Skip to Install")
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                }

                Button(
                    onClick = onNext,
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                        ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    // Show "Install" on the last category step (Other)
                    val isLastCategoryStep = currentStep is PluginInstallStep.OtherPlugins
                    Text(
                        text = if (isLastCategoryStep) "Install" else "Next",
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            is PluginInstallStep.Installing -> {
                // No navigation during installation
            }

            is PluginInstallStep.Complete -> {
                Button(
                    onClick = onFinish,
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                        ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("Start Using BOSS", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
