package ai.rever.boss.plugin.sandbox.context

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.InProcessPluginSandbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SandboxedPluginContextApiTest {
    private open class PlainContext : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private class RecordingContext :
        PlainContext(),
        PluginApiRegistryLifecycle {
        var removed: Any? = null

        override fun unregisterPluginAPI(api: Any) {
            removed = api
        }
    }

    private fun sandboxedContext(
        sandbox: InProcessPluginSandbox,
        host: PluginContext,
    ) = SandboxedPluginContext(
        _sandbox = sandbox,
        delegate = host,
        sandboxedPanelRegistry = SandboxedPanelRegistry(sandbox, host.panelRegistry),
        sandboxedTabRegistry = SandboxedTabRegistry(sandbox, host.tabRegistry),
    )

    @Test
    fun `unregister forwards the exact API instance to the host lifecycle`() =
        runBlocking<Unit> {
            val sandbox = InProcessPluginSandbox("test.plugin")
            try {
                val host = RecordingContext()
                val context = sandboxedContext(sandbox, host)
                val api = Any()

                context.unregisterPluginAPI(api)

                assertSame(api, host.removed)
            } finally {
                sandbox.stop()
            }
        }

    @Test
    fun `unregister fails visibly when the host delegate lacks lifecycle support`() =
        runBlocking<Unit> {
            val sandbox = InProcessPluginSandbox("test.plugin")
            try {
                val host = PlainContext()
                val context = sandboxedContext(sandbox, host)

                val error = assertFailsWith<IllegalStateException> { context.unregisterPluginAPI(Any()) }

                assertTrue(error.message.orEmpty().contains("PluginApiRegistryLifecycle"))
            } finally {
                sandbox.stop()
            }
        }
}
