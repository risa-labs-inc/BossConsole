package ai.rever.boss.components.dialogs

import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.downloads.Transfer
import ai.rever.boss.downloads.transferStatusLine
import ai.rever.boss.plugin.api.TransferPhase
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.theme.dialogPanelColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the bottom-bar progress item opens: every transfer in flight, with the
 * controls that belong to each one.
 *
 * Closing it is "minimize", not "stop" - the transfers are owned by the center,
 * not by this window's composition, so the only thing that ends one is its own
 * Cancel. That is the whole reason the button says Minimize: a dialog whose
 * close button reads Cancel next to a Cancel that means something else is how a
 * user cancels a download they meant to keep.
 */
@Composable
fun DownloadCenterDialog(onDismiss: () -> Unit) {
    val transfers by DownloadCenter.transfers.collectAsState()

    // Nothing left to show: the last transfer finished while the dialog was open.
    // Closing beats an empty box the user then has to dismiss - from an effect,
    // never inline, because onDismiss writes the caller's state and doing that
    // during composition is what "Cannot mutate state during composition" means.
    LaunchedEffect(transfers.isEmpty()) {
        if (transfers.isEmpty()) onDismiss()
    }
    if (transfers.isEmpty()) return

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (transfers.size == 1) "Download" else "Downloads",
                color = BossTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .dialogScrollFence(ROWS_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                transfers.forEach { transfer ->
                    TransferRow(transfer)
                }
                Text(
                    text = "Transfers continue in the background.",
                    color = BossTheme.colors.textMuted,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signalText),
            ) {
                Text("Minimize", fontSize = 13.sp)
            }
        },
        backgroundColor = dialogPanelColor,
        contentColor = BossTheme.colors.textPrimary,
    )
}

/** One transfer: what it is, where it has got to, and what can be done to it. */
@Composable
private fun TransferRow(transfer: Transfer) {
    val info = transfer.info
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.title,
                    color = BossTheme.colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                info.detail?.let { detail ->
                    Text(
                        text = detail,
                        color = BossTheme.colors.textMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TransferActions(transfer)
        }

        Spacer(Modifier.height(6.dp))

        val barModifier =
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        // A downloaded update draws a full bar, not an indeterminate one: it has
        // finished downloading and is waiting on the user, and a bar still
        // sweeping beside "Ready to install" says the opposite.
        val fraction = info.progress ?: 1f.takeIf { info.phase == TransferPhase.READY_TO_INSTALL }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = fraction,
                modifier = barModifier,
                color = BossTheme.colors.signal,
                backgroundColor = BossTheme.colors.raised,
            )
        } else {
            LinearProgressIndicator(
                modifier = barModifier,
                color = BossTheme.colors.signal,
                backgroundColor = BossTheme.colors.raised,
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = transferStatusLine(info),
            color = BossTheme.colors.textSecondary,
            fontSize = 11.sp,
        )
    }
}

/**
 * Install (a downloaded app update) and Cancel, for one row.
 *
 * Cancel is rendered whenever the transfer has a cancel action and merely
 * DISABLED outside the phases that can be abandoned, rather than hidden: a
 * button that appears and vanishes as the phase turns over is a moving target,
 * and its absence would read as "this cannot be stopped" rather than "it is too
 * late to stop safely".
 */
@Composable
private fun TransferActions(transfer: Transfer) {
    val info = transfer.info
    if (info.phase == TransferPhase.READY_TO_INSTALL && transfer.onInstall != null) {
        TextButton(
            onClick = { DownloadCenter.install(info.id) },
            colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signalText),
        ) {
            Text("Install", fontSize = 12.sp)
        }
    }
    // Rendered on the phase, not on the action's presence: `DownloadCenter.cancel`
    // clears the action to make the press single-shot, so keying the button on it made
    // Cancel VANISH when pressed and reappear a tick later on a row whose caller
    // re-asserts its actions - exactly the moving target this comment argues against.
    if (info.phase != TransferPhase.READY_TO_INSTALL || transfer.onCancel != null) {
        TextButton(
            onClick = { DownloadCenter.cancel(info.id) },
            enabled = info.cancellable,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary,
                    disabledContentColor = BossTheme.colors.textMuted,
                ),
        ) {
            Text("Cancel", fontSize = 12.sp)
        }
    }
}

/** How tall the row list grows before it scrolls. */
private val ROWS_MAX_HEIGHT = 260.dp
