package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
internal fun WizardHeader(
    currentStep: PluginInstallStep,
    onBack: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = BossTheme.colors.textSecondary,
                    )
                }
            }
            if (onBack != null) Spacer(Modifier.width(10.dp))
            Text(
                text = "BOSS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = BossTheme.colors.textPrimary,
                letterSpacing = 2.sp,
            )
        }

        CompactStepIndicator(currentStep)

        if (onDismiss != null) {
            Text(
                text = "Set up later",
                fontSize = 10.sp,
                color = BossTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onDismiss).padding(8.dp),
            )
        }
    }
}

@Composable
private fun CompactStepIndicator(currentStep: PluginInstallStep) {
    val activeIndex =
        when (currentStep) {
            is PluginInstallStep.Welcome -> -1
            is PluginInstallStep.Profile -> 0
            is PluginInstallStep.Review -> 1
            is PluginInstallStep.Installing -> 2
            is PluginInstallStep.Complete -> 3
        }
    val labels = listOf("Profile", "Review tools", "Install")
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == activeIndex
            val complete = index < activeIndex
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (complete) BossTheme.colors.ok else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) BossTheme.colors.signal else BossTheme.colors.line,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (complete) {
                    Icon(Icons.Default.Check, "Completed", Modifier.size(12.dp), BossTheme.colors.onSignal)
                } else {
                    Text(
                        text = (index + 1).toString(),
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                        textAlign = TextAlign.Center,
                        color = if (active) BossTheme.colors.signalText else BossTheme.colors.textMuted,
                    )
                }
            }
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) BossTheme.colors.textPrimary else BossTheme.colors.textMuted,
            )
            if (index < labels.lastIndex) {
                Box(Modifier.width(20.dp).height(1.dp).background(BossTheme.colors.line))
            }
        }
    }
}

@Composable
internal fun WelcomeStepContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Set up your BOSS workspace",
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text =
                "Start with a toolbox shaped around the way you work. You will see and approve " +
                    "every tool before BOSS installs anything.",
            fontSize = 14.sp,
            color = BossTheme.colors.textSecondary,
            lineHeight = 21.sp,
            modifier = Modifier.fillMaxWidth(0.72f),
        )

        Spacer(modifier = Modifier.height(36.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BossTheme.colors.raised.copy(alpha = 0.55f))
                    .border(1.dp, BossTheme.colors.line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SetupOverviewItem("01", "Choose your profile", "Tell BOSS what kind of work you do.", Modifier.weight(1f))
            SetupOverviewItem("02", "Review every tool", "Adjust the exact installation list.", Modifier.weight(1f))
            SetupOverviewItem("03", "Install", "Watch each tool as it is prepared.", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "No usage data or workspace content is needed to make this recommendation.",
            fontSize = 9.sp,
            color = BossTheme.colors.textMuted,
        )
    }
}

@Composable
private fun SetupOverviewItem(
    number: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        Text(number, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BossTheme.colors.signalText)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = BossTheme.colors.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(description, fontSize = 10.sp, lineHeight = 14.sp, color = BossTheme.colors.textMuted)
        }
    }
}

