package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkError
import BossDarkSurface
import BossDarkTextPrimary
import BossDarkTextSecondary
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The actual download UI content - a Surface with progress info.
 */
@Composable
private fun DownloadSurface(
    progress: Float,
    downloadedMB: Long,
    totalMB: Long,
    status: String,
    error: String?,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .width(450.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(8.dp),
        color = BossDarkBackground
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            // Title
            Text(
                text = "Downloading Browser Engine",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = BossDarkTextPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (error != null) {
                // Error state
                Text(
                    text = "Download failed",
                    fontSize = 14.sp,
                    color = BossDarkError, // Red
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Bound the error message height so a long failure reason scrolls
                // instead of pushing the Retry/Exit buttons out of the window.
                Text(
                    text = error,
                    fontSize = 13.sp,
                    color = BossDarkTextSecondary,
                    modifier = Modifier
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState())
                )
            } else {
                // Status message
                Text(
                    text = status,
                    fontSize = 14.sp,
                    color = BossDarkTextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = BossDarkAccent, // Blue
                    backgroundColor = BossDarkSurface // Dark gray
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Progress text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (totalMB > 0) "${downloadedMB}MB / ${totalMB}MB" else "Connecting...",
                        fontSize = 12.sp,
                        color = BossDarkTextSecondary
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = BossDarkTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (error != null && onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossDarkAccent
                        )
                    ) {
                        Text("Retry", fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = BossDarkTextSecondary
                    )
                ) {
                    Text(if (error != null) "Exit" else "Cancel", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * Full-window content for the Chromium download UI.
 * Use this directly when you have a dedicated window.
 * Fills the entire window with dark background and centers the download surface.
 */
@Composable
fun ChromiumDownloadContent(
    progress: Float,
    downloadedMB: Long,
    totalMB: Long,
    status: String = "Installing BOSS Browser Engine...",
    error: String? = null,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        DownloadSurface(
            progress = progress,
            downloadedMB = downloadedMB,
            totalMB = totalMB,
            status = status,
            error = error,
            onCancel = onCancel,
            onRetry = onRetry
        )
    }
}
