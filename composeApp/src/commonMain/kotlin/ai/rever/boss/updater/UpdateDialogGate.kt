package ai.rever.boss.updater

import ai.rever.boss.components.overlays.HeavyweightModalRegistry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Whether the app-update prompt should be on screen right now.
 *
 * The update prompt can appear on its own timer (a background update check
 * finishing) rather than in response to the user. On the heavyweight-overlay path (Windows), every
 * modal is a separate always-on-top window, and two of them stacked is the state BossConsole#696
 * reports as leaving BOSS unusable. So the prompt yields to any OTHER heavyweight modal: it does not
 * open over one, and it steps aside when one opens over it. The update banner still shows meanwhile,
 * and the prompt reappears once the other modal closes.
 *
 * [openHeavyweightModals] is the total from [ai.rever.boss.components.overlays.HeavyweightModalRegistry],
 * which counts the update prompt itself while it is up - so its own window is subtracted out via
 * [updateDialogShowing], leaving the count of modals that are NOT this prompt. On the lightweight
 * process-wide OFF_SCREEN path the count is always 0. The count spans all BOSS windows, so another
 * window's heavyweight modal also defers this prompt. Native and Settings lightweight dialogs are
 * not counted; this gate does not serialize other autonomous prompts or prove #696 fully resolved.
 */
internal fun shouldShowUpdateDialog(
    wantDialog: Boolean,
    isOwner: Boolean,
    updateAvailable: Boolean,
    openHeavyweightModals: Int,
    updateDialogShowing: Boolean,
): Boolean {
    if (!wantDialog || !isOwner || !updateAvailable) return false
    val otherModals = openHeavyweightModals - if (updateDialogShowing) 1 else 0
    return otherModals <= 0
}

/** Keeps count invalidations local and commits self-accounting before the modal's registration. */
@Composable
internal fun UpdateDialogGate(
    wantDialog: Boolean,
    isOwner: Boolean,
    updateAvailable: Boolean,
    content: @Composable () -> Unit,
) {
    var showing by remember { mutableStateOf(false) }
    if (shouldShowUpdateDialog(wantDialog, isOwner, updateAvailable, HeavyweightModalRegistry.openCount, showing)) {
        // TrackHeavyweightModal commits in this same parent composition, after this effect.
        // Keeping its registration outside Window's separate content composition pins that order.
        DisposableEffect(Unit) {
            showing = true
            onDispose { showing = false }
        }
        content()
    }
}
