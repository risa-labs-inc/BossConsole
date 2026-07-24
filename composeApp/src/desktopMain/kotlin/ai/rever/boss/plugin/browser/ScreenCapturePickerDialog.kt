package ai.rever.boss.plugin.browser

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo

import ai.rever.boss.cache.loadHighQualityFavicon
import ai.rever.boss.components.common.rememberFaviconLoader
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Window
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.teamdev.jxbrowser.capture.AudioCaptureMode
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

private val logger = BossLogger.forComponent("ScreenCapturePickerDialog")

/**
 * Screen capture picker dialog with Tab/Window/Screen tabs.
 */
@Composable
fun ScreenCapturePickerDialog(
    screens: List<ScreenCaptureNotifier.CaptureSourceItem>,
    windows: List<ScreenCaptureNotifier.CaptureSourceItem>,
    browsers: List<ScreenCaptureNotifier.CaptureSourceItem>,
    onDismiss: () -> Unit,
    onSelect: (ScreenCaptureNotifier.CaptureSourceItem, AudioCaptureMode) -> Unit
) {
    var selectedTab by remember { mutableStateOf(
        when {
            browsers.isNotEmpty() -> ShareTab.TAB
            windows.isNotEmpty() -> ShareTab.WINDOW
            else -> ShareTab.SCREEN
        }
    ) }
    var selectedSource by remember { mutableStateOf<ScreenCaptureNotifier.CaptureSourceItem?>(null) }
    var includeAudio by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true)
    ) {
        Surface(
            modifier = Modifier.width(500.dp).heightIn(min = 350.dp, max = 520.dp),
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp
        ) {
            Column {
                // Header
                DialogHeader(title = "Share your screen", subtitle = "Choose what to share", onDismiss = onDismiss)

                // Tab Bar
                TabBar(
                    selectedTab = selectedTab,
                    onTabSelected = {
                        selectedTab = it
                        selectedSource = null
                    },
                    tabCount = browsers.size,
                    windowCount = windows.size,
                    screenCount = screens.size
                )

                Divider(color = BossTheme.colors.line, thickness = 1.dp)

                // Content based on selected tab
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    when (selectedTab) {
                        ShareTab.TAB -> {
                            if (browsers.isEmpty()) {
                                EmptyState(icon = Icons.Default.Tab, message = "No browser tabs available")
                            } else {
                                SourceList(sources = browsers, selectedSource = selectedSource, onSourceSelected = { selectedSource = it })
                            }
                        }
                        ShareTab.WINDOW -> {
                            if (windows.isEmpty()) {
                                EmptyState(icon = Icons.Default.Window, message = "No windows available")
                            } else {
                                SourceList(sources = windows, selectedSource = selectedSource, onSourceSelected = { selectedSource = it })
                            }
                        }
                        ShareTab.SCREEN -> {
                            if (screens.isEmpty()) {
                                EmptyState(icon = Icons.Default.Monitor, message = "No screens available.\nPlease grant screen recording permission.")
                            } else {
                                SourceList(sources = screens, selectedSource = selectedSource, onSourceSelected = { selectedSource = it })
                            }
                        }
                    }
                }

                Divider(color = BossTheme.colors.line, thickness = 1.dp)

                // Footer
                DialogFooter(
                    includeAudio = includeAudio,
                    onAudioToggle = { includeAudio = it },
                    selectedSource = selectedSource,
                    onDismiss = onDismiss,
                    onShare = {
                        selectedSource?.let { source ->
                            val audioMode = if (includeAudio) AudioCaptureMode.CAPTURE else AudioCaptureMode.IGNORE
                            onSelect(source, audioMode)
                        }
                    }
                )
            }
        }
    }
}

private enum class ShareTab { TAB, WINDOW, SCREEN }

@Composable
private fun DialogHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = BossTheme.colors.textPrimary)
            Text(text = subtitle, fontSize = 13.sp, color = BossTheme.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = BossTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TabBar(
    selectedTab: ShareTab,
    onTabSelected: (ShareTab) -> Unit,
    tabCount: Int,
    windowCount: Int,
    screenCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        TabItem(title = "Tab", count = tabCount, isSelected = selectedTab == ShareTab.TAB, onClick = { onTabSelected(ShareTab.TAB) })
        Spacer(modifier = Modifier.width(8.dp))
        TabItem(title = "Window", count = windowCount, isSelected = selectedTab == ShareTab.WINDOW, onClick = { onTabSelected(ShareTab.WINDOW) })
        Spacer(modifier = Modifier.width(8.dp))
        TabItem(title = "Screen", count = screenCount, isSelected = selectedTab == ShareTab.SCREEN, onClick = { onTabSelected(ShareTab.SCREEN) })
    }
}

