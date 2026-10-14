package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.RemoteViewProvider
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.ui.menu.RemoteViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingPluginContextRemoteViewTest {
    private class Delegate : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override val remoteViewProvider: RemoteViewProvider =
            object : RemoteViewProvider {
                override fun beginRemoteView(reason: String): AutoCloseable = RemoteViewState.begin()
            }
    }

    private fun context() = TrackingPluginContext("remote-view-test", Delegate(), PluginRegistrationTracker())

    @Test
    fun `unload closes views a plugin left open`() {
        val base = RemoteViewState.count.value
        val ctx = context()
        ctx.remoteViewProvider!!.beginRemoteView("share")
        assertEquals(base + 1, RemoteViewState.count.value)
        ctx.unregisterAll()
        assertEquals(base, RemoteViewState.count.value)
    }

    @Test
    fun `a view begun after unload never raises the count`() {
        val base = RemoteViewState.count.value
        val ctx = context()
        ctx.unregisterAll()
        val late = ctx.remoteViewProvider!!.beginRemoteView("late")
        try {
            assertEquals(base, RemoteViewState.count.value)
        } finally {
            late.close()
        }
        assertEquals(base, RemoteViewState.count.value)
    }

    @Test
    fun `log reasons are stripped of control characters and capped`() {
        assertEquals("ab", sanitizeRemoteViewReason("a\nb"))
        assertEquals(80, sanitizeRemoteViewReason("x".repeat(500)).length)
    }
}
