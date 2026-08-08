package ai.rever.boss.performance

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Tells the user when the memory-pressure watchdog has tightened the resource tier under them.
 *
 * Interruptive rather than a toast, but only just. The original justification - "a new browser tab
 * that silently refuses to open looks like a bug" - died with the concurrent-browser ceiling, and
 * a modal that blocks the UI to announce that nothing has changed yet is a poor trade. It stays
 * modal because the downgrade is one-way for the session and the restart action is the only way to
 * act on it, and it fires rarely by construction: sustained low memory over a full minute, at most
 * once per session. Worth revisiting as a non-modal notice once [changeSummary] can report a real
 * live effect.
 *
 * Mounted by `BossWindow`; renders nothing until [MemoryPressureWatchdog] has actually acted.
 */
@Composable
fun MemoryPressureNoticeDialog(onRestartRequested: () -> Unit) {
    val notice by MemoryPressureWatchdog.notices.collectAsState()
    val current = notice ?: return

    val colors = BossTheme.colors

    Dialog(onDismissRequest = { MemoryPressureWatchdog.acknowledge() }) {
        Column(
            modifier =
                Modifier
                    .width(430.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                    .padding(20.dp),
        ) {
            NoticeBody(current)
            Spacer(Modifier.height(18.dp))
            NoticeActions(current, onRestartRequested)
        }
    }
}

/**
 * What a live tighten to [mode] actually changed, in the user's terms.
 *
 * Only the levers a *mid-session* tighten can really apply belong here. Plugin gating and the
 * Chromium renderer cap are both fixed at startup, so however constrained the tier is on paper,
 * a running session cannot have gained them. Internal so `MemoryPressureCopyTest` can hold it
 * against the tier table, which is how the previous version's false claim went unnoticed.
 */
internal fun changeSummary(mode: ai.rever.boss.config.BossResourceMode): String =
    if (mode.backgroundSamplingEnabled) {
        "nothing yet; a restart is needed to apply this tier"
    } else {
        "background performance sampling is off"
    }

/**
 * Why restarting would reclaim more than the live tighten just did.
 *
 * Derived rather than written out, for the same reason as [changeSummary]. The previous string
 * claimed a restart "would also skip non-essential plugins on the way up", which stopped being
 * true when plugin gating was removed: no tier skips a plugin now. The honest remaining reason is
 * the renderer cap, which is a Chromium switch fixed when the engine starts.
 */
internal fun restartRationale(target: ai.rever.boss.config.BossResourceMode): String {
    val limit = target.rendererProcessLimit
    return if (limit != null) {
        "Chromium's process limit is fixed when the browser engine starts, so it cannot be " +
            "lowered in place. Restarting in ${target.displayName} brings it up capped at $limit."
    } else {
        "Restarting in ${target.displayName} applies the tier from a clean start."
    }
}

@Composable
private fun NoticeBody(notice: MemoryPressureNotice) {
    val colors = BossTheme.colors

    Text(
        text = "Low memory - BOSS reduced itself",
        color = colors.textPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )

    Spacer(Modifier.height(10.dp))

    Text(
        text =
            "Only ${notice.freePercent}% of this machine's memory was free for a sustained " +
                "period. BOSS has switched to ${notice.appliedMode.displayName} to stay well " +
                "clear of the point where the embedded browser would take the whole app down " +
                "with it.",
        color = colors.textSecondary,
        fontSize = 13.sp,
    )

    Spacer(Modifier.height(12.dp))

    // Derived from the tier rather than written out, because the hardcoded version said
    // "background performance sampling is off" and that was simply untrue: the watchdog only
    // ever applies LITE, LITE leaves sampling on, and the sampler is started once at boot and
    // never stopped mid-session anyway. A false sentence is worse here than in most places -
    // this dialog's whole job is explaining a change the user did not ask for.
    Text(
        text = "What changed: ${changeSummary(notice.appliedMode)}",
        color = colors.textMuted,
        fontSize = 12.sp,
    )

    if (notice.restartWouldHelpFurther) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = restartRationale(ai.rever.boss.config.BossResourceMode.ULTRA_LITE),
            color = colors.textMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun NoticeActions(
    notice: MemoryPressureNotice,
    onRestartRequested: () -> Unit,
) {
    val colors = BossTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { MemoryPressureWatchdog.acknowledge() }) {
            Text("Continue", color = colors.textSecondary, fontSize = 13.sp)
        }
        if (notice.restartWouldHelpFurther) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                MemoryPressureWatchdog.acknowledge()
                onRestartRequested()
            }) {
                Text("Restart in Ultra Lite", color = colors.signal, fontSize = 13.sp)
            }
        }
    }
}
