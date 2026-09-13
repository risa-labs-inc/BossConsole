package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.settings.MICROKERNEL_MODE_CONFIRMATION_MESSAGE
import ai.rever.boss.settings.MicrokernelModeConfirmation
import ai.rever.boss.settings.MicrokernelModePreference
import ai.rever.boss.settings.needsMicrokernelModeConfirmation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun AdvancedSettings() {
    val coroutineScope = rememberCoroutineScope()

    val saveState by MicrokernelModePreference.saveState.collectAsState()
    val kernelMode = saveState.enabled ?: false
    val needsRestart = saveState.needsRestart
    val saveError = saveState.saveFailed
    val readError = saveState.readFailed
    val confirmation = remember { MicrokernelModeConfirmation() }

    LaunchedEffect(Unit) { MicrokernelModePreference.refresh() }

    fun applyMicrokernelMode(enabled: Boolean) {
        coroutineScope.launch { MicrokernelModePreference.save(enabled) }
    }

    if (confirmation.pending) {
        // No confirmColor override - both entry points take ConfirmationDialog's own default
        // (BossTheme.colors.alert) rather than each naming a color that can drift from the
        // other's, which is what let this button show blue here and red from the menu.
        ConfirmationDialog(
            title = "Enable experimental Microkernel Mode?",
            message = MICROKERNEL_MODE_CONFIRMATION_MESSAGE,
            confirmText = "Enable experimental mode",
            onDismiss = { confirmation.cancel() },
            onConfirm = { confirmation.confirm { applyMicrokernelMode(true) } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Process Mode") {
            SettingsToggle(
                label = "Microkernel Mode",
                checked = kernelMode,
                enabled = saveState.enabled != null,
                onCheckedChange = { enabled ->
                    if (needsMicrokernelModeConfirmation(currentlyEnabled = kernelMode, nextEnabled = enabled)) {
                        confirmation.request()
                    } else {
                        applyMicrokernelMode(enabled)
                    }
                },
                description = "Run plugins in isolated processes with gRPC IPC and AI self-healing",
            )

            if (saveError || readError) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = BossTheme.colors.alert.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    elevation = 0.dp,
                ) {
                    Text(
                        // Distinct messages, not one reworded flag (BossConsole#481 review): a
                        // read failure never attempted a write, so "check that BOSS can write" is
                        // actively wrong advice for it, and on a first-ever refresh failing this
                        // way the toggle above is also disabled - there is nothing to retry from
                        // this surface until some other window's refresh happens to succeed.
                        text =
                            if (readError) {
                                "Could not read the Microkernel Mode preference. It may become available " +
                                    "once BOSS can read its config directory."
                            } else {
                                "Could not save Microkernel Mode. Check that BOSS can write to its config directory."
                            },
                        fontSize = 11.sp,
                        color = BossTheme.colors.alert,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (needsRestart) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = AccentColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    elevation = 0.dp,
                ) {
                    Text(
                        text = "Restart required for changes to take effect.",
                        fontSize = 11.sp,
                        color = AccentColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // Always shown, including with Microkernel Mode off: this is where source egress is turned
        // on, and an operator should be able to read and set it before enabling the mode that runs
        // it. The readiness card says when the mode is what's holding it back.
        SelfHealingSettings(kernelMode = kernelMode)

        // Plugin JVM Settings (only visible in KERNEL mode)
        if (kernelMode) {
            val perfSettings by PerformanceSettingsManager.currentSettings.collectAsState()
            var pluginHeap by remember(perfSettings) { mutableStateOf(perfSettings.pluginJvmHeapMb.toFloat()) }
            var pluginInitHeap by remember(perfSettings) { mutableStateOf(perfSettings.pluginJvmInitialHeapMb.toFloat()) }

            SettingsSection(title = "Plugin JVM Resources") {
                SettingsSlider(
                    label = "Max Heap per Plugin",
                    value = pluginHeap,
                    onValueChange = { pluginHeap = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            PerformanceSettingsManager.updateSettings(
                                perfSettings.copy(pluginJvmHeapMb = pluginHeap.toInt()),
                            )
                        }
                    },
                    valueRange = 128f..8192f,
                    steps = 31,
                    valueDisplay = {
                        val mb = it.toInt()
                        if (mb >= 1024) "${"%.1f".format(mb / 1024f)} GB" else "$mb MB"
                    },
                    description = "Maximum heap size for each plugin child JVM. Requires plugin restart.",
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsSlider(
                    label = "Initial Heap per Plugin",
                    value = pluginInitHeap,
                    onValueChange = { pluginInitHeap = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            PerformanceSettingsManager.updateSettings(
                                perfSettings.copy(pluginJvmInitialHeapMb = pluginInitHeap.toInt()),
                            )
                        }
                    },
                    valueRange = 32f..pluginHeap.coerceAtLeast(64f),
                    steps = 15,
                    valueDisplay = {
                        val mb = it.toInt()
                        if (mb >= 1024) "${"%.1f".format(mb / 1024f)} GB" else "$mb MB"
                    },
                    description = "Initial heap allocation. Higher values reduce GC during startup.",
                )
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = AccentColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(6.dp),
                    elevation = 0.dp,
                ) {
                    val totalSystemMb =
                        remember {
                            ai.rever.boss.config.SystemMemory
                                .totalPhysicalBytes() / (1024 * 1024)
                        }
                    val totalPluginMb = pluginHeap.toLong() * 16
                    Text(
                        text =
                            "System RAM: ${"%.1f".format(totalSystemMb / 1024f)} GB  •  " +
                                "Max plugin allocation: ${"%.1f".format(totalPluginMb / 1024f)} GB (16 plugins × ${pluginHeap.toInt()} MB)",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // Info card
        SettingsSection(title = "About") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = AccentColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
                elevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Microkernel Architecture",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            "When enabled, plugins and services run in isolated child processes " +
                                "with gRPC-based IPC, and a crashed process is diagnosed and recovered " +
                                "by the orchestrator rather than simply restarted. Asking a model to " +
                                "propose a source patch is a separate opt-in, configured above. " +
                                "When disabled, everything runs in a single JVM process (default).",
                        fontSize = 11.sp,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}
