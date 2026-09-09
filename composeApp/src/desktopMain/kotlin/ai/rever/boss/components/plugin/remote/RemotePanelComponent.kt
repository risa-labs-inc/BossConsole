package ai.rever.boss.components.plugin.remote

import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.ui.sdk.WidgetTree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/** Panel adapter over the shared remote surface lifecycle and renderer. */
class RemotePanelComponent(
    val panelId: String,
    val displayName: String,
    processId: String,
    registry: RemoteUiSurfaceRegistry = RemoteUiSurfaceRegistry.shared,
) {
    private val surface = RemoteSurfaceComponent(panelId, displayName, processId, registry)
    val connected: State<Boolean> get() = surface.connected

    @Composable
    fun Content() = surface.Content()

    fun attach() = surface.attach()

    fun updateTree(tree: WidgetTree) = surface.updateTree(tree)

    fun dispose() = surface.dispose()
}
