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

/** One page transition in flight. */
internal data class SwipeTransition(
    /** The page being arrived at, captured when it was last left. Null if it was never captured. */
    val incoming: ImageBitmap?,
    /** The page being left, captured at the moment the gesture committed. */
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
 */
@Composable
internal fun SwipeSlide(
    transition: SwipeTransition,
    onFinished: () -> Unit,
) {
    val progress = remember(transition) { Animatable(0f) }
    var paneWidthPx by remember(transition) { mutableStateOf(0f) }

    LaunchedEffect(transition, paneWidthPx) {
        // Waiting for a measured width matters: starting at 0 would play the whole animation in the
        // first frame, before layout, and the transition would be a flicker.
        if (paneWidthPx <= 0f) return@LaunchedEffect
        progress.animateTo(1f, tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing))
        onFinished()
    }

    // Going back, the page on top moves right and the one behind follows it in from the left.
    val sign = if (transition.direction == SwipeNavDirection.BACK) 1f else -1f
    val eased = progress.value

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
                        .graphicsLayer { translationX = -sign * paneWidthPx * INCOMING_PARALLAX * (1f - eased) },
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
                            translationX = sign * paneWidthPx * eased
                            // A soft edge so the moving page reads as being above the other one
                            // rather than as a cut between two images.
                            shadowElevation = 24f
                        },
            )
        }
    }
}
