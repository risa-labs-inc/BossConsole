package ai.rever.boss.updater

import BossDarkAccent
import BossDarkBackground
import BossDarkTextMuted
import BossDarkTextPrimary
import BossDarkTextSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Matches the banner colors in UpdateUI.kt
private val AccentBlue get() = BossDarkAccent
private val TextGray get() = BossDarkTextMuted

/**
 * Dismissible dialog shown when a new BossConsole version is available.
 *
 * "Update Now" starts the download (the top banner then shows progress);
 * "Later" (or clicking outside / Esc) persists the dismissal for this
 * version — it won't be prompted again until a different version is
 * published or the user manually checks for updates.
 */
@Composable
fun UpdateAvailableDialog(
    updateInfo: UpdateInfo,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit
) {
    // Release notes are markdown; render them like the editor's preview does.
    // Best-effort: if parsing fails (or yields nothing), notesBlocks stays null
    // and the dialog falls back to the plain-text lines it always showed.
    val notesBlocks = remember(updateInfo.releaseNotes) {
        runCatching { parseReleaseNotes(updateInfo.releaseNotes) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
    AlertDialog(
        onDismissRequest = onLater,
        modifier = Modifier.widthIn(min = 360.dp, max = 480.dp),
        title = {
            Text(
                "Update available",
                color = BossDarkTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp)) {
                Text(
                    "BossConsole v${updateInfo.latestVersion} is available " +
                        "(you have v${updateInfo.currentVersion}).",
                    color = BossDarkTextSecondary,
                    fontSize = 13.sp
                )
                if (updateInfo.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "What's new",
                        color = BossDarkTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    // Column+verticalScroll behind NotesAreaSizing (see its kdoc):
                    // the scroll area's intrinsic height AND baseline alignment
                    // lines must both be fenced off, or the desktop AlertDialog
                    // re-sizes / shifts the text slot as the notes are scrolled.
                    Column(
                        Modifier
                            .then(NotesAreaSizing(220.dp))
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (notesBlocks != null) {
                            notesBlocks.forEach { block ->
                                NotesBlockView(block)
                            }
                        } else {
                            updateInfo.releaseNotes.lines().forEach { line ->
                                Text(
                                    line,
                                    color = BossDarkTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdateNow) {
                Text("Update Now", color = AccentBlue, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("Later", color = TextGray, fontSize = 13.sp)
            }
        },
        backgroundColor = BossDarkBackground,
        contentColor = BossDarkTextPrimary
    )
}

/**
 * Sizing fence for scrollable content inside the desktop material [AlertDialog].
 *
 * Two leaks have to be plugged, or the dialog resizes/jumps while scrolling:
 *
 * 1. Intrinsics: the dialog sizes its popup from intrinsic measurements, and
 *    heightIn(max) clamps layout only — so both layout and intrinsic height
 *    are capped here.
 * 2. Baselines: AlertDialogBaselineLayout places the text slot at
 *    `titleBaseline + offset - slotFirstBaseline`. FirstBaseline merges
 *    across children with MIN policy and propagates out of scroll containers
 *    offset by the scroll position — scrolling makes the slot's merged
 *    FirstBaseline increasingly negative, which pushes the slot down and
 *    grows the dialog by exactly the scrolled amount. Pinning both baselines
 *    to constants stops any scroll-dependent value from escaping.
 */
private data class NotesAreaSizing(private val max: Dp) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val cappedMax = max.roundToPx().coerceAtMost(constraints.maxHeight)
        val placeable = measurable.measure(
            constraints.copy(
                minHeight = constraints.minHeight.coerceAtMost(cappedMax),
                maxHeight = cappedMax
            )
        )
        return layout(
            placeable.width,
            placeable.height,
            alignmentLines = mapOf(FirstBaseline to 0, LastBaseline to placeable.height)
        ) {
            placeable.placeRelative(0, 0)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.minIntrinsicHeight(width).coerceAtMost(max.roundToPx())

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.maxIntrinsicHeight(width).coerceAtMost(max.roundToPx())
}
