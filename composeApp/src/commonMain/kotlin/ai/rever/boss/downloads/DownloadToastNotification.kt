package ai.rever.boss.downloads

import ai.rever.boss.plugin.api.TransferInfo
import ai.rever.boss.plugin.api.TransferPhase
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
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
 * Pure state representation for download toast notifications.
 */
data class DownloadToastData(
    val id: String,
    val title: String,
    val statusText: String,
    val progress: Float?,
    val isComplete: Boolean,
)

/**
 * Formats a Transfer item into toast data representation.
 */
fun formatDownloadToastData(transfer: Transfer): DownloadToastData {
    val info = transfer.info
    val isComplete = info.phase == TransferPhase.READY_TO_INSTALL
    val status = transferStatusLine(info)
    return DownloadToastData(
        id = info.id,
        title = info.title,
        statusText = status,
        progress = info.progress,
        isComplete = isComplete,
    )
}

/**
 * Floating Download Toast Notification UI overlay for active or completed transfers.
 */
@Composable
fun DownloadToastOverlay(
    modifier: Modifier = Modifier,
    onDismiss: ((String) -> Unit)? = null,
) {
    val transfers by DownloadCenter.transfers.collectAsState()
    val activeTransfers = transfers.take(2) // Show at most top 2 active downloads

    if (activeTransfers.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(16.dp),
    ) {
        for (transfer in activeTransfers) {
            val toastData = formatDownloadToastData(transfer)
            DownloadToastCard(
                data = toastData,
                onCancel = {
                    transfer.onCancel?.invoke()
                    onDismiss?.invoke(toastData.id)
                },
                onInstall = transfer.onInstall,
            )
        }
    }
}

@Composable
fun DownloadToastCard(
    data: DownloadToastData,
    onCancel: () -> Unit,
    onInstall: (() -> Unit)? = null,
) {
    val colors = BossTheme.colors

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.raised,
        elevation = 6.dp,
        modifier =
            Modifier
                .width(280.dp)
                .border(1.dp, colors.line, RoundedCornerShape(10.dp)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (data.isComplete) Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription = null,
                        tint = if (data.isComplete) colors.ok else colors.signal,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = data.title,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = colors.textMuted,
                    modifier =
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onCancel() },
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = data.statusText,
                color = colors.textSecondary,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(6.dp))

            if (!data.isComplete && data.progress != null) {
                LinearProgressIndicator(
                    progress = data.progress,
                    color = colors.signal,
                    backgroundColor = colors.line,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            } else if (!data.isComplete) {
                LinearProgressIndicator(
                    color = colors.signal,
                    backgroundColor = colors.line,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            } else if (onInstall != null) {
                Text(
                    text = "Click to Install",
                    color = colors.data,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clickable { onInstall() }
                            .padding(top = 2.dp),
                )
            }
        }
    }
}
