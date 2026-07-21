package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * IPC proxy implementation of PanelEventProvider.
 */
class PanelEventProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : PanelEventProvider {

    private val stub = PanelEventServiceGrpcKt.PanelEventServiceCoroutineStub(channel)

    override suspend fun closePanel(panelId: PanelId, windowId: String) {
        try {
            stub.closePanel(
                ClosePanelRequest.newBuilder()
                    .setPanelId(panelId.panelId)
                    .setPluginId(panelId.pluginId)
                    .setDefaultOrder(panelId.defaultOrder)
                    .setWindowId(windowId)
                    .build()
            )
        } catch (_: Exception) {}
    }

    override suspend fun openPanel(panelId: PanelId, windowId: String) {
        try {
            stub.openPanel(
                OpenPanelRequest.newBuilder()
                    .setPanelId(panelId.panelId)
                    .setPluginId(panelId.pluginId)
                    .setDefaultOrder(panelId.defaultOrder)
                    .setWindowId(windowId)
                    .build()
            )
        } catch (_: Exception) {}
    }
}
