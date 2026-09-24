package ai.rever.boss.mastery.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ai.rever.boss.mastery.MasteryProgress as KProgress

/**
 * Pins the #1144 wire mapping for guarded-edge skips: a [KProgress.NodeSkipped]
 * flows through the service and lands on the proto's `node_skipped` oneof arm
 * with the same node_id and reason. The proto field is additive (IPC 1.3.0),
 * so this is what older runtimes see when they don't know about the new arm.
 */
class MasteryProgressMappingTest {
    @Test
    fun `NodeSkipped maps to the node_skipped proto arm with the same node_id and reason`() {
        val progress = KProgress.NodeSkipped(nodeId = "skip-1", reason = "edge condition failed")
        val proto = progress.toProto(executionId = "exec-1")

        assertTrue(proto.hasNodeSkipped(), "proto must carry the node_skipped oneof arm")
        assertEquals("skip-1", proto.nodeSkipped.nodeId)
        assertEquals("edge condition failed", proto.nodeSkipped.reason)
    }
}
