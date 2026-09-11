package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthSnapshot
import ai.rever.boss.components.plugin.PluginHealthStatus
import ai.rever.boss.mcp.McpKillSwitchFault
import ai.rever.boss.mcp.McpPolicyFault

/** Where a health finding comes from. [wireName] is the value `boss status --json` sends. */
internal enum class HealthArea(
    val wireName: String,
) {
    PLUGINS("plugins"),
    BROWSER("browser"),
    MCP("mcp"),
}

/** How much of the workspace a finding takes away. Declared most severe first. */
internal enum class HealthSeverity(
    val wireName: String,
) {
    /**
     * Something that should work is broken and a whole capability is gone: a browser engine that
     * failed, or every MCP tool.
     */
    CRITICAL("critical"),

    /** Something narrower: one plugin, one setting that was not saved, or an engine not downloaded yet. */
    WARNING("warning"),
}

/** Stable identifiers scripts can match on. Summaries are for people and may be reworded. */
internal object HealthCodes {
    const val PLUGIN_STOPPED = "plugin_stopped_after_failures"
    const val PLUGIN_NEEDS_ATTENTION = "plugin_needs_attention"
    const val BROWSER_ENGINE_NOT_INSTALLED = "browser_engine_not_installed"
    const val BROWSER_ENGINE_UNAVAILABLE = "browser_engine_unavailable"
    const val BROWSER_ENGINE_UNRESPONSIVE = "browser_engine_unresponsive"
    const val MCP_TOOLS_WITHHELD = "mcp_tools_withheld"
    const val MCP_TOOL_SETTING_NOT_SAVED = "mcp_tool_setting_not_saved"
    const val MCP_POLICY_UNREADABLE = "mcp_policy_unreadable"
    const val MCP_POLICY_NOT_SAVED = "mcp_policy_not_saved"
}

/** One problem the workspace is known to have, with a suggested next step when there is one. */
internal data class HealthFinding(
    val area: HealthArea,
    val severity: HealthSeverity,
    val code: String,
    val summary: String,
    val subject: String? = null,
    val remedy: String? = null,
)

/**
 * The workspace health report. Only [findings] make the workspace degraded; [unchecked] areas could
 * not be read, so they are reported as neither healthy nor degraded.
 */
internal data class WorkspaceHealthReport(
    val findings: List<HealthFinding>,
    val unchecked: Set<HealthArea> = emptySet(),
) {
    val degraded: Boolean get() = findings.isNotEmpty()
}

internal sealed interface BrowserEngineHealth {
    data object Healthy : BrowserEngineHealth

    /** No usable engine is installed yet. [reason] is the engine's own advice on what to do. */
    data class NotInstalled(
        val reason: String,
    ) : BrowserEngineHealth

    /** The engine could not start. [reason] is the engine's own operator-facing explanation. */
    data class Unavailable(
        val reason: String,
    ) : BrowserEngineHealth

    /** The engine stopped creating browsers and the wedge detector has spent its recycle budget. */
    data object Unresponsive : BrowserEngineHealth
}

/** The MCP fault states the bottom status bar shows. */
internal data class McpFaults(
    val killSwitch: McpKillSwitchFault?,
    val policy: McpPolicyFault?,
)

/** What a report is built from. A null source could not be read and is reported as unchecked. */
internal data class WorkspaceHealthInputs(
    val plugins: List<PluginHealthSnapshot>?,
    val browser: BrowserEngineHealth?,
    val mcp: McpFaults?,
)

private val findingOrder: Comparator<HealthFinding> =
    compareBy<HealthFinding>({ it.severity.ordinal }, { it.area.ordinal }, { it.subject.orEmpty() }, { it.code })

/**
 * Turn state BOSS already tracks into findings. Pure: no clock, no I/O, no side effects.
 *
 * Only problems count. A plugin the user disabled, or one the signed-in user may not access, is
 * listed as unavailable by the Plugin Health & Recovery center, but it is not a degradation here.
 */
internal fun workspaceHealthReport(inputs: WorkspaceHealthInputs): WorkspaceHealthReport {
    val findings =
        buildList {
            inputs.plugins?.let { addAll(pluginFindings(it)) }
            inputs.browser?.let { health -> browserFinding(health)?.let { add(it) } }
            inputs.mcp?.killSwitch?.let { add(killSwitchFinding(it)) }
            inputs.mcp?.policy?.let { add(policyFinding(it)) }
        }
    val unchecked =
        buildSet {
            if (inputs.plugins == null) add(HealthArea.PLUGINS)
            if (inputs.browser == null) add(HealthArea.BROWSER)
            if (inputs.mcp == null) add(HealthArea.MCP)
        }
    return WorkspaceHealthReport(findings.sortedWith(findingOrder), unchecked)
}

