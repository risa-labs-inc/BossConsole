package ai.rever.boss.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Focus-mode hover-reveal state for the four window edges.
 *
 * Each edge has three layers of state:
 * - `hovering*Strip`: raw cursor presence in the invisible edge strip
 * - `hoverReveal*`: set after the reveal delay threshold is met
 * - `show*`: debounced visibility with a grace period, so the bar doesn't
 *   flicker while the mouse travels from the strip onto the revealed content
 */
internal class FocusModeRevealState {
    // Hovering states track raw cursor position in hover strips
    var hoveringTopStrip by mutableStateOf(false)
    var hoveringLeftStrip by mutableStateOf(false)
    var hoveringRightStrip by mutableStateOf(false)
    var hoveringBottomStrip by mutableStateOf(false)

    // Reveal states are set after delay threshold is met
    var hoverRevealTop by mutableStateOf(false)
    var hoverRevealLeft by mutableStateOf(false)
    var hoverRevealRight by mutableStateOf(false)
    var hoverRevealBottom by mutableStateOf(false)

    // Debounced visibility states with grace period for smoother transitions
    var showTopBar by mutableStateOf(false)
    var showLeftSidebar by mutableStateOf(false)
    var showRightSidebar by mutableStateOf(false)
    var showBottomBar by mutableStateOf(false)

    // Interaction sources for sidebar hover tracking
    val topBarInteractionSource = MutableInteractionSource()
    val leftSidebarInteractionSource = MutableInteractionSource()
    val rightSidebarInteractionSource = MutableInteractionSource()
    val bottomBarInteractionSource = MutableInteractionSource()
}

/**
 * Creates the reveal state and runs its per-edge delay and grace-period effects.
 * When focus mode is off, all four bars are simply shown.
 */
@Composable
internal fun rememberFocusModeReveal(
    isFocusModeEnabled: Boolean,
    revealDelayMs: Long,
): FocusModeRevealState {
    val state = remember { FocusModeRevealState() }

    // Track hover state on revealed content itself
    val topBarHovered by state.topBarInteractionSource.collectIsHoveredAsState()
    val leftSidebarHovered by state.leftSidebarInteractionSource.collectIsHoveredAsState()
    val rightSidebarHovered by state.rightSidebarInteractionSource.collectIsHoveredAsState()
    val bottomBarHovered by state.bottomBarInteractionSource.collectIsHoveredAsState()

    // Apply reveal delay before triggering reveal
    LaunchedEffect(state.hoveringTopStrip, revealDelayMs) {
        if (state.hoveringTopStrip) {
            delay(revealDelayMs)
            state.hoverRevealTop = true
        } else {
            state.hoverRevealTop = false
        }
    }

    LaunchedEffect(state.hoveringLeftStrip, revealDelayMs) {
        if (state.hoveringLeftStrip) {
            delay(revealDelayMs)
            state.hoverRevealLeft = true
        } else {
            state.hoverRevealLeft = false
        }
    }

    LaunchedEffect(state.hoveringRightStrip, revealDelayMs) {
        if (state.hoveringRightStrip) {
            delay(revealDelayMs)
            state.hoverRevealRight = true
        } else {
            state.hoverRevealRight = false
        }
    }

    LaunchedEffect(state.hoveringBottomStrip, revealDelayMs) {
        if (state.hoveringBottomStrip) {
            delay(revealDelayMs)
            state.hoverRevealBottom = true
        } else {
            state.hoverRevealBottom = false
        }
    }

    // Add grace period before hiding to prevent flicker when moving mouse from strip to sidebar
    LaunchedEffect(state.hoverRevealTop, topBarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            state.showTopBar = true
        } else if (state.hoverRevealTop || topBarHovered) {
            state.showTopBar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!state.hoverRevealTop && !topBarHovered) {
                state.showTopBar = false
            }
        }
    }

    LaunchedEffect(state.hoverRevealLeft, leftSidebarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            state.showLeftSidebar = true
        } else if (state.hoverRevealLeft || leftSidebarHovered) {
            state.showLeftSidebar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!state.hoverRevealLeft && !leftSidebarHovered) {
                state.showLeftSidebar = false
            }
        }
    }

    LaunchedEffect(state.hoverRevealRight, rightSidebarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            state.showRightSidebar = true
        } else if (state.hoverRevealRight || rightSidebarHovered) {
            state.showRightSidebar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!state.hoverRevealRight && !rightSidebarHovered) {
                state.showRightSidebar = false
            }
        }
    }

    LaunchedEffect(state.hoverRevealBottom, bottomBarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            state.showBottomBar = true
        } else if (state.hoverRevealBottom || bottomBarHovered) {
            state.showBottomBar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!state.hoverRevealBottom && !bottomBarHovered) {
                state.showBottomBar = false
            }
        }
    }

    return state
}

/**
 * Hover reveal strips for focus mode — dynamic sizing to avoid blocking clicks.
 * Each strip uses revealOffset when hidden, 1dp when visible (doesn't block clicks).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.FocusModeHoverStrips(
    state: FocusModeRevealState,
    isFocusModeEnabled: Boolean,
    isAutoRevealEnabled: Boolean,
    revealOffsetDp: Dp,
) {
    if (!isFocusModeEnabled || !isAutoRevealEnabled) return

    // Top hover strip
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (state.showTopBar) 1.dp else revealOffsetDp)
                .align(Alignment.TopStart)
                .zIndex(10f)
                .background(Color.Transparent)
                .onPointerEvent(PointerEventType.Enter) {
                    state.hoveringTopStrip = true
                }.onPointerEvent(PointerEventType.Exit) {
                    state.hoveringTopStrip = false
                },
    )

    // Left hover strip
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(if (state.showLeftSidebar) 1.dp else revealOffsetDp)
                .align(Alignment.CenterStart)
                .zIndex(10f)
                .background(Color.Transparent)
                .onPointerEvent(PointerEventType.Enter) {
                    state.hoveringLeftStrip = true
                }.onPointerEvent(PointerEventType.Exit) {
                    state.hoveringLeftStrip = false
                },
    )

    // Right hover strip
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(if (state.showRightSidebar) 1.dp else revealOffsetDp)
                .align(Alignment.CenterEnd)
                .zIndex(10f)
                .background(Color.Transparent)
                .onPointerEvent(PointerEventType.Enter) {
                    state.hoveringRightStrip = true
                }.onPointerEvent(PointerEventType.Exit) {
                    state.hoveringRightStrip = false
                },
    )

    // Bottom hover strip
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (state.showBottomBar) 1.dp else revealOffsetDp)
                .align(Alignment.BottomStart)
                .zIndex(10f)
                .background(Color.Transparent)
                .onPointerEvent(PointerEventType.Enter) {
                    state.hoveringBottomStrip = true
                }.onPointerEvent(PointerEventType.Exit) {
                    state.hoveringBottomStrip = false
                },
    )
}
