package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.browser.ActiveBrowserRegistry
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Formats a numeric zoom level multiplier (e.g. 1.25) to a human-readable percentage (e.g. "125%").
 */
fun formatZoomPercentage(zoomLevel: Double): String {
    val percentage = kotlin.math.round(zoomLevel * 100).toInt()
    return "$percentage%"
}

/**
 * Determines whether the zoom level deviates meaningfully from standard 100% (1.0).
 */
fun isNonDefaultZoom(zoomLevel: Double): Boolean = kotlin.math.abs(zoomLevel - 1.0) > 0.01

/**
 * Sleek, interactive address bar zoom badge and quick-controls pill for embedded browser tabs.
 * Automatically appears when active tab zoom is not 100%, offering 1-click reset and zoom +/- controls.
 */
@Composable
fun BrowserZoomBadge(
    modifier: Modifier = Modifier,
) {
    val windowId = LocalWindowId.current ?: return
    val windowsWithBrowser by ActiveBrowserRegistry.windowsWithActiveBrowser.collectAsState()
    val hasBrowserInWindow = windowsWithBrowser.contains(windowId)

    var currentZoom by remember(windowId) { mutableStateOf(1.0) }

    LaunchedEffect(windowId, windowsWithBrowser) {
        val handle = ActiveBrowserRegistry.activeIn(windowId)
        if (handle != null) {
            currentZoom = handle.getZoomLevel()
            val listener: (Double) -> Unit = { newZoom ->
                currentZoom = newZoom
            }
            handle.addZoomListener(listener)
        }
    }

    val showBadge = hasBrowserInWindow && isNonDefaultZoom(currentZoom)
    val colors = BossTheme.colors

    AnimatedVisibility(
        visible = showBadge,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.panel,
            elevation = 2.dp,
            modifier =
                Modifier
                    .border(
                        width = 1.dp,
                        color = colors.line,
                        shape = RoundedCornerShape(12.dp),
                    ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                // Zoom Out Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                ActiveBrowserRegistry.activeIn(windowId)?.zoomOut()
                            },
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Zoom Out",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }

                // Percentage Badge (Click to reset to 100%)
                Text(
                    text = formatZoomPercentage(currentZoom),
                    color = colors.signal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                ActiveBrowserRegistry.activeIn(windowId)?.resetZoom()
                            }.padding(horizontal = 4.dp, vertical = 1.dp),
                )

                // Zoom In Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                ActiveBrowserRegistry.activeIn(windowId)?.zoomIn()
                            },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Zoom In",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }

                // Reset Button Icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                ActiveBrowserRegistry.activeIn(windowId)?.resetZoom()
                            },
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Zoom to 100%",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