@Composable
internal fun ProfileStepContent(
    selectedProfile: ToolboxProfile?,
    toolCount: (ToolboxProfile) -> Int,
    onSelectProfile: (ToolboxProfile) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Which best matches your work?",
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This only sets the initial tool selection. It does not affect permissions, and you can change tools next.",
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(18.dp))
        val profileListState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = profileListState,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(ToolboxProfile.entries, key = { it.name }) { profile ->
                    val icon =
                        when (profile) {
                            ToolboxProfile.EVERYTHING -> Icons.Default.Extension
                            ToolboxProfile.DEVELOPER -> Icons.Default.Code
                            ToolboxProfile.PRODUCT_DESIGN -> Icons.Default.Lightbulb
                            ToolboxProfile.OPERATIONS_AUTOMATION -> Icons.Default.Settings
                            ToolboxProfile.GENERAL -> Icons.Default.HomeRepairService
                        }
                    ProfileChoiceRow(profile, toolCount(profile), icon, selectedProfile == profile) {
                        onSelectProfile(profile)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(profileListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ProfileChoiceRow(
    profile: ToolboxProfile,
    toolCount: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(BossTheme.colors.raised.copy(alpha = if (selected) 0.8f else 0.42f))
            .border(1.dp, if (selected) BossTheme.colors.signal else BossTheme.colors.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(BossTheme.colors.panel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), if (selected) BossTheme.colors.signalText else BossTheme.colors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BossTheme.colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(profile.description, fontSize = 10.sp, color = BossTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text("$toolCount tools", fontSize = 10.sp, color = BossTheme.colors.textSecondary)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .border(1.dp, if (selected) BossTheme.colors.signal else BossTheme.colors.lineStrong, CircleShape)
                .background(if (selected) BossTheme.colors.signal else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, "Selected", Modifier.size(11.dp), BossTheme.colors.onSignal)
        }
    }
}

@Composable
internal fun ReviewStepContent(
    plugins: List<WizardPluginInfo>,
    isPluginSelected: (String) -> Boolean,
    onTogglePlugin: (String) -> Unit,
    selectedProfile: ToolboxProfile? = null,
) {
    val selectedCount = plugins.count { isPluginSelected(it.id) }
    val selectedPlugins = plugins.filter { isPluginSelected(it.id) }
    val dependencyCount = selectedPlugins.count { it.id == AI_GATEWAY_PLUGIN_ID }
    val coreCount = selectedPlugins.count { it.isMandatory || it.isDefault } - dependencyCount
    val profileCount = (selectedCount - coreCount - dependencyCount).coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            reviewTitle(selectedProfile),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "$selectedCount tools selected. Review and adjust the exact installation list.",
            fontSize = 12.sp,
            color = BossTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(13.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(BossTheme.colors.raised.copy(alpha = 0.35f))
                .border(1.dp, BossTheme.colors.line, RoundedCornerShape(8.dp)),
        ) {
            ReviewMetric(selectedCount.toString(), "selected", Modifier.weight(1f))
            ReviewMetric(coreCount.toString(), "core", Modifier.weight(1f))
            ReviewMetric(profileCount.toString(), "for your work", Modifier.weight(1f))
            ReviewMetric(dependencyCount.toString(), if (dependencyCount == 1) "dependency" else "dependencies", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        val reviewGridState = rememberLazyGridState()
        Box(modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = reviewGridState,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                gridItems(plugins, key = { it.id }) { plugin ->
                    PluginChoiceCard(plugin, isPluginSelected(plugin.id)) { onTogglePlugin(plugin.id) }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(reviewGridState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (selectedPlugins.any { it.id == FLUCK_AGENT_PLUGIN_ID } && dependencyCount > 0) {
            DependencyNote()
        } else {
            Text(
                "Required workspace tools stay selected. You can change this later in Toolbox.",
                fontSize = 10.sp,
                color = BossTheme.colors.textMuted,
            )
        }
    }
}

private fun reviewTitle(profile: ToolboxProfile?): String =
    when (profile) {
        ToolboxProfile.EVERYTHING -> "Complete setup"
        ToolboxProfile.DEVELOPER -> "Development setup"
        ToolboxProfile.PRODUCT_DESIGN -> "Product and design setup"
        ToolboxProfile.OPERATIONS_AUTOMATION -> "Operations and automation setup"
        ToolboxProfile.GENERAL -> "General setup"
        null -> "Review your toolbox"
    }

@Composable
private fun ReviewMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = BossTheme.colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 9.sp, color = BossTheme.colors.textMuted)
    }
}

@Composable
private fun DependencyNote() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BossTheme.colors.signalWash.copy(alpha = 0.35f))
            .border(1.dp, BossTheme.colors.signal.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).border(1.dp, BossTheme.colors.signal, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("i", fontSize = 9.sp, color = BossTheme.colors.signalText)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "AI Gateway is included because Fluck Agent requires it.",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary,
            )
            Text(
                "BOSS AI is available to signed-in BOSS users. No API key is required.",
                fontSize = 8.sp,
                color = BossTheme.colors.textMuted,
            )
        }
    }
}

private const val AI_GATEWAY_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.aigateway"
private const val FLUCK_AGENT_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.fluckagent"

