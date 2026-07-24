package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*

/**
 * Kernel-side bridge for ContextMenuService.
 *
 * Context menus in the out-of-process model are handled differently than in-process:
 * the plugin registers menu items via gRPC, and the kernel renders them using
 * the host's native context menu system. Action callbacks are forwarded back
 * to the plugin process.
 */
class ContextMenuServiceBridge : ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineImplBase() {

    private val registeredMenus = java.util.concurrent.ConcurrentHashMap<String, RegisterContextMenuRequest>()
    private val actionCallbacks = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

    override suspend fun registerContextMenu(request: RegisterContextMenuRequest): Empty {
        registeredMenus[request.contextMenuId] = request
        return Empty.getDefaultInstance()
    }

    override suspend fun unregisterContextMenu(request: ContextMenuIdRequest): Empty {
        registeredMenus.remove(request.contextMenuId)
        actionCallbacks.remove(request.contextMenuId)
        return Empty.getDefaultInstance()
    }

    override suspend fun onContextMenuAction(request: ContextMenuActionRequest): Empty {
        actionCallbacks[request.contextMenuId]?.invoke(request.actionId)
        return Empty.getDefaultInstance()
    }

    fun getRegisteredMenu(menuId: String): RegisterContextMenuRequest? = registeredMenus[menuId]

    fun setActionCallback(menuId: String, callback: (String) -> Unit) {
        actionCallbacks[menuId] = callback
    }
}
