package ai.rever.boss.plugin.sandbox.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginIncompatibilityObservationTest {
    @Test
    fun `runtime incompatibility and recovery publish without a manager state change`() =
        runBlocking {
            val id = "health-observation-test"
            PluginCrashRegistry.clearIncompatible(id)
            val observed = mutableListOf<Boolean>()
            val job =
                launch(Dispatchers.Unconfined) {
                    PluginCrashRegistry.incompatiblePlugins
                        .map { id in it }
                        .distinctUntilChanged()
                        .collect { observed.add(it) }
                }
            try {
                PluginCrashRegistry.markIncompatible(id)
                PluginCrashRegistry.clearIncompatible(id)
                assertEquals(listOf(false, true, false), observed)
            } finally {
                job.cancel()
                PluginCrashRegistry.clearIncompatible(id)
            }
        }
}
