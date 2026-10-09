package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DefaultPluginApiRegistryTest {
    private interface EqualApi

    private data class EqualApiImpl(
        val value: String,
    ) : EqualApi

    private fun context() =
        DefaultPlugin(
            PanelRegistry(),
            TabRegistry(),
            windowProjectState = null,
        )

    @Test
    fun `teardown cannot remove an equal API now owned by another plugin`() =
        runBlocking<Unit> {
            val context = context()
            try {
                val baselineVersion = context.apiRegistryVersion.value
                val first = EqualApiImpl("same")
                val replacement = EqualApiImpl("same")
                context.registerPluginAPI(first)
                context.registerPluginAPI(replacement)

                context.unregisterPluginAPI(first)

                assertSame(replacement, context.getPluginAPI(EqualApi::class.java))
                assertSame(replacement, context.getPluginAPI(EqualApiImpl::class.java))
                assertEquals(
                    baselineVersion + 2,
                    context.apiRegistryVersion.value,
                    "a no-op teardown must not signal a registry change",
                )
            } finally {
                context.dispose().join()
            }
        }

    @Test
    fun `concurrent registry changes never lose a version increment`() =
        runBlocking<Unit> {
            val context = context()
            try {
                val baselineVersion = context.apiRegistryVersion.value
                val registrations = 1_000
                val jobs =
                    (1..registrations)
                        .map { value ->
                            async(Dispatchers.Default) {
                                context.registerPluginAPI(EqualApiImpl(value.toString()))
                            }
                        }
                jobs.awaitAll()

                assertEquals(baselineVersion + registrations, context.apiRegistryVersion.value)
            } finally {
                context.dispose().join()
            }
        }
}
