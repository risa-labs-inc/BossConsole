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
 * This is deliberately interruptive rather than a toast. The downgrade is one-way for the rest of
 * the session and it changes behaviour the user will otherwise discover as a malfunction - a new
 * browser tab that silently refuses to open looks like a bug, not like a policy. It also fires
 * rarely by construction: sustained low memory over a full minute, at most once per session.
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
 * What the browser-ceiling notice says, given what the tier actually did.
 *
 * Internal so `MemoryPressureCopyTest` can hold it against the two outcomes. The previous
 * version of this dialog stated one thing unconditionally and was wrong for the other case,
 * which is the mistake this file has already made once.
 */
internal fun capNoticeBody(refusal: ai.rever.boss.plugin.browser.BrowserCapRefusal): String {
    val tier =
        "BOSS is running in ${refusal.mode.displayName}, which keeps at most ${refusal.cap} " +
            "browsers open at once."
    val idleMinutes = refusal.evictedIdleMs?.let { (it / 60_000).coerceAtLeast(1) }
    return if (idleMinutes != null) {
        "$tier To make room, it closed the browser you had not used for about " +
            "$idleMinutes minute${if (idleMinutes == 1L) "" else "s"}. Change the mode in " +
            "Settings > Performance if you would rather keep them all."
    } else {
        "$tier Every open browser is either in use or was opened moments ago, so this one was " +
            "not opened. Close one, or change the mode in Settings > Performance."
    }
}

/**
 * Explains what the tier did to the browser ceiling: an idle browser reclaimed, or a new one
 * declined because everything open was in use.
 *
 * Without this the user gets the same nothing an engine failure gives them, for a limit they
 * never chose, on a tier the app may have picked for them. The ceiling is process-wide across
 * every window and shares its pool with RPA and automation handles, so "where did my tab go" has
 * an answer they cannot otherwise reach.
 */
@Composable
fun BrowserCapNoticeDialog() {
    val refusal by ai.rever.boss.plugin.browser.BrowserServiceImpl.capRefusals
        .collectAsState()
    val current = refusal ?: return

    val colors = BossTheme.colors

    Dialog(onDismissRequest = {
        ai.rever.boss.plugin.browser.BrowserServiceImpl
            .acknowledgeCapRefusal()
    }) {
        Column(
            modifier =
                Modifier
                    .width(430.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                    .padding(20.dp),
        ) {
            Text(
                text =
                    if (current.evictedIdleMs != null) {
                        "Closed an idle browser"
                    } else {
                        "Browser limit reached"
                    },
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = capNoticeBody(current),
                color = colors.textSecondary,
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    ai.rever.boss.plugin.browser.BrowserServiceImpl
                        .acknowledgeCapRefusal()
                }) {
                    Text("Continue", color = colors.signal, fontSize = 13.sp)
                }
            }
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
internal fun changeSummary(mode: ai.rever.boss.config.BossResourceMode): String {
    val parts = mutableListOf<String>()
    mode.maxConcurrentBrowsers?.let { parts += "at most $it browsers can be open at once" }
    if (!mode.backgroundSamplingEnabled) parts += "background performance sampling is off"
    return if (parts.isEmpty()) "nothing yet; a restart is needed to apply this tier" else parts.joinToString(", and ")
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
            text =
                "Plugins already loaded cannot be unloaded to reclaim memory. Restarting in " +
                    "Ultra Lite would also skip non-essential plugins on the way up.",
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
