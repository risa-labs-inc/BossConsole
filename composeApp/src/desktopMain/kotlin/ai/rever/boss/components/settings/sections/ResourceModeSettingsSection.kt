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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_LABEL = "Auto (recommended)"

/** Settle time before a Settings edit reaches disk. Long enough to swallow a typed number. */
private const val PERSIST_DEBOUNCE_MS = 400L

/**
 * A single edited field, applied to whatever the persisted state is at write time.
 *
 * Exists so Settings never writes back a whole snapshot: doing that clobbers concurrent writers,
 * and the concrete victim is [ResourceModeConfig.requestUltraLiteOnNextLaunch] firing from the
 * memory-pressure dialog while this screen is open.
 */
private typealias ModeSettings = ai.rever.boss.config.ResourceModeSettingsData

private sealed interface ResourceModeField {
    fun applyTo(current: ModeSettings): ModeSettings

    /**
     * Debounce identity. Distinct per field so an edit to one threshold cannot cancel the pending
     * write of the other, which silently dropped it.
     */
    val debounceKey: String get() = this::class.simpleName.orEmpty()

    data class LiteThreshold(
        val gb: Int,
    ) : ResourceModeField {
        override fun applyTo(current: ModeSettings) = current.copy(liteThresholdGb = gb)
    }

    data class UltraLiteThreshold(
        val gb: Int,
    ) : ResourceModeField {
        override fun applyTo(current: ModeSettings) = current.copy(ultraLiteThresholdGb = gb)
    }

    data class LivePressure(
        val enabled: Boolean,
    ) : ResourceModeField {
        override fun applyTo(current: ModeSettings) = current.copy(livePressureEnabled = enabled)
    }
}

/** The Settings label for a tier, e.g. "Lite - Caps the browser. Every plugin still loads." */
private fun BossResourceMode.settingsLabel(): String = "$displayName - $summary"

private fun ResourceModeReason.explain(mode: BossResourceMode): String =
    when (this) {
        // Named explicitly rather than folded into "because you selected it": when the env var is
        // set, the dropdown above still shows the user's own choice, and claiming they selected
        // this tier contradicts the control they are looking at.
        ResourceModeReason.ENVIRONMENT_OVERRIDE -> {
            "${mode.displayName}, forced by ${ResourceModeConfig.MODE_KEY}"
        }

        ResourceModeReason.USER_SELECTION -> {
            "${mode.displayName}, because you selected it"
        }

        ResourceModeReason.PRESSURE_RESTART -> {
            "${mode.displayName}, for this launch only, after low memory"
        }

        ResourceModeReason.PLATFORM_DEFAULT -> {
            "${mode.displayName}, the default on this platform"
        }

        ResourceModeReason.DETECTED_MEMORY -> {
            "${mode.displayName}, chosen from this machine's memory"
        }

        ResourceModeReason.DEFAULT -> {
            "${mode.displayName}, nothing asked for a reduced tier"
        }
    }

/**
 * Settings for how much of itself BOSS runs.
 *
 * Beyond offering the choice, this screen has to say **why** the current tier was chosen. The
 * app picks a tier on the user's behalf from their machine's memory and platform, and a setting
 * that changed itself without explanation is indistinguishable from a bug.
 */
