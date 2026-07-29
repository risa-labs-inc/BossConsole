package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTextArea
import ai.rever.boss.components.settings.shared.SettingsTextField
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.kernel.SelfHealingProvider
import ai.rever.boss.kernel.SelfHealingSettingsData
import ai.rever.boss.kernel.SelfHealingSettingsManager
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The instruction the orchestrator uses when this field is left empty.
 *
 * Duplicated from `DEFAULT_REPAIR_SYSTEM_PROMPT` in boss-orchestrator, which the host cannot depend
 * on — it is a separate process. Shown as placeholder text only; the value actually used still
 * comes from the orchestrator when the field is blank.
 */
private const val DEFAULT_SYSTEM_PROMPT_HINT =
    "You are a precise code repair assistant. Always respond with valid JSON only. " +
        "Do not include markdown code fences."

/**
 * Settings for the self-healing orchestrator's use of a model.
 *
 * Self-healing itself needs no model — restart, tuned restart, state reset and escalation all work
 * without one. What is configured here is the optional step beyond that: asking a model to read the
 * crashing process's source and propose a patch. That sends source off the machine, so it is off by
 * default and every field that decides where it goes is stated rather than inferred.
 */
@Composable
fun SelfHealingSettings(kernelMode: Boolean) {
    val scope = rememberCoroutineScope()
    val settings by SelfHealingSettingsManager.currentSettings.collectAsState()
    val provider = remember(settings.provider) { SelfHealingProvider.of(settings.provider) }

    // Off the composition thread: resolving a key reads the legacy key file (see docs/THREADING.md).
    // Re-run on every settings change as well as on provider change, so the card stops saying "no
    // API key" as soon as one becomes resolvable — that is the one thing this card exists to get
    // right, and keying it on the provider alone left it stale until the screen was recreated.
    //
    // Note the key does NOT come from Settings → AI Providers: that store belongs to a plugin and
    // the kernel spawns repair services before plugins load. An environment variable is the
    // supported path — see SelfHealingSettingsManager.resolveApiKey.
    val hasKey by produceState(initialValue = true, settings) {
        value = withContext(Dispatchers.IO) { SelfHealingSettingsManager.hasApiKey(provider) }
    }

    fun update(change: (SelfHealingSettingsData) -> SelfHealingSettingsData) {
        scope.launch { SelfHealingSettingsManager.updateSettings(change(settings)) }
    }

    SettingsSection(title = "Self-Healing") {
        SettingsToggle(
            label = "AI-Assisted Repair",
            checked = settings.aiRepairEnabled,
            onCheckedChange = { enabled -> update { it.copy(aiRepairEnabled = enabled) } },
            description =
                "Let the orchestrator send the crashing process's source files to the model below " +
                    "and propose a patch. Restart, state reset and escalation work without this.",
        )

        if (settings.aiRepairEnabled) {
            RepairModelFields(settings = settings, provider = provider, onChange = ::update)

            Spacer(modifier = Modifier.height(8.dp))
            RepairReadinessCard(settings = settings, provider = provider, hasKey = hasKey, kernelMode = kernelMode)
        }
    }
}

/**
 * Where proposals come from and what the model is told.
 *
 * Model and endpoint are shown with the chosen provider's default as placeholder text rather than
 * pre-filled, so a blank field keeps tracking the default instead of pinning today's value.
 */
@Composable
private fun RepairModelFields(
    settings: SelfHealingSettingsData,
    provider: SelfHealingProvider,
    onChange: ((SelfHealingSettingsData) -> SelfHealingSettingsData) -> Unit,
) {
    Spacer(modifier = Modifier.height(8.dp))
    SettingsDropdown(
        label = "Provider",
        options = SelfHealingProvider.entries.map { it.displayName },
        selectedOption = provider.displayName,
        onOptionSelected = { chosen ->
            val picked = SelfHealingProvider.entries.first { it.displayName == chosen }
            // Model and endpoint are provider-specific; carrying them across would point a new
            // provider at another one's URL. Clearing restores that provider's defaults.
            onChange { it.copy(provider = picked.name, model = "", endpoint = "") }
        },
        description =
            "Where repair proposals are generated. The API key comes from the environment " +
                "(AI_REPAIR_API_KEY, or the provider's own variable).",
    )

    Spacer(modifier = Modifier.height(8.dp))
    SettingsTextField(
        label = "Model",
        value = settings.model,
        onValueChange = { value -> onChange { it.copy(model = value) } },
        placeholder = provider.defaultModel.ifBlank { "Required for a custom provider" },
        description = "Leave empty to use the provider's default.",
    )

    Spacer(modifier = Modifier.height(8.dp))
    SettingsTextField(
        label = "Endpoint",
        value = settings.endpoint,
        onValueChange = { value -> onChange { it.copy(endpoint = value) } },
        placeholder = provider.defaultEndpoint.ifBlank { "https://your-gateway/v1/chat/completions" },
        description = "Leave empty to use the provider's default.",
    )

    Spacer(modifier = Modifier.height(8.dp))
    SettingsTextField(
        label = "Source root",
        value = settings.projectRoot,
        onValueChange = { value -> onChange { it.copy(projectRoot = value) } },
        placeholder = "/path/to/your/checkout",
        description =
            "The only directory source may be read from and sent to the model. " +
                "Without one, AI repair stays off.",
    )

    Spacer(modifier = Modifier.height(8.dp))
    SettingsTextArea(
        label = "System prompt",
        value = settings.systemPrompt,
        onValueChange = { value -> onChange { it.copy(systemPrompt = value) } },
        placeholder = DEFAULT_SYSTEM_PROMPT_HINT,
        description =
            "Leave empty for the default. Proposals are parsed as JSON, so a replacement " +
                "should still ask for JSON only.",
    )

    if (settings.systemPrompt.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsButtonRow(
            label = "Custom system prompt in use",
            buttonText = "Reset to default",
            onClick = { onChange { it.copy(systemPrompt = "") } },
        )
    }
}

/**
 * Says whether AI repair will actually run, and what is missing if not.
 *
 * Three separate things have to line up — a key, a source root, and microkernel mode — and each is
 * withheld silently at spawn time. Naming the missing one here is the difference between a setting
 * that appears on and a setting that works.
 */
@Composable
private fun RepairReadinessCard(
    settings: SelfHealingSettingsData,
    provider: SelfHealingProvider,
    hasKey: Boolean,
    kernelMode: Boolean,
) {
    val blockers =
        buildList {
            if (!hasKey) {
                add("no API key for ${provider.displayName} (set ${provider.apiKeyEnvVar} or AI_REPAIR_API_KEY)")
            }
            if (settings.projectRoot.isBlank()) add("no source root named")
            if (settings.endpoint.isBlank() && provider.defaultEndpoint.isBlank()) add("no endpoint")
            if (settings.model.isBlank() && provider.defaultModel.isBlank()) add("no model")
            if (!kernelMode) add("Microkernel Mode is off, so the orchestrator does not run")
        }

    val ready = blockers.isEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (ready) AccentColor.copy(alpha = 0.12f) else BossTheme.colors.alert.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        elevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (ready) "AI repair is configured" else "AI repair will not run",
                fontSize = 12.sp,
                color = if (ready) AccentColor else BossTheme.colors.alert,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    if (ready) {
                        "Applies the next time the orchestrator starts. Restart BOSS to pick up changes."
                    } else {
                        "Missing: ${blockers.joinToString("; ")}."
                    },
                fontSize = 11.sp,
                color = TextSecondary,
            )
        }
    }
}
