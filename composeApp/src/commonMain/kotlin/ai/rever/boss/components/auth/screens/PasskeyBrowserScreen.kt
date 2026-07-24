package ai.rever.boss.components.auth.screens

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.components.bars.horizontal.HorizontalBar
import kotlinx.coroutines.delay

private val passkeyBrowserLogger = BossLogger.forComponent("PasskeyBrowserScreen")

/**
 * Screen that embeds a browser view for WebAuthn passkey registration/authentication
 *
 * This screen displays an embedded JxBrowser instance showing the WebAuthn page,
 * monitors for successful completion via deep link callbacks, and auto-closes.
 */
@Composable
fun PasskeyBrowserScreen(
    url: String,
    sessionId: String,
    onSuccess: () -> Unit,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var browserError by remember { mutableStateOf<String?>(null) }

    passkeyBrowserLogger.debug(LogCategory.AUTH, "Displaying WebAuthn page", mapOf("url" to LogSanitizer.maskUriParams(url)))

    // Monitor for deep link callbacks indicating success
    val deepLink by DeepLinkHandler.deepLinkFlow.collectAsState()
    LaunchedEffect(deepLink) {
        val link = deepLink
        if (link != null && (
            link.contains("auth/verify") ||
            link.contains("passkey/registered") ||
            link.contains("passkey/authenticated")
        )) {
            passkeyBrowserLogger.info(LogCategory.AUTH, "Deep link received, operation successful")

            // Add small delay for visual feedback
            delay(500)

            // Clear the deep link
            DeepLinkHandler.clearDeepLink()

            // Trigger success callback
            onSuccess()
        }
    }

    // Simulate loading completion after delay (will be replaced by actual browser load event)
    LaunchedEffect(Unit) {
        delay(1000)
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BossTheme.colors.panel)
    ) {
        // Title Bar - matches BossTitleBar
        HorizontalBar(height = 26.dp) {
            Text(
                text = "Boss Console",
                color = BossTheme.colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
            )
        }
        Divider(color = BossTheme.colors.line)

        // Top bar - matches main BossTopBar structure
        HorizontalBar(height = 40.dp) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 36.dp, end = 16.dp),  // Space for macOS traffic lights
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side - Back button
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Login",
                        tint = BossTheme.colors.textPrimary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Authentication indicator
                Text(
                    text = "WebAuthn Authentication",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = BossTheme.colors.textPrimary
                )

                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Divider(color = BossTheme.colors.line)

        // Browser content area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BossTheme.colors.panel)
        ) {
            if (browserError != null) {
                // Show error state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Browser Error",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BossTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = browserError ?: "Failed to load browser",
                        fontSize = 14.sp,
                        color = BossTheme.colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Go Back")
                    }
                }
            } else {
                // Show platform-specific browser view
                PasskeyBrowserView(
                    url = url,
                    onLoadComplete = { isLoading = false },
                    onError = { error -> browserError = error }
                )

                // Show loading indicator overlay
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BossTheme.colors.panel.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = BossTheme.colors.signal,
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Loading WebAuthn...",
                                fontSize = 14.sp,
                                color = BossTheme.colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Platform-specific browser view component
 * Implemented differently for each platform (Desktop uses JxBrowser)
 */
@Composable
expect fun PasskeyBrowserView(
    url: String,
    onLoadComplete: () -> Unit,
    onError: (String) -> Unit
)
