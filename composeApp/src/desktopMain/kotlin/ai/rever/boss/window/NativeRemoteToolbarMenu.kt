package ai.rever.boss.window

import ai.rever.boss.components.overlays.HeavyweightCorner
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/** A remotely opened toolbar menu belongs to the captured window, without activating the OS window. */
@Composable
internal fun NativeRemoteToolbarMenu(
    request: NativeToolbarMenuRequest,
    dismiss: () -> Unit,
) {
    val parent = LocalAwtWindow.current ?: return
    val content = (parent as? RootPaneContainer)?.contentPane
    val inset = content?.let { SwingUtilities.convertPoint(it, 0, 0, parent) }
    val left = (request.bounds.left - (inset?.x ?: 0)).coerceIn(0, (parent.width - 280).coerceAtLeast(0))
    val top = (request.bounds.bottom - (inset?.y ?: 0)).coerceAtLeast(0)
    val height = ((request.entries.size + 1) * 38).coerceAtMost(640)
    HeavyweightCorner(
        alignment = Alignment.TopStart,
        initialSize = DpSize(280.dp, height.dp),
        regionInWindow = IntRect(left, top, left + 280, top + height),
        focusable = false,
        owned = true,
    ) {
        Column(
            Modifier
                .width(
                    280.dp,
                ).heightIn(max = 640.dp)
                .background(BossTheme.colors.panel)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(request.title, color = BossTheme.colors.textPrimary, modifier = Modifier.weight(1f).padding(8.dp))
                Text(
                    "×",
                    color = BossTheme.colors.textPrimary,
                    modifier = Modifier.clickable(onClick = dismiss).padding(8.dp),
                )
            }
            request.entries.forEach { entry ->
                Text(
                    text = if (entry.active) "✓ ${entry.label}" else entry.label,
                    color = BossTheme.colors.textPrimary.copy(alpha = if (entry.enabled) 1f else 0.45f),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.enabled) {
                                dismiss()
                                request.select(entry.id)
                            }.padding(10.dp),
                )
            }
        }
    }
}
