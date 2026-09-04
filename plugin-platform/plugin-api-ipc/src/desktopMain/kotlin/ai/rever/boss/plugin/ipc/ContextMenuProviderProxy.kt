package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.ui.ContextMenuItemData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.grpc.ManagedChannel

/**
 * IPC proxy implementation of ContextMenuProvider — **context menus do not work
 * out-of-process** (BossConsole issue #30).
 *
 * Context menus require Compose modifier access, which cannot be serialized over gRPC.
 * The intent was for this proxy to register menu descriptors and for the kernel to attach
 * them to the matching UI node while rendering. The kernel never did: its
 * `ContextMenuServiceBridge` acknowledges and discards every registration, a rendered
 * widget node has no context-menu attachment point, and there is no kernel -> plugin
 * event that could deliver a picked action back to [ContextMenuItemData.onClick].
 *
 * So [applyContextMenu] returns the modifier untouched and the right-click does nothing.
 * The registration is still announced — from a [LaunchedEffect], so once per menu rather
 * than once per recomposition — which keeps a would-be consumer visible in the kernel's
 * debug log. That announcement is advisory: the kernel drops it.
 *
 * Plugins that need a context menu must run in-process, where the host
 * `ContextMenuProvider` decorates a real modifier.
 */
class ContextMenuProviderProxy(
    channel: ManagedChannel,
) : ContextMenuProvider {
    private val stub = ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineStub(channel)

    @Composable
    override fun applyContextMenu(
        modifier: Modifier,
        items: List<ContextMenuItemData>,
    ): Modifier {
        // Key on the labels — which are the entire wire payload — and NOT on `items`.
        // ContextMenuItemData is a data class whose `onClick` lambda takes part in
        // equals/hashCode, so a call-site lambda that isn't remembered makes each list
        // unequal to the last one. Keying on `items` (or hashing it into the id, as the
        // original `ctx_${items.hashCode()}` did) therefore re-announces on every
        // recomposition under an id that never repeats. Labels are content-compared, so
        // this announces once per menu and again only when the menu text changes.
        val labels = items.map { it.label }
        LaunchedEffect(labels) {
            try {
                    val protoItems =
                    labels.mapIndexed { index, label ->
                        ContextMenuItemProto
                            .newBuilder()
                            .setLabel(label)
                            // Stable per-item ids are part of the protocol work the
                            // kernel bridge's KDoc describes.
                            .setActionId("${label}_${index}")
                            .build()
                    }
                stub.registerContextMenu(
                    RegisterContextMenuRequest
                        .newBuilder()
                        .setContextMenuId("ctx_${labels.hashCode()}")
                        .addAllItems(protoItems)
                        .build(),
                )
            } catch (_: Exception) {
                // Advisory call — the kernel discards the registration either way, so a
                // transport failure (or this effect being cancelled as the node leaves
                // composition) must not surface in the plugin's UI. This module has no
                // logger dependency; the kernel-side bridge logs whatever arrives.
            }
        }
        return modifier
    }
}
