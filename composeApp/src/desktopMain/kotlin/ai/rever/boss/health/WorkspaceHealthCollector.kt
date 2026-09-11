package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthSnapshot
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.browser.EngineInitError
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Reads every health source for `boss status` and `boss doctor`.
 *
 * Read-only by construction: each source is a value another part of BOSS already maintains. The
 * sources are parameters so tests can replace them without starting a browser engine.
 */
internal class WorkspaceHealthCollector(
    private val pluginSnapshots: () -> List<PluginHealthSnapshot> = WorkspaceHealthSources::pluginSnapshots,
    private val browserHealth: () -> BrowserEngineHealth = ::currentBrowserEngineHealth,
    private val mcpFaults: () -> McpFaults = ::currentMcpFaults,
) {
    /**
     * Never throws. A source that cannot be read is reported as unchecked, never as healthy. With no
     * window open there is no plugin manager to read, so plugins are unchecked as well.
     */
    fun collect(): WorkspaceHealthReport =
        workspaceHealthReport(
            WorkspaceHealthInputs(
                plugins = read(HealthArea.PLUGINS, pluginSnapshots)?.takeIf { it.isNotEmpty() },
                browser = read(HealthArea.BROWSER, browserHealth),
                mcp = read(HealthArea.MCP, mcpFaults),
            ),
        )

    // A status query must still answer when one health source is broken, so each source is
    // contained here. LinkageError is caught alongside Exception because a class missing from
    // this path must not take `boss status` down with it; VirtualMachineError is left alone.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> read(
        area: HealthArea,
        source: () -> T,
    ): T? =
        try {
            source()
        } catch (e: Exception) {
            unreadable(area, e)
        } catch (e: LinkageError) {
            unreadable(area, e)
        }

    private fun unreadable(
        area: HealthArea,
        error: Throwable,
    ): Nothing? {
        logger.warn(
            LogCategory.SYSTEM,
            "Workspace health source could not be read",
            mapOf("area" to area.wireName, "error" to (error.message ?: error::class.simpleName)),
        )
        return null
    }

    private companion object {
        val logger = BossLogger.forComponent("WorkspaceHealth")
    }
}

/**
 * The browser engine as the health report describes it.
 *
 * Whether an engine is installed is asked first, with the same check start-up uses before it offers
 * the download, because a start-up error recorded without an engine only restates that. This is
 * deliberately not `FluckEngine.isEngineHealthy()`, which is also false for an engine that is merely
 * closed and will be recreated on next use.
 */
internal fun currentBrowserEngineHealth(): BrowserEngineHealth {
    if (FluckEngine.resolveEngineDir() == null) {
        return BrowserEngineHealth.NotInstalled(FluckEngine.noUsableEngineReason())
    }
    val initError = FluckEngine.initError
    return when {
        initError != null -> BrowserEngineHealth.Unavailable(initError.reason)
        FluckEngine.isWedgeUnrecoverable -> BrowserEngineHealth.Unresponsive
        else -> BrowserEngineHealth.Healthy
    }
}

internal fun currentMcpFaults(): McpFaults =
    McpFaults(
        killSwitch = McpToolRegistryImpl.killSwitchFault.value,
        policy = McpToolRegistryImpl.policyFault.value,
    )

private val EngineInitError.reason: String
    get() =
        when (this) {
            is EngineInitError.LicenseValidation -> message
            is EngineInitError.NetworkError -> message
            is EngineInitError.Other -> message
        }
