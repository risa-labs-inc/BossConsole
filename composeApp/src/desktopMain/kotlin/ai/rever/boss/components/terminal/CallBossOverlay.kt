package ai.rever.boss.components.terminal

import ai.rever.boss.services.terminal.VoiceCallMetrics
import ai.rever.boss.services.terminal.VoiceCallSessionManager
import ai.rever.boss.services.terminal.VoiceCallState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern, rich Call Boss HUD overlay rendered inside the active terminal pane.
 */
@Composable
fun CallBossOverlay(
    session: VoiceCallSessionManager,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    val metrics by session.metrics.collectAsState()

    AnimatedVisibility(
        visible = state != VoiceCallState.DISCONNECTED,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E2E).copy(alpha = 0.95f))
                    .border(1.dp, Color(0xFF313244), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CallBossStatusSection(state, metrics)

                WaveformBarVisualizer(
                    state = state,
                    inputLevel = metrics.inputEnergyLevel,
                    outputLevel = metrics.outputEnergyLevel,
                )

                CallBossControlsRow(session, state, metrics)
            }
        }
    }
}

@Composable
private fun CallBossStatusSection(
    state: VoiceCallState,
    metrics: VoiceCallMetrics,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StateIndicatorDot(state)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = getStateLabel(state, metrics),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (metrics.latencyMs > 0) {
                Text(
                    text = "${metrics.latencyMs}ms latency",
                    color = Color(0xFFA6ADC8),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun CallBossControlsRow(
    session: VoiceCallSessionManager,
    state: VoiceCallState,
    metrics: VoiceCallMetrics,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Mute button
        IconButtonPill(
            onClick = { session.toggleMute() },
            backgroundColor = if (metrics.isMuted) Color(0xFFE78284) else Color(0xFF45475A),
        ) {
            Icon(
                imageVector = if (metrics.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (metrics.isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }

        // Interrupt button (visible when agent is speaking)
        if (state == VoiceCallState.SPEAKING) {
            IconButtonPill(
                onClick = { session.handleBargeIn() },
                backgroundColor = Color(0xFFEA999C),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Interrupt",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Retry button (visible on error)
        if (state == VoiceCallState.ERROR) {
            IconButtonPill(
                onClick = { session.startCall() },
                backgroundColor = Color(0xFF89B4FA),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // End call button
        IconButtonPill(
            onClick = { session.endCall() },
            backgroundColor = Color(0xFFF38BA8),
        ) {
            Icon(
                imageVector = Icons.Default.CallEnd,
                contentDescription = "End Call",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun StateIndicatorDot(state: VoiceCallState) {
    val dotColor =
        when (state) {
            VoiceCallState.CONNECTING -> Color(0xFFF9E2AF)
            VoiceCallState.CONNECTED -> Color(0xFF89B4FA)
            VoiceCallState.LISTENING -> Color(0xFFA6E3A1)
            VoiceCallState.SPEAKING -> Color(0xFFCBA6F7)
            VoiceCallState.PROCESSING -> Color(0xFFFAB387)
            VoiceCallState.DISCONNECTING -> Color(0xFF6C7086)
            VoiceCallState.ERROR -> Color(0xFFF38BA8)
            VoiceCallState.DISCONNECTED -> Color.Transparent
        }

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
    )

    val isVoiceActive = state == VoiceCallState.LISTENING || state == VoiceCallState.SPEAKING
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(16.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .scale(if (isVoiceActive) pulseScale else 1.0f)
                    .clip(CircleShape)
                    .background(dotColor),
        )
    }
}

@Composable
private fun WaveformBarVisualizer(
    state: VoiceCallState,
    inputLevel: Float,
    outputLevel: Float,
) {
    val activeLevel = if (state == VoiceCallState.SPEAKING) outputLevel else inputLevel
    val barColor = if (state == VoiceCallState.SPEAKING) Color(0xFFCBA6F7) else Color(0xFFA6E3A1)

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp).padding(horizontal = 16.dp),
    ) {
        repeat(7) { index ->
            val factor = ((index + 1) * 0.25f) % 1f
            val barHeight = (4.dp + (16.dp * (activeLevel * (0.5f + factor * 0.5f)))).coerceIn(4.dp, 18.dp)
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (activeLevel > 0.05f) barColor else Color(0xFF45475A)),
            )
        }
    }
}

@Composable
private fun IconButtonPill(
    onClick: () -> Unit,
    backgroundColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .clickable { onClick() },
    ) {
        content()
    }
}

private fun getStateLabel(
    state: VoiceCallState,
    metrics: VoiceCallMetrics,
): String =
    when (state) {
        VoiceCallState.CONNECTING -> "Connecting..."
        VoiceCallState.CONNECTED -> "Call Connected"
        VoiceCallState.LISTENING -> if (metrics.isMuted) "Microphone Muted" else "Listening to you..."
        VoiceCallState.SPEAKING -> "Boss is speaking..."
        VoiceCallState.PROCESSING -> metrics.activeToolName?.let { "Running $it..." } ?: "Thinking..."
        VoiceCallState.DISCONNECTING -> "Disconnecting..."
        VoiceCallState.ERROR -> "Call Failed"
        VoiceCallState.DISCONNECTED -> "Disconnected"
    }
