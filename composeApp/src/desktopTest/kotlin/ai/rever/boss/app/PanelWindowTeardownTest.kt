package ai.rever.boss.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** Guards the caller ordering that store-only lifecycle tests cannot exercise. */
class PanelWindowTeardownTest {
    @Test
    fun `panels are disposed in the plugin teardown callback before classloaders close`() {
        val source = File("src/commonMain/kotlin/ai/rever/boss/app/BossAppStartupEffects.kt").readText()
        // An earlier sibling DisposableEffect is insufficient: Compose forgets siblings
        // in reverse order. Require the store cleanup in the plugin's own onDispose.
        val beforePluginDisposal = source.substringBefore("plugin.dispose()")
        val disposalCallback = beforePluginDisposal.substringAfterLast("onDispose {")
        assertContains(disposalCallback, "state.panelComponentStore.dispose()")
    }

    @Test
    fun `store registration belongs to its own effect rather than plugin recreation`() {
        val source = File("src/commonMain/kotlin/ai/rever/boss/app/BossAppStartupEffects.kt").readText()
        val storeEffect =
            source
                .substringAfter("DisposableEffect(state.panelComponentStore, windowId)")
                .substringBefore("// App-level window lifecycle")
        assertContains(storeEffect, "PanelComponentStoreRegistry.register(windowId, state.panelComponentStore)")
        assertContains(storeEffect, "PanelComponentStoreRegistry.unregister(windowId)")
        val pluginEffect = source.substringAfter("// DefaultPlugin lifecycle:")
        assertFalse(pluginEffect.contains("PanelComponentStoreRegistry.unregister(windowId)"))
    }
}
