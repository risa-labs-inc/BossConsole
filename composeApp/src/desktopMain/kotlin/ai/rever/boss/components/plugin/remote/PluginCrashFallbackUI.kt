package ai.rever.boss.components.plugin.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * State representing the health of an out-of-process plugin.
 */
enum class PluginProcessState {
    /** Plugin process is running normally. */
    RUNNING,

    /** Plugin process has crashed and can be restarted. */
    CRASHED,

    /** Plugin is being restarted. */
    RESTARTING,

    /** Plugin failed to restart after max attempts. */
    FAILED,

    /** Plugin has been switched to in-process fallback mode. */
    IN_PROCESS_FALLBACK,
}

/**
 * Crash fallback UI displayed when an out-of-process plugin's child process crashes.
 *
 * Shows the crash state and provides:
 * - "Restart Plugin" button to attempt restarting the child process
 * - "Run In-Process" button to fall back to in-process mode
 * - Error details for debugging
 *
 * @param pluginId The crashed plugin's ID
 * @param displayName Human-readable plugin name
 * @param processState Current process state
 * @param errorMessage Optional error details
 * @param restartCount Number of restart attempts so far
 * @param maxRestarts Maximum restart attempts before giving up
 * @param onRestart Callback to restart the plugin process
 * @param onRunInProcess Callback to switch to in-process mode
 */
@Composable
fun PluginCrashFallbackUI(
    pluginId: String,
    displayName: String,
    processState: PluginProcessState,
    errorMessage: String? = null,
    restartCount: Int = 0,
    maxRestarts: Int = 3,
    onRestart: () -> Unit = {},
    onRunInProcess: () -> Unit = {},
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp).widthIn(max = 400.dp),
        ) {
            when (processState) {
                PluginProcessState.CRASHED -> {
                    CrashedContent(
                        displayName = displayName,
                        errorMessage = errorMessage,
                        restartCount = restartCount,
                        maxRestarts = maxRestarts,
                        onRestart = onRestart,
                        onRunInProcess = onRunInProcess,
                    )
                }

                PluginProcessState.RESTARTING -> {
                    RestartingContent(displayName)
                }

                PluginProcessState.FAILED -> {
                    FailedContent(
                        displayName = displayName,
                        errorMessage = errorMessage,
                        restartCount = restartCount,
                        onRunInProcess = onRunInProcess,
                    )
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun CrashedContent(
    displayName: String,
    errorMessage: String?,
    restartCount: Int,
    maxRestarts: Int,
    onRestart: () -> Unit,
    onRunInProcess: () -> Unit,
) {
    Text(
        text = "Plugin Crashed",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.error,
    )

    Text(
        text = "$displayName stopped unexpectedly.",
        fontSize = 14.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )

    if (errorMessage != null) {
        Text(
            text = errorMessage,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (restartCount < maxRestarts) {
        Button(onClick = onRestart) {
            Text("Restart Plugin (${restartCount + 1}/$maxRestarts)")
        }
    }

    Button(
        onClick = onRunInProcess,
        colors =
            ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.primary,
            ),
    ) {
        Text("Run In-Process")
    }
}

@Composable
private fun RestartingContent(displayName: String) {
    CircularProgressIndicator(
        modifier = Modifier.size(32.dp),
        strokeWidth = 3.dp,
    )
    Text(
        text = "Restarting $displayName...",
        fontSize = 14.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
    )
}

@Composable
private fun FailedContent(
    displayName: String,
    errorMessage: String?,
    restartCount: Int,
    onRunInProcess: () -> Unit,
) {
    Text(
        text = "Plugin Failed",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.error,
    )

    Text(
        text = "$displayName failed after $restartCount restart attempts.",
        fontSize = 14.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )

    if (errorMessage != null) {
        Text(
            text = errorMessage,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Button(onClick = onRunInProcess) {
        Text("Run In-Process (Fallback)")
    }
}
