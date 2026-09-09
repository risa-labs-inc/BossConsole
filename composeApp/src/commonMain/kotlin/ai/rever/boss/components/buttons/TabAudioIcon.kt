package ai.rever.boss.components.buttons

import ai.rever.boss.components.plugin.TabAudioRegistry
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

/** Crossfade inside the existing favicon slot without changing the title's available width. */
@Composable
internal fun TabAudioIcon(
    tabId: String?,
    modifier: Modifier,
    favicon: @Composable () -> Unit,
) {
    val audioAlpha by animateFloatAsState(
        targetValue = if (TabAudioRegistry.isPlaying(tabId)) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "audioPlayingIndicator",
    )
    Box(modifier) {
        Box(Modifier.alpha(1f - audioAlpha)) { favicon() }
        if (audioAlpha > 0f) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = "Playing audio",
                tint = BossTheme.colors.signal,
                modifier = Modifier.matchParentSize().alpha(audioAlpha),
            )
        }
    }
}
