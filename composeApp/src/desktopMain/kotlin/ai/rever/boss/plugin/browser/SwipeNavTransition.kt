package ai.rever.boss.plugin.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned

/** How long the page takes to slide across. Safari is in this neighbourhood. */
internal const val SLIDE_DURATION_MS = 220

/**
 * How far the incoming page starts behind, as a fraction of the pane.
 *
 * The incoming page moves slower than the outgoing one, which is what makes the two read as a
 * stack rather than as one strip scrolling past. Safari does the same; without it the effect looks
 * like a carousel.
 */
private const val INCOMING_PARALLAX = 0.28f

/** Floor for the settle, so a release a hair from either end is still a movement, not a jump. */
private const val MIN_SETTLE_MS = 60

/** Where a tracked transition is in its life. */
internal enum class SwipePhase {
    /** Following the finger. Progress is whatever the page last reported. */
    TRACKING,

    /** The gesture committed; run out to 1 and navigate. */
    COMMITTING,

    /** The gesture was abandoned; run back to 0 and put the page back. */
    CANCELLING,
}

/** One page transition in flight. */
internal data class SwipeTransition(
    /** The page being arrived at, captured when it was last left. Null if it was never captured. */
    val incoming: ImageBitmap?,
    /** The page being left, captured when the gesture was recognised. */
    val outgoing: ImageBitmap?,
    val direction: SwipeNavDirection,
) {
    /**
     * Whether this is worth showing at all.
     *
     * Both frames are needed. With only the outgoing one the page slides off to reveal nothing,
     * which looks like a rendering fault rather than a navigation; with only the incoming one there
     * is nothing to move. In either case the caller navigates without a transition, which is the
     * behaviour of the chevron style and is never wrong, only plainer.
     */
    val isRenderable: Boolean get() = incoming != null && outgoing != null
}

/**
 * The slide, drawn in place of the browser view.
 *
 * **Why this replaces the view rather than covering it.** Under `HARDWARE_ACCELERATED` the browser
 * is a native surface composited *above* the Compose scene, so anything Compose draws over the page
 * is behind it - the reason the find bar escapes into a heavyweight always-on-top window. Rather
 * than fight that with a full-pane heavyweight window, the transition takes the browser view out of
 * the composition for its duration and draws two still frames instead. The plugin's home surface
 * already proves that detach and reattach is sound: it renders the dashboard *instead of*
 * `Content()` and switching back is unremarkable.
 *
 * Both pages are frozen images for the duration, which is also what Safari does - a page cannot be
 * live while it is sliding, because the one arriving has not loaded yet.
 *
 * **[tracked] is the finger, not an animation.** While the phase is [SwipePhase.TRACKING] this
 * draws exactly where the page says the gesture is, with no easing of its own: an animation
 * chasing a live value is what makes a tracked gesture feel like rubber. Easing is only for the
 * settle at the end, once the finger has stopped having an opinion.
 */
@Composable
internal fun SwipeSlide(
    transition: SwipeTransition,
    phase: SwipePhase,
    tracked: Float,
    onSettled: (SwipePhase) -> Unit,
) {
    val settle = remember(transition) { Animatable(0f) }
    var paneWidthPx by remember(transition) { mutableStateOf(0f) }
    var settling by remember(transition) { mutableStateOf(false) }

    LaunchedEffect(transition, phase, paneWidthPx) {
        if (paneWidthPx <= 0f || phase == SwipePhase.TRACKING) return@LaunchedEffect
        val target = if (phase == SwipePhase.COMMITTING) 1f else 0f
        settle.snapTo(tracked)
        settling = true
        // Proportional to the distance left, so releasing just short of the commit point does not
        // take as long as releasing at the very start. A fixed duration reads as sticky there.
        val remaining = kotlin.math.abs(target - tracked).coerceAtLeast(0.05f)
        val duration = (SLIDE_DURATION_MS * remaining).toInt().coerceAtLeast(MIN_SETTLE_MS)
        settle.animateTo(target, tween(duration, easing = FastOutSlowInEasing))
        onSettled(phase)
    }

    val sign = if (transition.direction == SwipeNavDirection.BACK) 1f else -1f
    val position = if (settling) settle.value else tracked

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // An opaque floor under both frames. The native surface is gone for the duration, so
                // without this the window's own background shows through wherever a frame does not
                // reach - most visibly when the two images differ in aspect from the pane.
                .background(Color.Black)
                .onGloballyPositioned { paneWidthPx = it.size.width.toFloat() },
    ) {
        transition.incoming?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = -sign * paneWidthPx * INCOMING_PARALLAX * (1f - position) },
            )
        }
        transition.outgoing?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = sign * paneWidthPx * position
                            // A soft edge so the moving page reads as being above the other one
                            // rather than as a cut between two images.
                            shadowElevation = 24f
                        },
            )
        }
    }
}
