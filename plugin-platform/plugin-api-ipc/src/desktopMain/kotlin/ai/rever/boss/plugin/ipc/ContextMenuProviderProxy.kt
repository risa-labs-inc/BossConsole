package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.ui.ContextMenuItemData
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * IPC proxy implementation of ContextMenuProvider.
 *
 * Context menus require Compose modifier access which can't be serialized over gRPC.
 * This proxy registers context menu intents with the kernel, which then applies them
 * using the host's native context menu implementation.
 *
 * The @Composable applyContextMenu method returns the unmodified modifier since
 * actual context menu application happens kernel-side. The kernel intercepts
 * registered context menu items and applies them during rendering.
 */
class ContextMenuProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : ContextMenuProvider {

    private val stub = ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineStub(channel)

    @Composable
    override fun applyContextMenu(
        modifier: Modifier,
        items: List<ContextMenuItemData>,
    ): Modifier {
        // Context menu application happens kernel-side.
        // Register the items so the kernel knows what to show.
        val contextMenuId = "ctx_${items.hashCode()}"
        scope.launch {
            try {
                val protoItems = items.map { item ->
                    ContextMenuItemProto.newBuilder()
                        .setLabel(item.label)
                        .setActionId(item.label) // Use label as action ID
                        .build()
                }
                stub.registerContextMenu(
                    RegisterContextMenuRequest.newBuilder()
                        .setContextMenuId(contextMenuId)
                        .addAllItems(protoItems)
                        .build()
                )
            } catch (_: Exception) {}
        }
        return modifier
    }
}
