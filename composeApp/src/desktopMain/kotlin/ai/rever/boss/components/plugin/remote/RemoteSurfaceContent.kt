package ai.rever.boss.components.plugin.remote

import ai.rever.boss.ui.sdk.WidgetEvent
import ai.rever.boss.ui.sdk.WidgetTree
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable

/** Shared connection status and rendering for remote panels and tabs. */
@Composable
internal fun RemoteSurfaceContent(
    tree: WidgetTree?,
    connected: Boolean,
    onEvent: (String, WidgetEvent) -> Unit,
) {
    Column {
        if (!connected) Text("Remote surface disconnected")
        tree?.let { RemoteWidgetRenderer(tree = it, onEvent = onEvent) }
    }
}
