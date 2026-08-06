package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsNumberInput
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.config.BossResourceMode
import ai.rever.boss.config.ResourceModeConfig
import ai.rever.boss.config.ResourceModeReason
import ai.rever.boss.config.ResourceModeSettings
import ai.rever.boss.config.SystemMemory
import ai.rever.boss.plugin.LiteModePluginPolicy
import ai.rever.boss.plugin.PluginStoreSetup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val AUTO_LABEL = "Auto (recommended)"

/** The Settings label for a tier, e.g. "Lite - Caps the browser. Every plugin still loads." */
private fun BossResourceMode.settingsLabel(): String = "$displayName - $summary"

private fun ResourceModeReason.explain(mode: BossResourceMode): String =
    when (this) {
        ResourceModeReason.EXPLICIT_OVERRIDE -> "${mode.displayName}, because you selected it"
        ResourceModeReason.PLATFORM_DEFAULT -> "${mode.displayName}, the default on this platform"
        ResourceModeReason.DETECTED_MEMORY -> "${mode.displayName}, chosen from this machine's memory"
        ResourceModeReason.DEFAULT -> "${mode.displayName}, nothing asked for a reduced tier"
    }

/**
 * Settings for how much of itself BOSS runs.
 *
 * Two things this screen has to do beyond offering the choice. It must say **why** the current
 * tier was chosen, because a tier that silently drops plugins is otherwise indistinguishable
 * from a broken install. And it must name the plugins a reduced tier skipped, for the same
 * reason - "Docker is missing" needs an answer that is not "reinstall BOSS".
 */
@Composable
fun ResourceModeSettingsSection() {
    var persisted by remember { mutableStateOf(ResourceModeSettings.current()) }

    val decision = ResourceModeConfig.decision
    val totalGb =
        remember {
            SystemMemory.totalPhysicalBytes().toDouble() / ResourceModeConfig.BYTES_PER_GB
        }
    val skipped = remember { PluginStoreSetup.skippedByResourceMode }
    var optedIn by remember { mutableStateOf(LiteModePluginPolicy.userAllowlist()) }

    val options = listOf(AUTO_LABEL) + BossResourceMode.entries.map { it.settingsLabel() }
    val selectedLabel =
        persisted.selectedMode
            ?.let { name -> BossResourceMode.entries.firstOrNull { it.name == name }?.settingsLabel() }
            ?: AUTO_LABEL

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModeSelectorSection(
            options = options,
            selectedLabel = selectedLabel,
            decision = decision,
            totalGb = totalGb,
            onSelected = { persisted = ResourceModeSettings.current() },
        )

        AutomaticSelectionSection(
            persisted = persisted,
            onChanged = { persisted = ResourceModeSettings.current() },
        )

        PluginVisibilitySections(
            skipped = skipped,
            optedIn = optedIn,
            onToggleOptIn = { pluginId, keep ->
                val next = if (keep) optedIn + pluginId else optedIn - pluginId
                LiteModePluginPolicy.setUserAllowlist(next)
                optedIn = LiteModePluginPolicy.userAllowlist()
            },
        )
    }
}

/** The tier picker, plus what the app resolved and why. */
@Composable
private fun ModeSelectorSection(
    options: List<String>,
    selectedLabel: String,
    decision: ai.rever.boss.config.ResourceModeDecision,
    totalGb: Double,
    onSelected: () -> Unit,
) {
    SettingsSection(title = "Resource Mode") {
        SettingsDropdown(
            label = "Mode",
            options = options,
            selectedOption = selectedLabel,
            onOptionSelected = { label ->
                val mode = BossResourceMode.entries.firstOrNull { it.settingsLabel() == label }
                ResourceModeSettings.update { it.copy(selectedMode = mode?.name) }
                onSelected()
            },
            description =
                "Takes effect on the next launch. Plugin loading happens once at startup, so a " +
                    "tier change cannot apply to a session already running.",
        )

        SettingsInfoRow(
            label = "Running as",
            value = decision.reason.explain(decision.mode),
            description =
                if (ResourceModeConfig.mode != decision.mode) {
                    "Tightened to ${ResourceModeConfig.mode.displayName} during this session " +
                        "after sustained low memory."
                } else {
                    null
                },
        )

        SettingsInfoRow(
            label = "Detected memory",
            value = if (totalGb > 0) "%.1f GB".format(totalGb) else "Unknown",
            description =
                if (totalGb <= 0) {
                    "This machine's total memory could not be read, so Auto will not reduce."
                } else {
                    null
                },
        )
    }
}

