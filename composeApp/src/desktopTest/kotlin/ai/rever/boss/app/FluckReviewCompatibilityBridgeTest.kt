package ai.rever.boss.app

import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FluckReviewCompatibilityBridgeTest {
    @Test
    fun `accepts a current terminal request for the exact window`() {
        val request = event().toSetupFluckOpenRequest(expectedWindowId = "window-1", nowMs = 1_000)

        assertEquals("request-1", request?.requestId)
        assertEquals("terminal-1", request?.terminalId)
        assertEquals("Inspect this setup terminal", request?.prompt)
    }

    @Test
    fun `rejects spoofed wrong-window expired and oversized requests`() {
        assertNull(event(source = "another-plugin").toSetupFluckOpenRequest("window-1", 1_000))
        assertNull(event(windowId = "window-2").toSetupFluckOpenRequest("window-1", 1_000))
        assertNull(event(expiresAtMs = 999).toSetupFluckOpenRequest("window-1", 1_000))
        assertNull(event(prompt = "x".repeat(16_001)).toSetupFluckOpenRequest("window-1", 1_000))
    }

    @Test
    fun `probe is accepted only from terminal for the exact window`() {
        val probe =
            CustomPluginEvent(
                TERMINAL_PLUGIN_ID,
                SETUP_FLUCK_PROBE_EVENT,
                mapOf("windowId" to "window-1", "requestId" to "probe-1"),
            )

        assertEquals("probe-1", probe.toSetupFluckProbeRequest("window-1"))
        assertNull(probe.toSetupFluckProbeRequest("window-2"))
    }

    @Test
    fun `setup open is routed only to its exact host window`() {
        val request = CustomPluginEvent(TERMINAL_PLUGIN_ID, SETUP_OPEN_EVENT, mapOf("windowId" to "window-2"))

        assertEquals(true, request.isSetupOpenRequest("window-2"))
        assertEquals(false, request.isSetupOpenRequest("window-1"))
    }

    @Test
    fun `addressed malformed request carries reason and safe acknowledgement ids`() {
        val route = event(expiresAtMs = "soon").routeSetupFluckOpenRequest("window-1", 1_000)

        assertTrue(route is SetupFluckOpenRoute.Reject)
        assertEquals("missing or malformed expiry", route.reason)
        assertTrue(route.canAcknowledge)
        val acknowledgement =
            setupDebugAcknowledgement(
                route.requestId!!,
                route.terminalId!!,
                accepted = false,
                error = route.reason,
            )
        assertEquals(false, acknowledgement.payload["accepted"])
        assertEquals(route.reason, acknowledgement.payload["error"])
    }

    @Test
    fun `malformed terminal id is refused using its bounded correlation value`() {
        val route =
            event(terminalId = "terminal id with spaces")
                .routeSetupFluckOpenRequest("window-1", 1_000)

        assertTrue(route is SetupFluckOpenRoute.Reject)
        assertEquals("missing or malformed terminal id", route.reason)
        assertEquals("request-1", route.requestId)
        assertEquals("terminal id with spaces", route.terminalId)
        assertTrue(route.canAcknowledge)
    }

    @Test
    fun `integer expiry is accepted without weakening exact numeric validation`() {
        val route = event(expiresAtMs = 2_000).routeSetupFluckOpenRequest("window-1", 1_000)

        assertTrue(route is SetupFluckOpenRoute.Accept)
    }

    @Test
    fun `bounded delivery ledger makes duplicates idempotent and accepts later requests`() {
        val ledger = DeliveredSetupRequestLedger(capacity = 2)
        val first = request("request-1")

        assertEquals(DeliveredSetupRequestLedger.Classification.NEW, ledger.classify(first))
        ledger.recordDelivered(first)
        assertEquals(DeliveredSetupRequestLedger.Classification.DUPLICATE, ledger.classify(first))
        assertEquals(
            DeliveredSetupRequestLedger.Classification.CONFLICT,
            ledger.classify(first.copy(prompt = "different setup data")),
        )
        ledger.recordDelivered(request("request-2"))
        ledger.recordDelivered(request("request-3"))
        assertEquals(DeliveredSetupRequestLedger.Classification.NEW, ledger.classify(first))
    }

    @Test
    fun `undelivered request remains retryable after unavailable panel or publish failure`() {
        val ledger = DeliveredSetupRequestLedger()
        val request = request("request-1")

        // An unavailable panel does not attempt delivery.
        assertEquals(DeliveredSetupRequestLedger.Classification.NEW, ledger.classify(request))
        assertFailsWith<IllegalStateException> {
            ledger.deliverAndRecord(request) { error("publish failed") }
        }
        assertEquals(DeliveredSetupRequestLedger.Classification.NEW, ledger.classify(request))

        ledger.deliverAndRecord(request) {}
        assertEquals(DeliveredSetupRequestLedger.Classification.DUPLICATE, ledger.classify(request))
    }

    @Test
    fun `one failing event does not prevent the next event`() {
        var failures = 0
        var handled = 0

        isolateSetupBridgeEvent(onFailure = { failures++ }) { error("broken plugin event") }
        isolateSetupBridgeEvent(onFailure = { failures++ }) { handled++ }

        assertEquals(1, failures)
        assertEquals(1, handled)
    }

    @Test
    fun `event isolation preserves coroutine cancellation`() {
        assertFailsWith<CancellationException> {
            isolateSetupBridgeEvent(onFailure = { error("must not log cancellation") }) {
                throw CancellationException("cancelled")
            }
        }
    }

    private fun event(
        source: String = TERMINAL_PLUGIN_ID,
        windowId: String = "window-1",
        expiresAtMs: Any = 2_000L,
        prompt: String = "Inspect this setup terminal",
        terminalId: String = "terminal-1",
    ): CustomPluginEvent =
        CustomPluginEvent(
            sourcePluginId = source,
            eventName = SETUP_DEBUG_OPEN_EVENT,
            payload =
                mapOf(
                    "windowId" to windowId,
                    "requestId" to "request-1",
                    "terminalId" to terminalId,
                    "expiresAtMs" to expiresAtMs,
                    "prompt" to prompt,
                ),
        )

    private fun request(id: String) = SetupFluckOpenRequest(id, "terminal-1", "Inspect this setup terminal")
}
