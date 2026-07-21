package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId

class PanelEventServiceBridge(
    private val provider: PanelEventProvider,
) : PanelEventServiceGrpcKt.PanelEventServiceCoroutineImplBase() {

    override suspend fun closePanel(request: ClosePanelRequest): Empty {
        val panelId = PanelId(
            panelId = request.panelId,
            pluginId = request.pluginId,
            defaultOrder = request.defaultOrder,
        )
        provider.closePanel(panelId, request.windowId)
        return Empty.getDefaultInstance()
    }

    override suspend fun openPanel(request: OpenPanelRequest): Empty {
        val panelId = PanelId(
            panelId = request.panelId,
            pluginId = request.pluginId,
            defaultOrder = request.defaultOrder,
        )
        provider.openPanel(panelId, request.windowId)
        return Empty.getDefaultInstance()
    }
}
