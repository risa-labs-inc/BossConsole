package ai.rever.boss.utils

import ai.rever.boss.components.events.PluginActionEventBus
import ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl
import ai.rever.boss.plugin.api.DeepLinkActionHandler
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the refusals around the `boss://plugin?id=…&action=…` approval gate.
 *
 * A well-formed external action link is held for the operator to confirm —
 * that half of the contract lives in [PluginActionOriginTest]. These tests pin
 * the other half: a link whose own text is malformed is refused outright for
 * every origin, and a well-formed link too large for a confirmation prompt to
 * show in full is refused rather than held, both decided by
 * [pluginActionDisposition] before the approval queue is consulted.
 *
 * Each refusal is asserted end to end, which is what separates these cases
 * from [PluginActionDispositionTest]'s pinning of the same predicate: the link
 * must answer a real not-handled verdict instead of the queued `null` a held
 * action reports, must never reach a registered handler, and must not consume
 * one of the retention slots a pending confirmation waits in. A refused link
 * that still queued itself would misreport itself to the single-instance caller
 * and crowd out genuine requests.
 */
class PluginActionRefusalBoundaryTest {
    /** Lets anything already queued on the EDT finish before an assertion reads the counter. */
    private fun drainUiThread() {
        SwingUtilities.invokeAndWait { }
    }

    private fun registerCounting(
        handlerId: String,
        calls: AtomicInteger,
    ) {
        DeepLinkActionRegistryImpl.register(
            object : DeepLinkActionHandler {
                override val handlerId = handlerId

                override fun handle(
                    action: String,
                    params: Map<String, String>,
                ): Boolean {
                    calls.incrementAndGet()
                    return action == "ping"
                }
            },
        )
    }

