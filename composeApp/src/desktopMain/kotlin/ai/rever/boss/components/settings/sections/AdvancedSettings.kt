package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.pathutils.BossDirectories
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdvancedSettings() {
    val coroutineScope = rememberCoroutineScope()

    // Read current BOSS_MODE from env_vars file
    var kernelMode by remember { mutableStateOf(false) }
    var needsRestart by remember { mutableStateOf(false) }
    val initialMode = remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val mode = readBossMode()
        kernelMode = mode
        initialMode.value = mode
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Process Mode") {
            SettingsToggle(
                label = "Microkernel Mode",
                checked = kernelMode,
                onCheckedChange = { enabled ->
                    kernelMode = enabled
                    needsRestart = enabled != initialMode.value
                    coroutineScope.launch {
                        writeBossMode(enabled)
                    }
                },
                description = "Run plugins in isolated processes with gRPC IPC and AI self-healing",
            )

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
                            try {
                                val osBean =
                                    java.lang.management.ManagementFactory
                                        .getOperatingSystemMXBean()
                                val method = osBean.javaClass.getMethod("getTotalPhysicalMemorySize")
                                method.isAccessible = true
                                (method.invoke(osBean) as Long) / (1024 * 1024)
                            } catch (_: Exception) {
                                0L
                            }
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
                                "with gRPC-based IPC. The RepairEngine provides AI-powered self-healing " +
                                "using the OpenAI API key from LLM Providers settings. " +
                                "When disabled, everything runs in a single JVM process (default).",
                        fontSize = 11.sp,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

private suspend fun readBossMode(): Boolean =
    withContext(Dispatchers.IO) {
        val envFile = BossDirectories.resolve("env_vars")
        if (!envFile.exists()) return@withContext false
        try {
            envFile
                .readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .any { line ->
                    val parts = line.split("=", limit = 2)
                    parts.size == 2 && parts[0].trim() == "BOSS_MODE" && parts[1].trim() == "KERNEL"
                }
        } catch (_: Exception) {
            false
        }
    }

private suspend fun writeBossMode(enabled: Boolean) =
    withContext(Dispatchers.IO) {
        val envFile = BossDirectories.resolve("env_vars")
        envFile.parentFile?.mkdirs()

        if (!envFile.exists()) {
            // Create with just BOSS_MODE
            envFile.writeText(
                if (enabled) "BOSS_MODE=KERNEL\n" else "# BOSS_MODE=KERNEL\n",
                Charsets.UTF_8,
            )
            return@withContext
        }

        val lines = envFile.readLines(Charsets.UTF_8).toMutableList()
        val modeLineIndex =
            lines.indexOfFirst { line ->
                val trimmed = line.trimStart('#', ' ')
                trimmed.startsWith("BOSS_MODE")
            }

        val newLine = if (enabled) "BOSS_MODE=KERNEL" else "# BOSS_MODE=KERNEL"

        if (modeLineIndex >= 0) {
            lines[modeLineIndex] = newLine
        } else {
            // Add after last non-empty line
            lines.add("")
            lines.add("# Microkernel mode — enables out-of-process plugins, gRPC IPC, and AI self-healing")
            lines.add(newLine)
        }

        envFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }
