package ai.rever.boss.utils

import ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl
import ai.rever.boss.plugin.api.DeepLinkActionHandler
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards what happens to a `boss://plugin?id=…&action=…` request, which is
 * decided by who asked rather than by what the action says.
 *
 * `boss://` is registered with the OS, so the same link arrives whether the
 * operator typed it themselves or some other program asked the OS to open a
 * URL. A plugin action's effect is whatever its registered handler does with
 * the link's params, so [pluginActionDisposition] refuses everything but the
 * operator's own invocation — these cases are the ones that regress silently.
 */
class PluginActionOriginTest {
    @Test
    fun `an action the operator ran themselves is dispatched`() {
        assertEquals(PluginActionDisposition.RUN, pluginActionDisposition(DeepLinkOrigin.OPERATOR_CLI))
    }

    @Test
    fun `an action from anywhere else is refused, never dispatched`() {
        assertEquals(PluginActionDisposition.REJECT, pluginActionDisposition(DeepLinkOrigin.EXTERNAL))
    }

    @Test
    fun `an external action link never reaches its registered handler`() {
        val handlerId = "test-plugin-action-handler-${System.nanoTime()}"
        val handledActions = mutableListOf<String>()
        DeepLinkActionRegistryImpl.register(recordingHandler(handlerId, handledActions))
        try {
            val verdict =
                runBlocking {
                    requireNotNull(
                        DeepLinkHandler.processDeepLink(
                            "boss://plugin?id=$handlerId&action=sync&scope=all",
                            DeepLinkOrigin.EXTERNAL,
                        ),
                    ).await()
                }
            assertFalse(verdict, "a refused action must not be reported as handled")
            assertTrue(handledActions.isEmpty(), "a refused action must never reach its handler")
        } finally {
            DeepLinkActionRegistryImpl.unregister(handlerId)
        }
    }

    @Test
    fun `an operator-initiated action link still dispatches to its handler`() {
        val handlerId = "test-plugin-action-handler-${System.nanoTime()}"
        val handledActions = mutableListOf<String>()
        DeepLinkActionRegistryImpl.register(recordingHandler(handlerId, handledActions))
        try {
            val verdict =
                runBlocking {
                    requireNotNull(
                        DeepLinkHandler.processDeepLink(
                            "boss://plugin?id=$handlerId&action=sync&scope=all",
                            DeepLinkOrigin.OPERATOR_CLI,
                        ),
                    ).await()
                }
            assertTrue(verdict, "an operator-initiated action must be dispatched to its handler")
            assertEquals(listOf("sync"), handledActions)
        } finally {
            DeepLinkActionRegistryImpl.unregister(handlerId)
        }
    }

    private fun recordingHandler(
        handlerId: String,
        handledActions: MutableList<String>,
    ): DeepLinkActionHandler =
        object : DeepLinkActionHandler {
            override val handlerId = handlerId

            override fun handle(
                action: String,
                params: Map<String, String>,
            ): Boolean {
                handledActions.add(action)
                return true
            }
        }
}
