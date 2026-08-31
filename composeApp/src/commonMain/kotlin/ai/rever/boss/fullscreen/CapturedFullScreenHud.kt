package ai.rever.boss.fullscreen

import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** How long the bar stays up after entering, before it takes the pointer to bring it back. */
internal const val HUD_DWELL_MS = 6000L

/** Upper bounds for the overlay window, before its content measures. See [OverlayCorner]. */
internal val HUD_MAX_WIDTH = 620.dp

internal val HUD_MAX_HEIGHT = 240.dp

/** Height of the strip at the top of the window that reveals the bar. */
internal val HUD_REVEAL_STRIP_HEIGHT = 24.dp

/**
 * What the reminder says, given the live keymap.
 *
 * Pure and separate from the composable so the wording can be tested, and because the one thing it
 * must never do is go stale: both escapes are rebindable, so a hardcoded string would confidently
 * tell a user to press a combination that no longer does anything. The hold-Escape line is a
 * literal precisely because that one cannot be rebound.
 */
internal fun capturedHudLines(
    settings: KeymapSettings,
    limitations: Set<CaptureLimitation>,
): List<String> {
    val exit = settings.getBinding(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE)?.displayString()
    val release = settings.getBinding(KeymapActions.POINTER_RELEASE)?.displayString()

    val lines = mutableListOf<String>()
    // Falls back to the hold when the action has been unbound entirely, rather than printing an
    // empty chord: an unbound exit is exactly when someone needs to be told the other way out.
    lines += if (exit != null) "$exit to leave captured full screen" else "Hold Esc to leave captured full screen"
    if (release != null) lines += "$release to release the pointer"
    lines += "Hold Esc for 2 seconds if you get stuck"

    limitations.forEach { limitation ->
        lines +=
            when (limitation) {
                CaptureLimitation.POINTER_NOT_CONFINED -> "Note: the pointer could not be confined on this system"
                CaptureLimitation.KEYBOARD_NOT_GRABBED -> "Note: your system shortcuts are still active"
                CaptureLimitation.WAYLAND_NO_GRAB -> "Note: Wayland does not allow confining the pointer"
            }
    }
    return lines
}

/**
 * The control bar for a captured session: shown on entry, and again whenever the pointer reaches the
 * top edge.
 *
 * ## What it does NOT carry
 *
 * Settings, Toolbox, Tools, Search and Sign Out are **not** here. They stay in the floating
 * quick-actions cluster they already use whenever the bar that owns them is gone - see
 * `focusQuickActionsPlacement`, which answers FLOATING while captured.
 *
 * Two earlier versions got this wrong in opposite directions. The first dropped the actions
 * entirely, which left Toolbox with no route at all on macOS (its menu is in the menu bar, which
 * this mode hides) and Sign Out with no route anywhere, since it has no shortcut - the
 * `docs/release-notes/v9.4.13.md:47` regression by another door. The second put them in this bar,
 * which fixed reachability by giving four buttons a second home and moving them out from under the
 * user on entering the mode. A mode is not a reason to relocate controls.
 *
 * What is left here is what belongs only to this mode: the way out, and the reminder naming it.
 *
 * ## Heavyweight, and why the reveal strip is still best-effort
 *
 * The bar itself goes through [OverlayCorner], so on the HARDWARE_ACCELERATED path it is its own
 * always-on-top window and layers ABOVE the browser surface. Drawn in place it was invisible over
 * any browser tab - composed, measured, behind the page - which is the whole reason the mode looked
 * like it showed nothing on entry.
 *
 * [OverlayCorner] rather than `OverlayHud`: a HUD is parent-sized and swallows every click beneath
 * it, which is only acceptable for something up while a key is held. This bar lingers for
 * [HUD_DWELL_MS] and carries buttons, so its window must cover no more than itself.
 *
 * **The reveal strip is NOT heavyweight and cannot see the pointer over a browser surface.** It is
 * ordinary Compose hover, so the page composites above it and the pointer never crosses it. That is
 * not a Windows-only gap any more - hardware rendering is the default on macOS and Linux too - and
 * in this mode the chrome is hidden, so content starts at the window's top edge and the strip is
 * behind it whenever the front tab is a browser. Over Compose content it works.
 *
 * So the dwell on entry is the reliable half, and the shortcuts and the hardwired hold are what the
 * reminder always names. Making the strip heavyweight would fix it at the cost of an always-on-top
 * window swallowing clicks along the window's top edge for the whole session, which is a worse
 * trade for content the user asked to have the display to itself.
 *
 * @param exitButton the blue button, which leaves the mode.
 * @param actions extra controls belonging to the mode itself. Empty today, and deliberately not
 *   where the window's ordinary actions go - see above.
 */
@Composable
fun BoxScope.CapturedFullScreenHud(
    session: CapturedFullScreen,
    exitButton: (@Composable () -> Unit)? = null,
    actions: List<@Composable () -> Unit> = emptyList(),
) {
    if (!session.active) return

    val keymap by KeymapSettingsManager.currentSettings.collectAsState()
    val stripInteraction = remember { MutableInteractionSource() }
    val barInteraction = remember { MutableInteractionSource() }
    val nearTop by stripInteraction.collectIsHoveredAsState()
    val onBar by barInteraction.collectIsHoveredAsState()
    var dwelling by remember { mutableStateOf(true) }

    // Restarted per session, so re-entering shows the bar again.
    LaunchedEffect(session.windowId) {
        dwelling = true
        delay(HUD_DWELL_MS)
        dwelling = false
    }

    // Always present while captured, even while the bar is up, so moving to the top edge holds it
    // open instead of letting it time out from under the pointer.
    Box(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(HUD_REVEAL_STRIP_HEIGHT)
                .hoverable(stripInteraction),
    )

    // Composed only while wanted, because on the heavyweight path this IS a window: an
    // AnimatedVisibility around it would keep an always-on-top window alive at zero alpha.
    if (dwelling || nearTop || onBar) {
        OverlayCorner(
            alignment = Alignment.TopCenter,
            // A generous upper bound, per OverlayCorner: too small and the content measures
            // clipped, then the window settles at the clipped size. Five action buttons plus the
            // exit button, over up to five lines of reminder.
            initialSize = DpSize(HUD_MAX_WIDTH, HUD_MAX_HEIGHT),
            // Clicks reach a non-focusable AWT window, so the buttons work. Focusable would make
            // the main window inactive for as long as the bar is up, and BOSS treats focus leaving
            // as a reason to end the session.
            focusable = false,
        ) {
            CapturedControlBar(
                lines = capturedHudLines(keymap, session.limitations),
                exitButton = exitButton,
                actions = actions,
                // Hovering the bar itself keeps it up, so a pointer travelling from the strip down
                // to a button does not dismiss the thing it is reaching for.
                modifier = Modifier.padding(top = 16.dp).hoverable(barInteraction),
            )
        }
    }
}

/** The revealed card: the way out, the actions the hidden chrome owned, and the shortcut reminder. */
@Composable
private fun CapturedControlBar(
    lines: List<String>,
    exitButton: (@Composable () -> Unit)?,
    actions: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BossTheme.colors.raised,
        elevation = 8.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (exitButton != null || actions.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    exitButton?.invoke()
                    if (exitButton != null && actions.isNotEmpty()) {
                        Divider(color = BossTheme.colors.line, modifier = Modifier.height(20.dp).width(1.dp))
                    }
                    actions.forEach { it() }
                }
            }

            lines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    color = if (index == 0) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                    fontSize = if (index == 0) 13.sp else 11.sp,
                    fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
