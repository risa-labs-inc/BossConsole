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
 * Explains a browser tab that did not open because the tier's ceiling was already reached.
 *
 * Without this the user gets the same nothing they would get from an engine failure, for a limit
 * they never chose, on a tier the app may have selected for them. The cap is process-wide across
 * every window and shares its pool with RPA and automation handles, so "why did my tab not open"
 * has an answer the user cannot otherwise reach.
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
                text = "Browser limit reached",
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text =
                    "BOSS is running in ${current.mode.displayName}, which allows " +
                        "${current.cap} browsers at once. Close one to open another, or change " +
                        "the mode in Settings > Performance.",
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

    Text(
        text = "What changed: the browser is now capped, and background performance sampling is off.",
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
