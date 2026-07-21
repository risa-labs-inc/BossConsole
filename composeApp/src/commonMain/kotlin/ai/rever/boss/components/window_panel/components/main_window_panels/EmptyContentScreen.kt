package ai.rever.boss.components.window_panel.components.main_window_panels

import BossDarkBackground
import BossDarkTextSecondary
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Tip card configuration with action callback.
 */
private data class TipCard(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val action: (() -> Unit)?,
    val enabled: Boolean
)

/**
 * Empty home screen with interactive tip cards.
 * Disabled tip cards appear dimmed when their action is not available.
 */
@Composable
fun EmptyContent(
    onOpenFile: () -> Unit,
    onNewTab: () -> Unit,
    onSplitPanel: (() -> Unit)? = null,
    onSwitchPanel: (() -> Unit)? = null,
    onNewWindow: () -> Unit
) {
    var selectedTip by remember { mutableStateOf(0) }

    // Tip card definitions with actions
    val tips = listOf(
        TipCard(
            icon = Icons.Outlined.Code,
            title = "Open a file",
            description = "Cmd+O to browse files",
            action = onOpenFile,
            enabled = true
        ),
        TipCard(
            icon = Icons.Outlined.Add,
            title = "New tab",
            description = "Cmd+T opens tab dialog",
            action = onNewTab,
            enabled = true
        ),
        TipCard(
            icon = Icons.Outlined.ViewColumn,
            title = "Split panels",
            description = "Right-click tab → Split Right/Down",
            action = onSplitPanel,
            enabled = onSplitPanel != null
        ),
        TipCard(
            icon = Icons.Outlined.SwapHoriz,
            title = "Switch panels",
            description = "Cmd+← → to navigate panels",
            action = onSwitchPanel,
            enabled = onSwitchPanel != null
        ),
        TipCard(
            icon = Icons.Outlined.OpenInBrowser,
            title = "New window",
            description = "Cmd+N creates new window",
            action = onNewWindow,
            enabled = true
        )
    )

    // Animation values for BOSS logo
    val infiniteTransition = rememberInfiniteTransition()
    val scale = infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val rotation = infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BossDarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Animated BOSS logo
            Box(
                modifier = Modifier.size(120.dp).scale(scale.value).rotate(rotation.value),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dashboard,
                    contentDescription = "BOSS",
                    tint = Color(0xFF4A9EFF),
                    modifier = Modifier.size(80.dp)
                )
            }

            // Welcome text
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Welcome to BOSS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Multi-panel development environment", color = BossDarkTextSecondary, fontSize = 16.sp)
            }

            // Quick tips with interactive cards
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Quick Tips", color = Color(0xFF4A9EFF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tips.forEachIndexed { index, tip ->
                        Card(
                            icon = tip.icon,
                            title = tip.title,
                            description = tip.description,
                            isSelected = index == selectedTip,
                            enabled = tip.enabled,
                            onClick = {
                                selectedTip = index
                                if (tip.enabled) tip.action?.invoke()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Rotating motivational messages
            val messages = listOf(
                "Ready to build something amazing? 🚀",
                "Code is poetry in motion 💫",
                "Let's turn ideas into reality ✨",
                "Your next breakthrough awaits 🌟",
                "Time to create magic 🎨"
            )
            var messageIndex by remember { mutableStateOf((0..messages.lastIndex).random()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(5000)
                    messageIndex = (0..messages.lastIndex).random()
                }
            }
            Text(
                text = messages[messageIndex],
                color = BossDarkTextSecondary.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

/**
 * Interactive tip card with disabled state support.
 */
@Composable
private fun Card(
    icon: ImageVector,
    title: String,
    description: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animations for selection and disabled states
    val animatedAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.3f
            isSelected -> 1f
            else -> 0.6f
        },
        animationSpec = tween(300)
    )

    val animatedScale by animateFloatAsState(
        targetValue = if (isSelected && enabled) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.4f)
    )

    // Colors based on state
    val backgroundColor = when {
        !enabled -> Color(0xFF1A1B1E)  // Darker for disabled
        isSelected -> Color(0xFF2A2D30)
        else -> Color(0xFF1E1F22)
    }

    val iconTint = when {
        !enabled -> Color(0xFF4A4A4A)  // Muted gray for disabled
        isSelected -> Color(0xFF4A9EFF)
        else -> BossDarkTextSecondary
    }

    val textColor = when {
        !enabled -> Color(0xFF666666)  // Dim text for disabled
        isSelected -> Color.White
        else -> BossDarkTextSecondary
    }

    Column(
        modifier = modifier
            .scale(animatedScale)
            .alpha(animatedAlpha)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .then(
                if (enabled) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier  // No clickable modifier when disabled
                }
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(32.dp)
        )

        Text(
            text = title,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        // Description appears when selected
        AnimatedVisibility(
            visible = isSelected,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = description,
                color = BossDarkTextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
