package ai.rever.boss.pet

import ai.rever.boss.config.BossPetSettingsManager
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The floating BOSS pet: a small, draggable, always-on-top companion that shows what the session's
 * agents and long-running actions are doing (see #386).
 *
 * Mounted once from `main.kt`'s `application {}` scope, guarded by [BossPetSettingsManager], so it is
 * app-global rather than per-window and never appears unless the user has opted in. It renders
 * [BossPet]'s controller and is driven by whatever calls that controller; [BossPetHost] wires
 * one real producer (the app updater) as a worked example, read-only.
 *
 * **Fixed palette, not [ai.rever.boss.plugin.ui.BossColors].** Those resolve through a CompositionLocal
 * that a bare top-level [Window] does not carry, so reading them here would throw or read a default.
 * A companion this small owning its own handful of colours is the same trade the overlays make for
 * the same reason.
 */
@Composable
fun BossPetWindow(onHide: () -> Unit) {
    val mood by BossPet.controller.mood.collectAsState()

    val initial = remember { initialPosition() }
    val windowState =
        rememberWindowState(
            size = DpSize(PET_WIDTH.dp, PET_HEIGHT.dp),
            position = WindowPosition(initial.first.dp, initial.second.dp),
        )

    Window(
        onCloseRequest = onHide,
        state = windowState,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
        title = "BOSS",
        icon = BossWindowIcon.painter,
    ) {
        ApplyBossWindowIcon(window)
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(rememberPetDragModifier(windowState))
                    .pointerInput(Unit) {
                        detectTapGestures { BossPet.controller.dismissAnnouncement() }
                    },
            contentAlignment = Alignment.Center,
        ) {
            BossPetCard(mood, onHide)
        }
    }
}

@Composable
private fun rememberPetDragModifier(windowState: WindowState): Modifier {
    val saveScope = rememberCoroutineScope()
    val saveMutex = remember { Mutex() }
    return Modifier.pointerInput(Unit) {
        val finishDrag: () -> Unit = {
            val p = windowState.position
            if (p is WindowPosition.Absolute) {
                val anchor =
                    petPosition(
                        p.x.value.roundToInt(),
                        p.y.value.roundToInt(),
                        connectedPetScreens(),
                        PET_WIDTH,
                        PET_HEIGHT,
                    )
                windowState.position = WindowPosition(anchor.first.dp, anchor.second.dp)
                saveScope.launch { savePetAnchor(anchor, saveMutex) }
            }
        }
        detectDragGestures(
            onDragEnd = finishDrag,
            onDragCancel = finishDrag,
        ) { change, drag ->
            change.consume()
            val p = windowState.position
            if (p is WindowPosition.Absolute) {
                windowState.position =
                    WindowPosition(p.x + drag.x.toDp(), p.y + drag.y.toDp())
            }
        }
    }
}

private suspend fun savePetAnchor(
    anchor: Pair<Int, Int>,
    mutex: Mutex,
) {
    mutex.withLock {
        withContext(Dispatchers.IO) {
            BossPetSettingsManager.setAnchor(anchor.first, anchor.second)
        }
    }
}

/** The pet's face and message for [mood]. Drag and click handling live on the hosting Box. */
@Composable
private fun BossPetCard(
    mood: BossPetMood,
    onHide: () -> Unit,
) {
    val glyph = glyphFor(mood)
    val accent = accentFor(mood)
    val label = labelFor(mood)

    Row(
        modifier =
            Modifier
                .widthIn(max = PET_WIDTH.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CARD_BG)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BossPetFace(glyph, accent, working = mood is BossPetMood.Working)
        if (label != null) {
            Text(
                text = label,
                color = TEXT,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "Hide pet until restart" }
                    .clickable(onClick = onHide),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "×", color = TEXT)
        }
    }
}

@Composable
private fun BossPetFace(
    glyph: String,
    accent: Color,
    working: Boolean,
) {
    // A gentle pulse while working, steady otherwise - a resting pet should not twitch.
    val pulse =
        if (working) {
            val transition = rememberInfiniteTransition(label = "pet-pulse")
            transition
                .animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(700),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "pet-pulse-alpha",
                ).value
        } else {
            1f
        }

    Box(
        modifier =
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f))
                .alpha(pulse),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, fontSize = 16.sp)
    }
}

private fun glyphFor(mood: BossPetMood): String =
    when (mood) {
        is BossPetMood.Idle -> "🤖"
        is BossPetMood.Working -> "⚙️"
        is BossPetMood.Completed -> "✅"
        is BossPetMood.Failed -> "⚠️"
    }

private fun accentFor(mood: BossPetMood): Color =
    when (mood) {
        is BossPetMood.Idle -> ACCENT
        is BossPetMood.Working -> ACCENT
        is BossPetMood.Completed -> SUCCESS
        is BossPetMood.Failed -> DANGER
    }

private fun labelFor(mood: BossPetMood): String? =
    when (mood) {
        is BossPetMood.Idle -> null
        is BossPetMood.Working -> if (mood.activeCount > 1) "Working - ${mood.activeCount} tasks" else "Working..."
        is BossPetMood.Completed -> mood.label
        is BossPetMood.Failed -> if (mood.occurrences > 1) "${mood.occurrences}× ${mood.label}" else mood.label
    }

/** Restore on a connected display, falling back to the primary display after monitor removal. */
private fun initialPosition(): Pair<Int, Int> {
    val saved = BossPetSettingsManager.settings.value
    return petPosition(saved.anchorX, saved.anchorY, connectedPetScreens(), PET_WIDTH, PET_HEIGHT)
}

private const val PET_WIDTH = 220
private const val PET_HEIGHT = 56

private val CARD_BG = Color(0xF01B1D22)
private val TEXT = Color(0xFFE8EAED)
private val ACCENT = Color(0xFF4C8DFF)
private val SUCCESS = Color(0xFF3FB950)
private val DANGER = Color(0xFFE5534B)
