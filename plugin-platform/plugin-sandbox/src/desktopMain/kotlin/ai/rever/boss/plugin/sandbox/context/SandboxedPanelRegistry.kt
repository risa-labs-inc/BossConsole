package ai.rever.boss.plugin.sandbox.context

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.sandbox.PanelSandboxRegistry
import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import com.arkivanov.decompose.ComponentContext

/**
 * A PanelRegistry wrapper that wraps panel component factories with error boundaries.
 *
 * When a panel is registered through this registry, its factory is wrapped to catch
 * exceptions and record them in the sandbox's health metrics.
 */
class SandboxedPanelRegistry(
    private val sandbox: PluginSandbox,
    private val delegate: PanelRegistry
) : PanelRegistry() {

    private val logger = BossLogger.forComponent("SandboxedPanelRegistry")

    override fun registerPanel(
        content: PanelInfo,
        factory: (ComponentContext, PanelInfo) -> PanelComponentWithUI
    ) {
        logger.debug(LogCategory.SYSTEM, "Registering sandboxed panel", mapOf(
            "panelId" to content.id.panelId,
            "pluginId" to sandbox.pluginId
        ))

        // Register panel-to-sandbox mapping for error boundary integration
        PanelSandboxRegistry.register(content.id, sandbox)

        // Wrap the factory with error handling
        val wrappedFactory: (ComponentContext, PanelInfo) -> PanelComponentWithUI = { ctx, info ->
            try {
                sandbox.recordHeartbeat()
                val component = factory(ctx, info)
                sandbox.recordSuccess()
                component
            } catch (e: Throwable) {
                logger.error(LogCategory.SYSTEM, "Error creating panel component", mapOf(
                    "panelId" to content.id.panelId,
                    "pluginId" to sandbox.pluginId
                ), e)
                sandbox.recordError(e)
                throw e
            }
        }

        delegate.registerPanel(content, wrappedFactory)
    }

    override fun unregisterPanel(id: PanelId) {
        logger.debug(LogCategory.SYSTEM, "Unregistering sandboxed panel", mapOf(
            "panelId" to id.panelId,
            "pluginId" to sandbox.pluginId
        ))

        // Remove panel-to-sandbox mapping
        PanelSandboxRegistry.unregister(id)

        delegate.unregisterPanel(id)
    }

    override fun createComponent(id: PanelId, componentContext: ComponentContext): PanelComponentWithUI? {
        return delegate.createComponent(id, componentContext)
    }

    override fun getPanelContent(id: PanelId): PanelInfo? {
        return delegate.getPanelContent(id)
    }

    override fun getAllPanels(): List<PanelInfo> {
        return delegate.getAllPanels()
    }

    override fun addChangeListener(listener: () -> Unit) {
        delegate.addChangeListener(listener)
    }

    override fun removeChangeListener(listener: () -> Unit) {
        delegate.removeChangeListener(listener)
    }
}
