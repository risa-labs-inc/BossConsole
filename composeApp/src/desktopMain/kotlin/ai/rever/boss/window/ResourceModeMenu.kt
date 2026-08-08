package ai.rever.boss.window

import ai.rever.boss.config.BossResourceMode
import ai.rever.boss.config.ResourceModeConfig
import ai.rever.boss.config.ResourceModeReason
import ai.rever.boss.config.ResourceModeSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.MenuScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The "Auto" entry, which stores no selection and lets the tier be resolved per machine. */
private const val AUTO_LABEL = "Auto"

/**
 * What the app is running as right now, which is not always what is selected below it.
 *
 * The two can disagree three ways: the selection applies only on the next launch, the environment
 * override outranks it entirely, and the memory watchdog can tighten the live tier mid-session.
 * A radio list on its own would state the selection as though it were the current state, so this
 * line carries the truth and the radios carry the intent.
 */
internal fun runningAsLabel(mode: BossResourceMode): String = "Running as ${mode.displayName}"

/**
 * The footer that explains why picking something may not do what it appears to.
 *
 * A menu item that silently does nothing is worse than no menu item, and both of these cases
 * genuinely do nothing visible at click time.
 */
internal fun applyHintLabel(
    reason: ResourceModeReason,
    tightened: Boolean,
): String =
    when {
        reason == ResourceModeReason.ENVIRONMENT_OVERRIDE -> {
            "Forced by ${ResourceModeConfig.MODE_KEY} - selection ignored"
        }

        tightened -> {
            "Tightened after low memory - applies on the next launch"
        }

        else -> {
            "Applies on the next launch"
        }
    }

/**
 * The View > Resource Mode submenu.
 *
 * Deliberately a radio group rather than the "Focus Mode (On)" style used by its neighbour: focus
 * mode is a binary that flips instantly, whereas this is a three-way choice that takes a restart,
 * and reusing the toggle idiom would imply an immediacy it does not have.
 */
@Composable
fun MenuScope.ResourceModeMenu() {
    val scope = rememberCoroutineScope()
    val persisted by ResourceModeSettings.settings.collectAsState()

    val decision = ResourceModeConfig.decision
    val liveMode = ResourceModeConfig.mode
    val forcedByEnvironment = decision.reason == ResourceModeReason.ENVIRONMENT_OVERRIDE

    fun select(name: String?) {
        // Per-field, matching the Settings screen: writing back a whole snapshot clobbers a
        // concurrent writer, most concretely requestUltraLiteOnNextLaunch() from the
        // memory-pressure dialog.
        scope.launch(Dispatchers.IO) {
            ResourceModeSettings.update { it.copy(selectedMode = name) }
        }
    }

    Menu("Resource Mode") {
        Item(runningAsLabel(liveMode), enabled = false, onClick = {})

        Separator()

        RadioButtonItem(
            AUTO_LABEL,
            selected = persisted.selectedMode == null,
            enabled = !forcedByEnvironment,
            onClick = { select(null) },
        )
        BossResourceMode.entries.forEach { mode ->
            RadioButtonItem(
                mode.displayName,
                selected = persisted.selectedMode == mode.name,
                enabled = !forcedByEnvironment,
                onClick = { select(mode.name) },
            )
        }

        Separator()

        Item(
            applyHintLabel(decision.reason, tightened = liveMode != decision.mode),
            enabled = false,
            onClick = {},
        )
    }
}
