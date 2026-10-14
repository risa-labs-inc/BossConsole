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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `boss://` is registered with the OS, so a `boss://plugin?id=…&action=…` link is
 * not evidence the operator asked for anything: any web page or local program can
 * produce the same input. These tests pin the gate that decides whether such a
 * link reaches a plugin's [DeepLinkActionHandler] unattended.
 */
class PluginActionOriginTest {
    /** Lets anything already queued on the EDT finish before the assertion reads the counter. */
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
    fun `an external action link never reaches the handler unattended`() {
        val id = "external-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        PluginActionEventBus.clearForTest()
        try {
            val verdict = DeepLinkHandler.processDeepLink("boss://plugin?id=$id&action=ping", DeepLinkOrigin.EXTERNAL)

            // No window is registered in a test JVM, which is also what a cold start
            // looks like: the argv link is processed before `application {}` builds
            // one. The action is neither run nor refused - it is retained until a
            // window can ask about it - so the verdict is null, the same "queued"
            // answer a held action gives when a window did exist.
            assertNull(verdict, "a held action has no outcome yet, so it reports queued rather than a verdict")
            drainUiThread()
            assertEquals(0, calls.get(), "the handler must not run for an externally delivered link")
            assertEquals(
                1,
                PluginActionEventBus.pendingCount,
                "the action must be waiting for a window rather than discarded",
            )
        } finally {
            PluginActionEventBus.clearForTest()
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `an operator initiated action link still dispatches and reports its verdict`() {
        val id = "operator-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        try {
            val handled =
                runBlocking {
                    requireNotNull(
                        DeepLinkHandler.processDeepLink(
                            "boss://plugin?id=$id&action=ping",
                            DeepLinkOrigin.OPERATOR_CLI,
                        ),
                    ).await()
                }
            assertTrue(handled, "the operator's own invocation must still run the action and report the outcome")
            assertEquals(1, calls.get())

            val declined =
                runBlocking {
                    requireNotNull(
                        DeepLinkHandler.processDeepLink(
                            "boss://plugin?id=$id&action=unknown",
                            DeepLinkOrigin.OPERATOR_CLI,
                        ),
                    ).await()
                }
            assertFalse(declined, "a declining handler is still reported honestly for the operator's own link")
        } finally {
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }

    @Test
    fun `a malformed action is refused for every origin, not held for confirmation`() {
        val id = "malformed-action-test-${System.nanoTime()}"
        val calls = AtomicInteger()
        registerCounting(id, calls)
        try {
            for (origin in DeepLinkOrigin.entries) {
                val verdict = DeepLinkHandler.processDeepLink("boss://plugin?id=$id&action=ping%0Arm%20-rf", origin)
                assertFalse(
                    runBlocking { requireNotNull(verdict).await() },
                    "an action carrying a control character cannot be shown in full, so it is refused outright",
                )
            }
            drainUiThread()
            assertEquals(0, calls.get())
        } finally {
            DeepLinkActionRegistryImpl.unregister(id)
        }
    }
}
