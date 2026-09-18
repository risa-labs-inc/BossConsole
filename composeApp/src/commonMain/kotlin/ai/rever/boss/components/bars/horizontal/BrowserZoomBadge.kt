package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.plugin.browser.ActiveBrowserRegistry
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.LocalWindowId
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.round

/**
 * Formats a numeric zoom level multiplier (e.g. 1.25) to a human-readable percentage (e.g. "125%").
 */
fun formatZoomPercentage(zoomLevel: Double): String {
    val percentage = round(zoomLevel * 100).toInt()
    return "$percentage%"
}

/**
 * Determines whether the zoom level deviates meaningfully from standard 100% (1.0).
 */
fun isNonDefaultZoom(zoomLevel: Double): Boolean = abs(zoomLevel - 1.0) > 0.01

/**
 * Sleek, interactive address bar zoom badge and quick-controls pill for embedded browser tabs.
 *
 * Appears whenever the window's ACTIVE browser handle reports a non-standard zoom, offering
 * 1-click reset and zoom +/- controls. Everything the badge shows and acts on is resolved
 * through the same [ActiveBrowserRegistry.activeHandleIdByWindow] entry, so the displayed
 * percentage and the button targets cannot drift onto different handles.
 *
 * Known limits, on purpose:
 * - The initial `getZoomLevel()` is a synchronous JxBrowser round trip on the UI thread, taken
 *   only when the active handle changes (a user-driven tab switch or panel change) - the same
 *   profile as the View menu's zoom handlers in BossAppMenuActionEffects. A wedged renderer
 *   can stall that read, and `syncCall` falls back to 1.0 (badge hidden) rather than wedge
 *   composition.
 * - Per-domain zoom persistence is applied on navigation via `ZoomSettingsProvider`, which does
 *   not invoke the handle's zoom listeners, so navigating to a domain with a stored custom zoom
 *   leaves the badge showing the previous value until the next tab switch or manual zoom.
 */
@Composable
fun BrowserZoomBadge(modifier: Modifier = Modifier) {
    val windowId = LocalWindowId.current ?: return
    val activeHandleIds by ActiveBrowserRegistry.activeHandleIdByWindow.collectAsState()
    val activeHandleId = activeHandleIds[windowId]
    val activeHandle = activeHandleId?.let { ActiveBrowserRegistry.handleById(it) }?.takeIf { it.isValid }

    var currentZoom by remember { mutableStateOf(1.0) }
    val zoomListener = remember { { zoom: Double -> currentZoom = zoom } }

    // One listener per active handle: re-keying on the handle id re-runs this whenever the
    // window's active browser surface changes (including a tab switch, which never changes
    // the window set), and onDispose detaches it, so listeners cannot accumulate.
    DisposableEffect(activeHandle) {
        if (activeHandle != null) {
            currentZoom = activeHandle.getZoomLevel()
            activeHandle.addZoomListener(zoomListener)
        }
        onDispose {
            activeHandle?.removeZoomListener(zoomListener)
        }
    }

    val showBadge = activeHandle != null && isNonDefaultZoom(currentZoom)

    AnimatedVisibility(
        visible = showBadge,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        BrowserZoomBadgeSurface(
            handle = activeHandle,
            currentZoom = currentZoom,
        )
    }
}

@Composable
private fun BrowserZoomBadgeSurface(
    handle: BrowserHandle?,
    currentZoom: Double,
) {
    val colors = BossTheme.colors
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.panel,
        elevation = 2.dp,
        // Spacing lives INSIDE the surface: the caller's modifier sits on the
        // AnimatedVisibility root, so any padding there would leave a permanent
        // phantom gap in the top bar while the badge is hidden.
        modifier =
            Modifier
                .padding(end = 8.dp)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            ZoomIconButton(
                icon = Icons.Default.Remove,
                contentDescription = "Zoom Out",
                onClick = { handle?.zoomOut() },
            )
            ZoomPercentageText(
                currentZoom = currentZoom,
                onClick = { handle?.resetZoom() },
            )
            ZoomIconButton(
                icon = Icons.Default.Add,
                contentDescription = "Zoom In",
                onClick = { handle?.zoomIn() },
            )
        }
    }
}

@Composable
private fun ZoomIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).clickable { onClick() },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = BossTheme.colors.textSecondary,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun ZoomPercentageText(
    currentZoom: Double,
    onClick: () -> Unit,
) {
    Text(
        text = formatZoomPercentage(currentZoom),
        color = BossTheme.colors.signal,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClickLabel = "Reset zoom to 100 percent") { onClick() }
                .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
