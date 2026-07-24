package ai.rever.boss.components.plugin.remote

import ai.rever.boss.components.plugin.OutOfProcessPluginSpawnerImpl
import ai.rever.boss.components.plugin.PluginStateBridge
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.ComponentContext

/**
 * Kernel-side panel component for out-of-process plugins using the split-brain model.
 *
 * Receives serialized state from the child process via [PluginStateBridge] and renders
 * using the existing in-process Compose UI. The child process runs the PluginStateHolder
 * and sends state updates; this component deserializes and renders them.
 *
 * When the bridge is not yet connected, shows a loading indicator.
 * When the child process crashes, delegates to [PluginCrashFallbackUI].
 *
 * @param ctx ComponentContext for Decompose lifecycle
 * @param panelInfo Panel metadata (id, displayName, icon, slot)
 * @param bridge State bridge connected to the child plugin process
 * @param content Composable lambda that receives the deserialized state bytes and renders UI
 */
class BridgedPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val bridge: PluginStateBridge,
    private val content: @Composable (stateBytes: ByteArray, connected: Boolean) -> Unit,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        val connected by bridge.connected.collectAsState()
        val stateBytes by bridge.state.collectAsState()

        if (!connected && stateBytes.isEmpty()) {
            // Not yet connected — show loading
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = BossTheme.colors.signal)
            }
        } else {
            content(stateBytes, connected)
        }
    }
}