    @Test
    fun `an oversized action is refused outright and never queued`() {
        // The size bound exists only for what a prompt must display, so an
        // oversized link is refused as EXTERNAL here; the operator's own link
        // is never shown, which PluginActionDispositionTest pins separately.
        val id = "oversized-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        PluginActionEventBus.clearForTest()
        try {
            val oversized = "a".repeat(PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH + 1)
            val verdict =
                DeepLinkHandler.processDeepLink(
                    "boss://plugin?id=$id&action=$oversized",
                    DeepLinkOrigin.EXTERNAL,
                )
            assertFalse(
                runBlocking { requireNotNull(verdict).await() },
                "an action too large to show in full must report not-handled, not be queued",
            )
            drainUiThread()
            assertEquals(0, calls.get(), "an oversized action must never reach its handler")
            assertEquals(
                0,
                PluginActionEventBus.pendingCount,
                "an oversized action must not occupy a confirmation slot",
            )
        } finally {
            PluginActionEventBus.clearForTest()
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `an oversized handler id is refused outright and never queued`() {
        // Registered under the oversized id itself, so the refusal is proven to
        // happen before dispatch rather than by a failed registry lookup.
        val id = "i".repeat(PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH + 1)
        val calls = AtomicInteger()
        registerCounting(id, calls)
        PluginActionEventBus.clearForTest()
        try {
            val verdict =
                DeepLinkHandler.processDeepLink(
                    "boss://plugin?id=$id&action=ping",
                    DeepLinkOrigin.EXTERNAL,
                )
            assertFalse(
                runBlocking { requireNotNull(verdict).await() },
                "a handler id too large to show in full must report not-handled, not be queued",
            )
            drainUiThread()
            assertEquals(0, calls.get(), "an oversized handler id must never reach its handler")
            assertEquals(
                0,
                PluginActionEventBus.pendingCount,
                "an oversized handler id must not occupy a confirmation slot",
            )
        } finally {
            PluginActionEventBus.clearForTest()
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `too many parameters is refused outright and never queued`() {
        val id = "oversized-params-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        PluginActionEventBus.clearForTest()
        try {
            // One parameter key beyond the bound, every key well formed, so the
            // count check is what refuses rather than a malformed token.
            val extraParams =
                (0..PLUGIN_ACTION_CONFIRM_MAX_PARAM_KEYS).joinToString("&") { "k$it=v$it" }
            val verdict =
                DeepLinkHandler.processDeepLink(
                    "boss://plugin?id=$id&action=ping&$extraParams",
                    DeepLinkOrigin.EXTERNAL,
                )
            assertFalse(
                runBlocking { requireNotNull(verdict).await() },
                "more parameters than a prompt can list must report not-handled, not be queued",
            )
            drainUiThread()
            assertEquals(0, calls.get(), "an over-parameterised action must never reach its handler")
            assertEquals(
                0,
                PluginActionEventBus.pendingCount,
                "an over-parameterised action must not occupy a confirmation slot",
            )
        } finally {
            PluginActionEventBus.clearForTest()
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `a blank action is refused for every origin, not held for confirmation`() {
        val id = "blank-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        PluginActionEventBus.clearForTest()
        try {
            for (origin in DeepLinkOrigin.entries) {
                val verdict = DeepLinkHandler.processDeepLink("boss://plugin?id=$id&action=", origin)
                assertFalse(
                    runBlocking { requireNotNull(verdict).await() },
                    "a blank action names nothing an operator could confirm, so it is refused whoever asked",
                )
            }
            drainUiThread()
            assertEquals(0, calls.get(), "a blank action must never reach its handler")
            assertEquals(
                0,
                PluginActionEventBus.pendingCount,
                "a blank action must not occupy a confirmation slot",
            )
        } finally {
            PluginActionEventBus.clearForTest()
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `a malformed handler id is refused for every origin, not held for confirmation`() {
        // The handler is registered under the malformed id itself, so a wrong
        // routing to dispatch would be observable as a handler call.
        val suffix = System.nanoTime()
        val registeredId = "malformed-id-test-$suffix\nInjected"
        val calls = AtomicInteger()
        registerCounting(registeredId, calls)
        PluginActionEventBus.clearForTest()
        try {
            for (origin in DeepLinkOrigin.entries) {
                val verdict =
                    DeepLinkHandler.processDeepLink(
                        "boss://plugin?id=malformed-id-test-$suffix%0AInjected&action=ping",
                        origin,
                    )
                assertFalse(
                    runBlocking { requireNotNull(verdict).await() },
                    "a handler id carrying a control character cannot be shown in full, so it is refused whoever asked",
                )
            }
            drainUiThread()
            assertEquals(0, calls.get(), "a malformed handler id must never reach its handler")
            assertEquals(
                0,
                PluginActionEventBus.pendingCount,
                "a malformed handler id must not occupy a confirmation slot",
            )
        } finally {
            PluginActionEventBus.clearForTest()
            DeepLinkActionRegistryImpl.unregister(registeredId)
        }
    }

    @Test
    fun `a malformed parameter key is refused for every origin, not held for confirmation`() {
        val id = "malformed-key-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        PluginActionEventBus.clearForTest()
        try {
            // The control character is raw in the link because query keys reach
            // the gate undecoded: a key carrying a line break could forge extra
            // lines in the prompt describing the request.
            for (origin in DeepLinkOrigin.entries) {
                val verdict =
                    DeepLinkHandler.processDeepLink(
                        "boss://plugin?id=$id&action=ping&forged" + '\n' + "key=1",
                        origin,
                    )
                assertFalse(
                    runBlocking { requireNotNull(verdict).await() },
                    "a parameter key with a control character cannot be shown in full, so it is refused whoever asked",
                )
            }
            drainUiThread()
            assertEquals(0, calls.get(), "a malformed parameter key must never reach its handler")
            assertEquals(
                0,
                PluginActionEventBus.pendingCount,
                "a malformed parameter key must not occupy a confirmation slot",
            )
        } finally {
            PluginActionEventBus.clearForTest()
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }
}
