package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ContextMenuActionRequest
import ai.rever.boss.ipc.proto.services.ContextMenuIdRequest
import ai.rever.boss.ipc.proto.services.RegisterContextMenuRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.lang.reflect.Modifier as JavaModifier

/**
 * Pins [ContextMenuServiceBridge]'s fail-closed identity requirement (BossConsole#53) and its
 * statelessness (issue #30).
 *
 * The bridge deliberately does nothing with an out-of-process plugin's context menu
 * registrations, but since #1218 it only acknowledges callers that present a verified process
 * identity; an unattributed caller is refused with `PERMISSION_DENIED`. The acknowledge-and-drop
 * half of the #30 contract is pinned over a real server in [UnattributedBridgesIdentityTest];
 * this file pins the refusal half and the no-state invariant directly.
 */
class ContextMenuServiceBridgeTest {
    private val bridge = ContextMenuServiceBridge()

    @Test
    fun `registerContextMenu refuses a caller with no verified identity`() =
        runBlocking {
            val request =
                RegisterContextMenuRequest
                    .newBuilder()
                    .setContextMenuId("ctx_1")
                    .setNodeId("node_1")
                    .build()

            val failure = assertFailsWith<StatusException> { bridge.registerContextMenu(request) }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
        }

    @Test
    fun `unregisterContextMenu refuses a caller with no verified identity`() =
        runBlocking {
            val request = ContextMenuIdRequest.newBuilder().setContextMenuId("never_registered").build()

            val failure = assertFailsWith<StatusException> { bridge.unregisterContextMenu(request) }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
        }

    @Test
    fun `onContextMenuAction refuses a caller with no verified identity`() =
        runBlocking {
            val request =
                ContextMenuActionRequest
                    .newBuilder()
                    .setContextMenuId("ctx_1")
                    .setActionId("Copy")
                    .build()

            val failure = assertFailsWith<StatusException> { bridge.onContextMenuAction(request) }
            assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
        }

    @Test
    fun `bridge keeps no per-registration state`() {
        val instanceFields =
            ContextMenuServiceBridge::class.java.declaredFields
                .filterNot { it.isSynthetic || JavaModifier.isStatic(it.modifiers) }

        assertTrue(
            instanceFields.isEmpty(),
            "The bridge must stay stateless - nothing in the host reads these registrations. " +
                "Found instance fields: ${instanceFields.map { it.name }}",
        )
    }
}