@Composable
fun ResourceModeSettingsSection() {
    // Observed, not snapshotted. A one-shot `remember { current() }` here left this screen showing
    // a stale selection whenever anything else wrote one while it was composed - the View menu, or
    // the memory-pressure dialog's restart button.
    val persisted by ResourceModeSettings.settings.collectAsState()

    // Likewise a flow: after a live tighten, a plain `ResourceModeConfig.mode` read left "Running
    // as Full" on screen until an unrelated recomposition happened to refresh it.
    val liveMode by ResourceModeConfig.effectiveMode.collectAsState()

    val decision = ResourceModeConfig.decision
    val totalGb =
        remember {
            SystemMemory.totalPhysicalBytes().toDouble() / ResourceModeConfig.BYTES_PER_GB
        }
    val scope = rememberCoroutineScope()

    // Persisting is a small file write, but it is still disk I/O and it runs from a Compose
    // callback. docs/THREADING.md is firm about this.
    fun persist(block: () -> Unit) {
        scope.launch(Dispatchers.IO) { block() }
    }

    // The typed number inputs are debounced: SettingsNumberInput fires onValueChange per
    // keystroke, so typing "128" would otherwise rewrite the JSON three times.
    //
    // Keyed per field, not one shared handle. A single job meant that touching the second
    // threshold within the debounce window cancelled the first one's pending write, which was
    // then never reissued - and the UI kept showing the lost value, because `persisted` had
    // already been updated optimistically.
    val pending = remember { mutableStateMapOf<String, Job>() }

    // The pending write itself, kept alongside the job so leaving the screen can still run it.
    val pendingWrites = remember { mutableStateMapOf<String, () -> Unit>() }

    fun persistDebounced(
        key: String,
        block: () -> Unit,
    ) {
        pending.remove(key)?.cancel()
        pendingWrites[key] = block
        pending[key] =
            scope.launch(Dispatchers.IO) {
                delay(PERSIST_DEBOUNCE_MS)
                block()
                pendingWrites.remove(key)
            }
    }

    // Flush on the way out. `scope` is rememberCoroutineScope(), so it is cancelled when this
    // screen leaves composition - typing a threshold and switching tabs within the debounce
    // window otherwise dropped the edit silently, and with no optimistic copy the field simply
    // showed the old value again next time. Same failure the per-field keying above fixes for
    // the sibling-field case; this is the dispose case of it.
    DisposableEffect(Unit) {
        onDispose {
            val outstanding = pendingWrites.values.toList()
            pendingWrites.clear()
            if (outstanding.isNotEmpty()) {
                // A detached scope: `scope` is already cancelled by the time onDispose runs,
                // so launching the flush on it would be a no-op.
                CoroutineScope(Dispatchers.IO).launch { outstanding.forEach { it() } }
            }
        }
    }

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
            liveMode = liveMode,
            totalGb = totalGb,
            // No optimistic local copy: the write updates the StateFlow this screen collects, so
            // the radio state arrives back through the same path an external change would.
            onSelected = { name ->
                persist { ResourceModeSettings.update { it.copy(selectedMode = name) } }
            },
        )

        AutomaticSelectionSection(
            persisted = persisted,
            // Per-field, never `update { updated }`. Writing back a whole snapshot captured when
            // the screen composed clobbers any concurrent write - most concretely
            // requestUltraLiteOnNextLaunch() firing from the memory-pressure dialog, which would
            // silently undo the "Restart in Ultra Lite" the user had just clicked.
            // SettingsNumberInput keys its own text state on `value`, so it holds what was typed
            // until the write lands - no optimistic copy needed to keep the field responsive.
            onChanged = { field ->
                val write = { ResourceModeSettings.update { current -> field.applyTo(current) } }
                // The toggle is one discrete event; only the typed thresholds need settling.
                if (field is ResourceModeField.LivePressure) {
                    persist(write)
                } else {
                    persistDebounced(field.debounceKey, write)
                }
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
    liveMode: BossResourceMode,
    totalGb: Double,
    onSelected: (String?) -> Unit,
) {
    SettingsSection(title = "Resource Mode") {
        // Disabled under an environment override, matching the View menu. Leaving it clickable
        // made the screen contradict itself: the dropdown said "takes effect on the next launch"
        // while the row directly below said "forced by BOSS_RESOURCE_MODE", and the override wins
        // on the next launch too - so the click genuinely does nothing.
        val forcedByEnvironment = decision.reason == ResourceModeReason.ENVIRONMENT_OVERRIDE
        SettingsDropdown(
            label = "Mode",
            options = options,
            selectedOption = selectedLabel,
            onOptionSelected = { label ->
                onSelected(BossResourceMode.entries.firstOrNull { it.settingsLabel() == label }?.name)
            },
            enabled = !forcedByEnvironment,
            description =
                if (forcedByEnvironment) {
                    "${ResourceModeConfig.MODE_KEY} is set, so this choice would be ignored. " +
                        "Unset it to choose a tier here."
                } else {
                    "Takes effect on the next launch. Chromium's process limit is a command-line " +
                        "switch read once when the browser engine starts, so a tier change cannot " +
                        "apply to a session already running."
                },
        )

        SettingsInfoRow(
            label = "Running as",
            value = decision.reason.explain(decision.mode),
            description =
                if (liveMode != decision.mode) {
                    "Tightened to ${liveMode.displayName} during this session after sustained " +
                        "low memory."
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
    persisted: ModeSettings,
    onChanged: (ResourceModeField) -> Unit,
) {
    SettingsSection(title = "Automatic Selection") {
        SettingsNumberInput(
            label = "Use Lite below",
            value = persisted.liteThresholdGb,
            onValueChange = { gb -> onChanged(ResourceModeField.LiteThreshold(gb)) },
            range = 1..1024,
            description = "Total machine memory, in GB, below which Auto picks Lite.",
        )

        SettingsNumberInput(
            label = "Use Ultra Lite below",
            value = persisted.ultraLiteThresholdGb,
            onValueChange = { gb -> onChanged(ResourceModeField.UltraLiteThreshold(gb)) },
            range = 1..1024,
            description =
                "Total machine memory, in GB, below which Auto picks Ultra Lite. Clamped to the " +
                    "Lite threshold, since a higher value would make Lite unreachable.",
        )

        SettingsToggle(
            label = "React to low memory while running",
            checked = persisted.livePressureEnabled,
            onCheckedChange = { on -> onChanged(ResourceModeField.LivePressure(on)) },
            description =
                "Switch to Lite mid-session when available memory stays low, and say so. " +
                    "Installed memory alone cannot tell how much is actually free. Takes effect " +
                    "on the next launch: the watchdog reads this once, when it starts.",
        )
    }
}
