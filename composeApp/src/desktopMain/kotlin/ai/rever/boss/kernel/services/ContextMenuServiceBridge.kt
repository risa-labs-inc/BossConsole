package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException

/**
 * Kernel-side stub for `ContextMenuService` — **intentionally passive**.
 *
 * Every RPC acknowledges and drops its request. The bridge holds no state, so
 * there is deliberately nothing to read back: an out-of-process plugin's context
 * menu items are *not* rendered by the host. The service is still registered so
 * that a plugin calling an RPC the IPC contract advertises gets `OK` rather than
 * `UNIMPLEMENTED` — once its caller presents a verified process identity
 * (BossConsole#53), which every RPC here now requires: an unattributed caller is
 * refused with `PERMISSION_DENIED` instead of silently acknowledged, so attempts to
 * inject menu registrations or forge actions land in the log with a refusal.
 *
 * This class used to claim it rendered plugin menus "using the host's native
 * context menu system" and forwarded action callbacks. It never did (issue #30),
 * and it cannot without a protocol change:
 *
 * - `ContextMenuProvider.applyContextMenu(Modifier, items)` — the host API this
 *   would have to call — is a `@Composable` modifier decorator. A gRPC handler has
 *   no composition and no UI node modifier to decorate, so the provider is simply
 *   not callable from here. (#23 removed the unused `provider` constructor
 *   parameter that implied otherwise.)
 * - Out-of-process plugin UI reaches the host as a serialized `WidgetTree` rendered
 *   by `RemoteWidgetRenderer`. `WidgetModifier` models only `clickable` /
 *   `clickEventId`; a widget node has no context-menu attachment point, and
 *   `ContextMenuProviderProxy` never populates the `node_id` the proto reserves for
 *   one.
 * - A chosen action has no way home. `ContextMenuItemData.onClick` is an in-process
 *   lambda; over IPC only labels and action ids survive. `OnContextMenuAction` is a
 *   *plugin → kernel* unary RPC, so despite its proto comment it cannot notify a
 *   plugin, and `UIEvent` — the real kernel → plugin channel — has no context-menu
 *   variant.
 *
 * Wiring this for real is therefore a change to the UI protocol, not to this class:
 * a context-menu descriptor on `WidgetModifier`, matching support in
 * `RemoteWidgetRenderer`, and a context-menu `UIEvent` to deliver the picked action
 * back to the plugin process.
 *
 * Nothing calls this service today either: `ContextMenuProviderProxy` is
 * constructed only by `RemotePluginContext.contextMenuProvider`, which no state
 * holder in `boss-microkernel-runtime` reads. In-process plugins are unaffected —
 * they hold the host `ContextMenuProvider` directly and never touch IPC.
 */
class ContextMenuServiceBridge : ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineImplBase() {
    override suspend fun registerContextMenu(request: RegisterContextMenuRequest): Empty {
        authenticatedCallerOrRefuse("registerContextMenu")
        logger.debug(
            LogCategory.SYSTEM,
            "Dropping context menu registration - the host does not render out-of-process context menus (issue #30)",
            mapOf(
                "contextMenuId" to request.contextMenuId,
                "nodeId" to request.nodeId,
                "items" to request.itemsCount,
            ),
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun unregisterContextMenu(request: ContextMenuIdRequest): Empty {
        authenticatedCallerOrRefuse("unregisterContextMenu")
        logger.debug(
            LogCategory.SYSTEM,
            "Ignoring context menu unregistration - nothing was registered",
            mapOf("contextMenuId" to request.contextMenuId),
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun onContextMenuAction(request: ContextMenuActionRequest): Empty {
        authenticatedCallerOrRefuse("onContextMenuAction")
        // The host never shows these menus, so it never triggers an action. A verified caller
        // firing this RPC by hand is acknowledged and dropped — see the class KDoc for why the
        // kernel cannot route it anywhere; an unattributed one never gets this far.
        logger.debug(
            LogCategory.SYSTEM,
            "Ignoring context menu action - the host renders no out-of-process context menus",
            mapOf("contextMenuId" to request.contextMenuId, "actionId" to request.actionId),
        )
        return Empty.getDefaultInstance()
    }

    /**
     * Unary RPCs only: the verified identity, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through with nothing to attribute it to. The passive
     * acknowledge-and-drop contract of issue #30 is unchanged for callers that do present one.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun refuseIdentity(rpc: String): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private companion object {
        val logger = BossLogger.forComponent("ContextMenuServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