/**
 * A plugin the sandbox watchdog stopped is reported as that, once, however many windows stopped
 * it. Other plugins needing attention are reported with the health center's own detail.
 */
private fun pluginFindings(snapshots: List<PluginHealthSnapshot>): List<HealthFinding> {
    val rows = snapshots.flatMap { it.rows }
    val names = rows.associate { it.pluginId to it.displayName }
    val stopped = snapshots.flatMapTo(sortedSetOf<String>()) { it.sandboxDisabledPluginIds }
    val stoppedFindings =
        stopped.map { pluginId ->
            HealthFinding(
                area = HealthArea.PLUGINS,
                severity = HealthSeverity.WARNING,
                code = HealthCodes.PLUGIN_STOPPED,
                summary = "Plugin '${names[pluginId] ?: pluginId}' was stopped after repeated failures.",
                subject = pluginId,
                remedy = "Reload it from Help > Plugin Health & Recovery in the affected window, or restart BOSS.",
            )
        }
    val attentionFindings =
        rows
            .filter { it.status == PluginHealthStatus.NEEDS_ATTENTION && it.pluginId !in stopped }
            .distinctBy { it.pluginId to it.detail }
            .map { row ->
                HealthFinding(
                    area = HealthArea.PLUGINS,
                    severity = HealthSeverity.WARNING,
                    code = HealthCodes.PLUGIN_NEEDS_ATTENTION,
                    summary = "Plugin '${row.displayName}': ${row.detail}",
                    subject = row.pluginId,
                    remedy = "Open Help > Plugin Health & Recovery for this plugin's recovery options.",
                )
            }
    return stoppedFindings + attentionFindings
}

private fun browserFinding(health: BrowserEngineHealth): HealthFinding? =
    when (health) {
        BrowserEngineHealth.Healthy -> {
            null
        }

        is BrowserEngineHealth.NotInstalled -> {
            HealthFinding(
                area = HealthArea.BROWSER,
                severity = HealthSeverity.WARNING,
                code = HealthCodes.BROWSER_ENGINE_NOT_INSTALLED,
                summary = "No usable browser engine is installed, so browser tabs and browser tools cannot run.",
                remedy = health.reason,
            )
        }

        is BrowserEngineHealth.Unavailable -> {
            HealthFinding(
                area = HealthArea.BROWSER,
                severity = HealthSeverity.CRITICAL,
                code = HealthCodes.BROWSER_ENGINE_UNAVAILABLE,
                summary = "The browser engine could not start: ${health.reason}",
                remedy = "Resolve the cause above, then use Retry in a browser tab or restart BOSS.",
            )
        }

        BrowserEngineHealth.Unresponsive -> {
            HealthFinding(
                area = HealthArea.BROWSER,
                severity = HealthSeverity.CRITICAL,
                code = HealthCodes.BROWSER_ENGINE_UNRESPONSIVE,
                summary = "The browser engine stopped creating browsers and automatic recovery has stopped retrying.",
                remedy = "Restart BOSS.",
            )
        }
    }

/**
 * The fault's own message already names the file and the recovery, so it is the summary. Only a
 * disabled-tools file that cannot be read withholds every tool. Any other kill-switch fault is
 * reported as a setting that could not be saved, deliberately without listing the fault types, so a
 * type added later is still reported rather than breaking this mapping.
 */
private fun killSwitchFinding(fault: McpKillSwitchFault): HealthFinding =
    if (fault is McpKillSwitchFault.PersistedSetUnreadable) {
        HealthFinding(HealthArea.MCP, HealthSeverity.CRITICAL, HealthCodes.MCP_TOOLS_WITHHELD, fault.message)
    } else {
        HealthFinding(
            area = HealthArea.MCP,
            severity = HealthSeverity.WARNING,
            code = HealthCodes.MCP_TOOL_SETTING_NOT_SAVED,
            summary = fault.message,
            subject = (fault as? McpKillSwitchFault.TogglePersistFailed)?.toolName,
        )
    }

/** As [killSwitchFinding]: only a policy file that cannot be read withholds every tool. */
private fun policyFinding(fault: McpPolicyFault): HealthFinding =
    if (fault is McpPolicyFault.PersistedPolicyUnreadable) {
        HealthFinding(HealthArea.MCP, HealthSeverity.CRITICAL, HealthCodes.MCP_POLICY_UNREADABLE, fault.message)
    } else {
        HealthFinding(
            area = HealthArea.MCP,
            severity = HealthSeverity.WARNING,
            code = HealthCodes.MCP_POLICY_NOT_SAVED,
            summary = fault.message,
            subject = (fault as? McpPolicyFault.PolicyPersistFailed)?.toolName,
        )
    }