@Composable
private fun TabItem(title: String, count: Int, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) BossTheme.colors.signal else Color.Transparent
    val textColor = if (isSelected) BossTheme.colors.onSignal else BossTheme.colors.textSecondary
    val borderColor = if (isSelected) BossTheme.colors.signal else BossTheme.colors.line

    Surface(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal, color = textColor)
            if (count > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = if (isSelected) BossTheme.colors.onSignal.copy(alpha = 0.2f) else BossTheme.colors.raised) {
                    Text(text = count.toString(), fontSize = 12.sp, color = textColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SourceList(
    sources: List<ScreenCaptureNotifier.CaptureSourceItem>,
    selectedSource: ScreenCaptureNotifier.CaptureSourceItem?,
    onSourceSelected: (ScreenCaptureNotifier.CaptureSourceItem) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(sources, key = { it.uniqueId }) { source ->
            SourceListItem(
                source = source,
                isSelected = source.uniqueId == selectedSource?.uniqueId,
                onClick = { onSourceSelected(source) }
            )
        }
    }
}

@Composable
private fun SourceListItem(
    source: ScreenCaptureNotifier.CaptureSourceItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) BossTheme.colors.signal.copy(alpha = 0.15f) else Color.Transparent
    val borderColor = if (isSelected) BossTheme.colors.signal else BossTheme.colors.line
    val isLoading = source.name == "Loading..."

    // Get favicon cache key from FluckTabInfo
    val faviconCacheKey = (source.tabInfo as? FluckTabInfo)?.faviconCacheKey

    // Load immediate favicons first (synchronous or fast cache)
    val inMemoryFavicon = (source.tabInfo?.tabIcon as? ai.rever.boss.plugin.api.TabIcon.Image)
    val cachedFavicon = source.tabInfo?.let { rememberFaviconLoader(it) }
    val immediateFavicon = inMemoryFavicon ?: cachedFavicon

    // Load HQ favicon (async from Google) - shows immediateFavicon while loading, then upgrades
    val loadedFavicon = rememberHighQualityFavicon(source.url, faviconCacheKey, immediateFavicon)

    // Determine fallback icon
    val (fallbackIcon, fallbackTint) = when (source.category) {
        ScreenCaptureNotifier.CaptureSourceItem.Category.SCREEN -> Icons.Default.Monitor to BossTheme.colors.signal
        ScreenCaptureNotifier.CaptureSourceItem.Category.WINDOW -> Icons.Default.Window to BossTheme.colors.ok
        ScreenCaptureNotifier.CaptureSourceItem.Category.BROWSER_TAB -> {
            if (isLoading) Icons.Default.Refresh to BossTheme.colors.textSecondary
            else Icons.Default.Tab to BossTheme.colors.data
        }
    }

    val textColor = if (isLoading) BossTheme.colors.textSecondary else BossTheme.colors.textPrimary

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Show favicon if available, otherwise fallback icon
            if (loadedFavicon != null) {
                Image(
                    painter = loadedFavicon.painter,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                )
            } else {
                Icon(imageVector = fallbackIcon, contentDescription = null, tint = fallbackTint, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = source.name, fontSize = 14.sp, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Selected", tint = BossTheme.colors.signal, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, tint = BossTheme.colors.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, fontSize = 14.sp, color = BossTheme.colors.textSecondary, textAlign = TextAlign.Center, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun DialogFooter(
    includeAudio: Boolean,
    onAudioToggle: (Boolean) -> Unit,
    selectedSource: ScreenCaptureNotifier.CaptureSourceItem?,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onAudioToggle(!includeAudio) }) {
            Checkbox(
                checked = includeAudio,
                onCheckedChange = onAudioToggle,
                colors = CheckboxDefaults.colors(checkedColor = BossTheme.colors.signal, uncheckedColor = BossTheme.colors.textSecondary, checkmarkColor = BossTheme.colors.onSignal)
            )
            Text(text = "Share audio", fontSize = 14.sp, color = BossTheme.colors.textPrimary)
        }
        Row {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary)) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onShare,
                enabled = selectedSource != null,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal,
                    disabledBackgroundColor = BossTheme.colors.raised,
                    disabledContentColor = BossTheme.colors.textSecondary
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Share", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Loads a high-quality favicon (128px from Google's service) asynchronously.
 * Returns the fallback immediately while HQ is loading, then upgrades to HQ when available.
 */
@Composable
private fun rememberHighQualityFavicon(
    url: String?,
    standardCacheKey: String?,
    fallback: ai.rever.boss.plugin.api.TabIcon.Image?
): ai.rever.boss.plugin.api.TabIcon.Image? {
    var hqFavicon by remember(url, standardCacheKey) { mutableStateOf<ai.rever.boss.plugin.api.TabIcon.Image?>(null) }

    LaunchedEffect(url, standardCacheKey) {
        if (url != null) {
            hqFavicon = try {
                loadHighQualityFavicon(url, standardCacheKey)
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.BROWSER,
                    "HQ favicon load failed - using fallback icon",
                    mapOf("error" to e.toString()),
                )
                null
            }
        }
    }

    // Return HQ if loaded, otherwise use fallback (in-memory or cached)
    return hqFavicon ?: fallback
}
