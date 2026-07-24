package ai.rever.boss.components.misc

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.network.NetworkMonitorService
import ai.rever.boss.services.network.NetworkState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import boss_kotlin.composeapp.generated.resources.Res
import boss_kotlin.composeapp.generated.resources.boss_icon
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Screen shown when app starts without internet connectivity
 * Features retry button and auto-retry countdown
 */
@Composable
fun OfflineScreen(onRetry: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    val networkState by NetworkMonitorService.networkState.collectAsState()
    val isAutoRetrying by NetworkMonitorService.isAutoRetrying.collectAsState()
    val nextRetryCountdown by NetworkMonitorService.nextRetryCountdown.collectAsState()
    var isManualRetrying by remember { mutableStateOf(false) }

    // Stop auto-retry when screen is disposed (user goes online)
    DisposableEffect(Unit) {
        onDispose {
            NetworkMonitorService.stopAutoRetry()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BossTheme.colors.panel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // BOSS Logo
            Image(
                painter = painterResource(Res.drawable.boss_icon),
                contentDescription = "BOSS Logo",
                modifier = Modifier.size(80.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Offline icon
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = "No Internet",
                tint = BossTheme.colors.textSecondary,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = "No Internet Connection",
                color = BossTheme.colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = "BOSS requires internet to authenticate.\nPlease check your connection.",
                color = BossTheme.colors.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Retry button
            Button(
                onClick = {
                    scope.launch {
                        isManualRetrying = true
                        onRetry()
                        isManualRetrying = false
                    }
                },
                enabled = !isManualRetrying && networkState !is NetworkState.Checking,
                colors =
                    ButtonDefaults.buttonColors(
                        backgroundColor = BossTheme.colors.signal,
                        contentColor = BossTheme.colors.onSignal,
                        disabledBackgroundColor = BossTheme.colors.raised,
                        disabledContentColor = BossTheme.colors.textSecondary,
                    ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                if (isManualRetrying || networkState is NetworkState.Checking) {
                    CircularProgressIndicator(
                        color = BossTheme.colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text =
                        if (isManualRetrying || networkState is NetworkState.Checking) {
                            "Checking..."
                        } else {
                            "Retry Connection"
                        },
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Auto-retry countdown
            if (isAutoRetrying && nextRetryCountdown > 0) {
                Text(
                    text = "Auto-retry in ${nextRetryCountdown}s",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
            }

            // Retry attempt info
            val retryAttempt = (networkState as? NetworkState.Disconnected)?.retryAttempt ?: 0
            if (retryAttempt > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Attempt $retryAttempt",
                    color = BossTheme.colors.textSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