/** Thresholds and the live-pressure toggle that drive Auto. */
@Composable
private fun AutomaticSelectionSection(
    persisted: ai.rever.boss.config.ResourceModeSettingsData,
    onChanged: () -> Unit,
) {
    SettingsSection(title = "Automatic Selection") {
        SettingsNumberInput(
            label = "Use Lite below",
            value = persisted.liteThresholdGb,
            onValueChange = { gb ->
                ResourceModeSettings.update { it.copy(liteThresholdGb = gb) }
                onChanged()
            },
            range = 1..1024,
            description = "Total machine memory, in GB, below which Auto picks Lite.",
        )

        SettingsNumberInput(
            label = "Use Ultra Lite below",
            value = persisted.ultraLiteThresholdGb,
            onValueChange = { gb ->
                ResourceModeSettings.update { it.copy(ultraLiteThresholdGb = gb) }
                onChanged()
            },
            range = 1..1024,
            description = "Total machine memory, in GB, below which Auto picks Ultra Lite.",
        )

        SettingsToggle(
            label = "React to low memory while running",
            checked = persisted.livePressureEnabled,
            onCheckedChange = { on ->
                ResourceModeSettings.update { it.copy(livePressureEnabled = on) }
                onChanged()
            },
            description =
                "Switch to Lite mid-session when free memory stays low, and say so. Installed " +
                    "memory alone cannot tell how much is actually free.",
        )
    }
}

/**
 * Names what the tier skipped, and what the user has excepted from it.
 *
 * The skipped list is the part that matters: without it, a reduced tier is indistinguishable
 * from an install that lost its plugins.
 */
@Composable
private fun PluginVisibilitySections(
    skipped: Set<String>,
    optedIn: Set<String>,
    onToggleOptIn: (String, Boolean) -> Unit,
) {
    if (skipped.isNotEmpty()) {
        SettingsSection(title = "Plugins Skipped This Launch") {
            SettingsInfoRow(
                label = "Not loaded",
                value = "${skipped.size} plugin${if (skipped.size == 1) "" else "s"}",
                description =
                    "These are installed but were not loaded, to save memory. Turn one on to " +
                        "keep it in Ultra Lite from the next launch.",
            )
            // A toggle per skipped plugin, so the recovery really is one click. Without these
            // the only way back is hand-editing ~/.boss/lite-plugins.json, which is not a
            // recovery story anyone can be expected to find.
            skipped.sorted().forEach { pluginId ->
                SettingsToggle(
                    label = pluginId.substringAfterLast('.'),
                    checked = pluginId in optedIn,
                    onCheckedChange = { keep -> onToggleOptIn(pluginId, keep) },
                    description = pluginId,
                )
            }
        }
    }

    // Exceptions the user added that this launch did not skip, e.g. because they are already
    // running in Full. Shown separately so the list above stays "what happened this launch".
    val otherExceptions = optedIn - skipped
    if (otherExceptions.isNotEmpty()) {
        SettingsSection(title = "Always Load in Ultra Lite") {
            otherExceptions.sorted().forEach { pluginId ->
                SettingsToggle(
                    label = pluginId.substringAfterLast('.'),
                    checked = true,
                    onCheckedChange = { keep -> onToggleOptIn(pluginId, keep) },
                    description = pluginId,
                )
            }
        }
    }
}
