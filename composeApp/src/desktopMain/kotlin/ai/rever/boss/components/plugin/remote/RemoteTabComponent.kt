package ai.rever.boss.components.plugin.remote

import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.ui.sdk.WidgetTree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/** Tab metadata plus the same lifecycle and renderer used by remote panels. */
class RemoteTabComponent(
    val tabId: String,
    val displayName: String,
    processId: String,
    registry: RemoteUiSurfaceRegistry = RemoteUiSurfaceRegistry.shared,
) {
    private val surface = RemoteSurfaceComponent(tabId, displayName, processId, registry)
    private val _title = mutableStateOf(displayName)
    private val _isLoading = mutableStateOf(false)
    val title: State<String> get() = _title
    val isLoading: State<Boolean> get() = _isLoading
    val connected: State<Boolean> get() = surface.connected

    @Composable
    fun Content() = surface.Content()

    fun attach() = surface.attach()

    fun updateTree(tree: WidgetTree) = surface.updateTree(tree)

    fun dispose() = surface.dispose()

    fun updateTitle(title: String) {
        _title.value = title
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
}