@Composable
internal fun InstallingStepContent(
    progress: Float,
    status: String,
    error: String?,
    plugins: List<WizardPluginInfo>,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        if (error != null) {
            Text(
                text = "Installation needs attention",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary,
            )

            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = "No changes will be made until the installer can continue safely.",
                fontSize = 12.sp,
                color = BossTheme.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(18.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BossTheme.colors.raised.copy(alpha = 0.6f))
                        .border(1.dp, BossTheme.colors.line, RoundedCornerShape(8.dp))
                        .padding(14.dp),
            ) {
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = BossTheme.colors.alert,
                    lineHeight = 20.sp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

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
            Text(
                text = "Installing your toolbox",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(status, fontSize = 12.sp, color = BossTheme.colors.textSecondary)
                Text(
                    "${(progress * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BossTheme.colors.signalText,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                color = BossTheme.colors.signal,
                backgroundColor = BossTheme.colors.raised,
            )

            Spacer(modifier = Modifier.height(14.dp))
            val completedCount = (progress * plugins.size).toInt().coerceIn(0, plugins.size)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                gridItems(plugins, key = { it.id }) { plugin ->
                    val index = plugins.indexOf(plugin)
                    val label =
                        when {
                            index < completedCount -> "Installed"
                            index == completedCount -> "Installing"
                            else -> "Waiting"
                        }
                    ToolInstallRow(plugin, label)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "$completedCount of ${plugins.size} tools installed",
                fontSize = 11.sp,
                color = BossTheme.colors.textSecondary,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun ToolInstallRow(
    plugin: WizardPluginInfo,
    status: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(BossTheme.colors.raised.copy(alpha = 0.65f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        plugin.icon?.let {
            Icon(it, null, Modifier.size(18.dp), tint = BossTheme.colors.textSecondary)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(plugin.name, fontSize = 12.sp, color = BossTheme.colors.textPrimary)
            Text("v${plugin.version}", fontSize = 10.sp, color = BossTheme.colors.textMuted)
        }
        Text(
            status,
            fontSize = 10.sp,
            color = if (status == "Installed") BossTheme.colors.ok else BossTheme.colors.textSecondary,
        )
    }
}

@Composable
internal fun CompleteStepContent(
    installedCount: Int,
    failedPlugins: List<Pair<String, String>> = emptyList(),
    bossTermReady: Boolean = false,
    onSetupBossTerm: (() -> Unit)? = null,
    onFinish: (() -> Unit)? = null,
) {
    val hasFailures = failedPlugins.isNotEmpty()

    if (bossTermReady && onSetupBossTerm != null && onFinish != null) {
        BossTermOfferContent(
            installedCount = installedCount,
            onSetupBossTerm = onSetupBossTerm,
            onFinish = onFinish,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (hasFailures) Icons.Outlined.Warning else Icons.Default.CheckCircle,
            contentDescription = if (hasFailures) "Installation errors" else "Success",
            modifier = Modifier.size(72.dp),
            tint = if (hasFailures) BossTheme.colors.warn else BossTheme.colors.ok,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (hasFailures) "Installation incomplete" else "You're All Set!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text =
                if (installedCount > 0) {
                    "$installedCount tool${if (installedCount > 1) "s" else ""} installed successfully"
                } else if (hasFailures) {
                    "No tools were installed successfully"
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
                    Column(
                        modifier =
                            Modifier
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
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
private fun BossTermOfferContent(
    installedCount: Int,
    onSetupBossTerm: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BossTheme.colors.raised)
                .border(1.dp, BossTheme.colors.lineStrong, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Terminal, null, Modifier.size(24.dp), BossTheme.colors.ok)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "OPTIONAL SETUP",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
            color = BossTheme.colors.signalText,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Set up BOSS Term too?",
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$installedCount tools are ready. BOSS Term was installed with your workspace and has its own short setup.",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = BossTheme.colors.textSecondary,
            modifier = Modifier.fillMaxWidth(0.78f),
        )
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier
                .fillMaxWidth(0.78f)
                .clip(RoundedCornerShape(8.dp))
                .background(BossTheme.colors.raised.copy(alpha = 0.45f))
                .border(1.dp, BossTheme.colors.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(21.dp).clip(CircleShape).background(BossTheme.colors.ok.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(13.dp), BossTheme.colors.ok)
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("BOSS Term is installed", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BossTheme.colors.textPrimary)
                Text("Continue with shell and terminal preferences", fontSize = 9.sp, color = BossTheme.colors.textMuted)
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSetupBossTerm,
                colors =
                    ButtonDefaults.buttonColors(
                        backgroundColor = BossTheme.colors.signal,
                        contentColor = BossTheme.colors.onSignal,
                    ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Text("Set up BOSS Term", fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onFinish,
                colors =
                    ButtonDefaults.buttonColors(
                        backgroundColor = BossTheme.colors.raised,
                        contentColor = BossTheme.colors.textSecondary,
                    ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(40.dp),
            ) {
                Text("Not now")
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("You can run BOSS Term setup later from its settings.", fontSize = 9.sp, color = BossTheme.colors.textMuted)
    }
}

@Composable
internal fun WizardNavigation(
    currentStep: PluginInstallStep,
    selectedCount: Int,
    profileSelected: Boolean = true,
    onNext: () -> Unit,
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
                    Text("Continue", fontWeight = FontWeight.Medium)
                }
            }

            is PluginInstallStep.Profile,
            is PluginInstallStep.Review,
            -> {
                Button(
                    onClick = onNext,
                    enabled = selectedCount > 0 && (currentStep !is PluginInstallStep.Profile || profileSelected),
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                        ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        text = if (currentStep is PluginInstallStep.Review) "Install $selectedCount tools" else "Review selected tools",
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

@Composable
private fun PluginChoiceCard(
    plugin: WizardPluginInfo,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val interactionModifier =
        if (plugin.isMandatory) {
            Modifier.semantics(mergeDescendants = true) {
                role = Role.Checkbox
                toggleableState = ToggleableState.On
            }
        } else {
            Modifier.toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BossTheme.colors.raised.copy(alpha = 0.48f))
            .border(
                1.dp,
                if (selected) BossTheme.colors.signal.copy(alpha = 0.28f) else BossTheme.colors.line,
                RoundedCornerShape(8.dp),
            ).then(interactionModifier)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) BossTheme.colors.signal else BossTheme.colors.panel)
                .border(1.dp, if (selected) BossTheme.colors.signal else BossTheme.colors.lineStrong, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, "Selected", Modifier.size(12.dp), BossTheme.colors.onSignal)
        }
        Spacer(Modifier.width(9.dp))
        plugin.icon?.let {
            Icon(it, null, Modifier.size(17.dp), BossTheme.colors.textSecondary)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                plugin.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = BossTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(plugin.description, fontSize = 9.sp, color = BossTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (plugin.isMandatory) "Required" else plugin.category.displayName,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            color = if (plugin.isMandatory) BossTheme.colors.signalText else BossTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}
