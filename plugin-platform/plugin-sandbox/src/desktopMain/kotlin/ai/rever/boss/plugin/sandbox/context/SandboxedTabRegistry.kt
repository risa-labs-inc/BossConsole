package ai.rever.boss.plugin.sandbox.context

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.TabSandboxRegistry
import com.arkivanov.decompose.ComponentContext

/**
 * A TabRegistry wrapper that wraps tab component factories with error boundaries.
 *
 * When a tab type is registered through this registry, its factory is wrapped to catch
 * exceptions and record them in the sandbox's health metrics.
 */
class SandboxedTabRegistry(
    private val sandbox: PluginSandbox,
    private val delegate: TabRegistry,
) : TabRegistry() {
    private val logger = BossLogger.forComponent("SandboxedTabRegistry")

    override fun registerTabType(
        content: TabTypeInfo,
        factory: (TabInfo, ComponentContext) -> TabComponentWithUI,
    ) {
        logger.debug(
            LogCategory.SYSTEM,
            "Registering sandboxed tab type",
            mapOf(
                "tabTypeId" to content.typeId.typeId,
                "pluginId" to sandbox.pluginId,
            ),
        )

        // Register tab type-to-sandbox mapping for error boundary integration
        TabSandboxRegistry.register(content.typeId, sandbox)

        // Wrap the factory with error handling
        val wrappedFactory: (TabInfo, ComponentContext) -> TabComponentWithUI = { tabInfo, ctx ->
            try {
                sandbox.recordHeartbeat()
                val component = factory(tabInfo, ctx)
                sandbox.recordSuccess()
                component
            } catch (e: Throwable) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Error creating tab component",
                    mapOf(
                        "tabTypeId" to content.typeId.typeId,
                        "pluginId" to sandbox.pluginId,
                    ),
                    e,
                )
                sandbox.recordError(e)
                throw e
            }
        }

        delegate.registerTabType(content, wrappedFactory)
    }

    override fun unregisterTabType(typeId: ai.rever.boss.plugin.api.TabTypeId) {
        // Remove tab type-to-sandbox mapping
        TabSandboxRegistry.unregister(typeId)

        delegate.unregisterTabType(typeId)
    }

    override fun addUnregisterListener(listener: (ai.rever.boss.plugin.api.TabTypeId) -> Unit) {
        delegate.addUnregisterListener(listener)
    }

    override fun removeUnregisterListener(listener: (ai.rever.boss.plugin.api.TabTypeId) -> Unit) {
        delegate.removeUnregisterListener(listener)
    }

    override fun createTabComponent(
        config: TabInfo,
        componentContext: ComponentContext,
    ): TabComponentWithUI? = delegate.createTabComponent(config, componentContext)
}
