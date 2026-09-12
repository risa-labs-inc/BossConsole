package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthRow
import ai.rever.boss.components.plugin.PluginHealthSnapshot
import ai.rever.boss.components.plugin.PluginHealthStatus
import ai.rever.boss.mcp.McpKillSwitchFault
import ai.rever.boss.mcp.McpPolicyFault
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceHealthReportTest {
    @Test
    fun `a workspace with nothing wrong is not degraded`() {
        val report = workspaceHealthReport(inputs(plugins = listOf(snapshot(row("notes", PluginHealthStatus.HEALTHY)))))

        assertFalse(report.degraded)
        assertTrue(report.findings.isEmpty())
        assertTrue(report.unchecked.isEmpty())
    }

    @Test
    fun `a plugin stopped by the sandbox watchdog is one warning naming the plugin`() {
        val stoppedRow =
            row("terminaltab", PluginHealthStatus.NEEDS_ATTENTION, name = "Terminal Tab", detail = STOPPED)

        val report =
            workspaceHealthReport(inputs(plugins = listOf(snapshot(stoppedRow, stopped = setOf("terminaltab")))))

        val finding = report.findings.single()
        assertEquals(HealthCodes.PLUGIN_STOPPED, finding.code)
        assertEquals(HealthArea.PLUGINS, finding.area)
        assertEquals(HealthSeverity.WARNING, finding.severity)
        assertEquals("terminaltab", finding.subject)
        assertEquals("Plugin 'Terminal Tab' was stopped after repeated failures.", finding.summary)
        assertTrue(report.degraded)
    }

    @Test
    fun `a plugin needing attention carries the health center's own detail`() {
        val gated =
            row(
                "notes",
                PluginHealthStatus.NEEDS_ATTENTION,
                name = "Notes",
                detail = "This plugin requires a newer BOSS version.",
            )

        val finding = workspaceHealthReport(inputs(plugins = listOf(snapshot(gated)))).findings.single()

        assertEquals(HealthCodes.PLUGIN_NEEDS_ATTENTION, finding.code)
        assertEquals("Plugin 'Notes': This plugin requires a newer BOSS version.", finding.summary)
        assertEquals("notes", finding.subject)
    }

    @Test
    fun `plugins unavailable by choice or by access are not degradations`() {
        val disabled = row("notes", PluginHealthStatus.UNAVAILABLE, detail = "The plugin is disabled.")
        val restricted = row("admin", PluginHealthStatus.UNAVAILABLE, detail = "Access is required for this plugin.")

        val report = workspaceHealthReport(inputs(plugins = listOf(snapshot(disabled, restricted))))

        assertFalse(report.degraded)
    }

    @Test
    fun `the same problem in two windows is reported once`() {
        val crashedRow = row("notes", PluginHealthStatus.NEEDS_ATTENTION, detail = CRASHED)
        val stoppedRow = row("tasks", PluginHealthStatus.NEEDS_ATTENTION, detail = STOPPED)

        val report =
            workspaceHealthReport(
                inputs(
                    plugins =
                        listOf(
                            snapshot(crashedRow, stoppedRow, stopped = setOf("tasks")),
                            snapshot(crashedRow, stoppedRow, stopped = setOf("tasks")),
                        ),
                ),
            )

        assertEquals(
            listOf(HealthCodes.PLUGIN_NEEDS_ATTENTION to "notes", HealthCodes.PLUGIN_STOPPED to "tasks"),
            report.findings.map { it.code to it.subject }.sortedBy { it.second },
        )
    }

    @Test
    fun `a browser engine that is not installed yet is a warning that passes on the engine's advice`() {
        val advice = "BOSS-branded Chromium not found. Please restart the app to trigger auto-download."

        val finding =
            workspaceHealthReport(inputs(browser = BrowserEngineHealth.NotInstalled(advice))).findings.single()

        assertEquals(HealthCodes.BROWSER_ENGINE_NOT_INSTALLED, finding.code)
        assertEquals(HealthSeverity.WARNING, finding.severity)
        assertEquals(advice, finding.remedy)
    }

    @Test
    fun `a browser engine that could not start is critical and carries the engine's reason`() {
        val reason = "License validation failed. Please check your internet connection."

        val finding =
            workspaceHealthReport(inputs(browser = BrowserEngineHealth.Unavailable(reason))).findings.single()

        assertEquals(HealthCodes.BROWSER_ENGINE_UNAVAILABLE, finding.code)
        assertEquals(HealthSeverity.CRITICAL, finding.severity)
        assertTrue(finding.summary.endsWith(reason), finding.summary)
        assertNull(finding.subject)
    }

    @Test
    fun `a browser engine whose automatic recovery gave up is critical`() {
        val finding = workspaceHealthReport(inputs(browser = BrowserEngineHealth.Unresponsive)).findings.single()

        assertEquals(HealthCodes.BROWSER_ENGINE_UNRESPONSIVE, finding.code)
        assertEquals(HealthSeverity.CRITICAL, finding.severity)
    }

    @Test
    fun `an unreadable kill-switch file withholds every tool and is critical`() {
        val fault =
            McpKillSwitchFault.PersistedSetUnreadable("/boss/mcp-disabled-tools.json", null, 3, "unexpected end")

        val finding = mcpFinding(killSwitch = fault)

        assertEquals(HealthCodes.MCP_TOOLS_WITHHELD, finding.code)
        assertEquals(HealthSeverity.CRITICAL, finding.severity)
        assertEquals(fault.message, finding.summary)
    }

    @Test
    fun `a kill-switch toggle that could not be saved is a warning about that tool`() {
        val fault =
            McpKillSwitchFault.TogglePersistFailed(
                toolName = "run_command",
                enabled = true,
                outcome = McpKillSwitchFault.ToggleOutcome.REFUSED,
                error = "disk full",
            )

        val finding = mcpFinding(killSwitch = fault)

        assertEquals(HealthCodes.MCP_TOOL_SETTING_NOT_SAVED, finding.code)
        assertEquals(HealthSeverity.WARNING, finding.severity)
        assertEquals("run_command", finding.subject)
        assertEquals(fault.message, finding.summary)
    }

    @Test
    fun `policy faults are critical only when the policy file cannot be read`() {
        val unreadable = McpPolicyFault.PersistedPolicyUnreadable("/boss/mcp-tool-policy.json", "unexpected end")
        val notSaved = McpPolicyFault.PolicyPersistFailed("run_command", "disk full")

        val unreadableFinding = mcpFinding(policy = unreadable)
        val notSavedFinding = mcpFinding(policy = notSaved)

        assertEquals(HealthCodes.MCP_POLICY_UNREADABLE, unreadableFinding.code)
        assertEquals(HealthSeverity.CRITICAL, unreadableFinding.severity)
        assertEquals(HealthCodes.MCP_POLICY_NOT_SAVED, notSavedFinding.code)
        assertEquals(HealthSeverity.WARNING, notSavedFinding.severity)
        assertEquals("run_command", notSavedFinding.subject)
    }

    @Test
    fun `sources that could not be read are unchecked, never healthy`() {
        val report = workspaceHealthReport(WorkspaceHealthInputs(plugins = null, browser = null, mcp = null))

        assertEquals(HealthArea.entries.toSet(), report.unchecked)
        assertFalse(report.degraded)
    }

    @Test
    fun `critical findings are listed before warnings`() {
        val stoppedRow = row("tasks", PluginHealthStatus.NEEDS_ATTENTION, detail = STOPPED)

        val report =
            workspaceHealthReport(
                inputs(
                    plugins = listOf(snapshot(stoppedRow, stopped = setOf("tasks"))),
                    browser = BrowserEngineHealth.Unresponsive,
                ),
            )

        assertEquals(listOf(HealthSeverity.CRITICAL, HealthSeverity.WARNING), report.findings.map { it.severity })
    }

    @Test
    fun `json uses lowercase wire names and omits absent fields`() {
        val report =
            WorkspaceHealthReport(
                findings =
                    listOf(HealthFinding(HealthArea.MCP, HealthSeverity.CRITICAL, "mcp_tools_withheld", "Withheld.")),
                unchecked = setOf(HealthArea.BROWSER),
            )

        val json = report.toJson()

        assertTrue(json.getValue("degraded").jsonPrimitive.boolean)
        val finding = (json.getValue("findings") as JsonArray).single().jsonObject
        assertEquals("mcp", finding.getValue("area").jsonPrimitive.content)
        assertEquals("critical", finding.getValue("severity").jsonPrimitive.content)
        assertEquals("mcp_tools_withheld", finding.getValue("code").jsonPrimitive.content)
        assertFalse(finding.containsKey("subject"))
        assertFalse(finding.containsKey("remedy"))
        assertEquals(listOf("browser"), (json.getValue("unchecked") as JsonArray).map { it.jsonPrimitive.content })
    }

    private fun inputs(
        plugins: List<PluginHealthSnapshot>? = emptyList(),
        browser: BrowserEngineHealth? = BrowserEngineHealth.Healthy,
        mcp: McpFaults? = McpFaults(killSwitch = null, policy = null),
    ) = WorkspaceHealthInputs(plugins, browser, mcp)

    private fun mcpFinding(
        killSwitch: McpKillSwitchFault? = null,
        policy: McpPolicyFault? = null,
    ): HealthFinding = workspaceHealthReport(inputs(mcp = McpFaults(killSwitch, policy))).findings.single()

    private fun snapshot(
        vararg rows: PluginHealthRow,
        stopped: Set<String> = emptySet(),
    ) = PluginHealthSnapshot(rows.toList(), stopped)

    private fun row(
        id: String,
        status: PluginHealthStatus,
        name: String = id,
        detail: String = "Running normally.",
    ) = PluginHealthRow(id, name, status, detail)

    private companion object {
        const val STOPPED = "The plugin stopped and needs recovery."
        const val CRASHED = "The plugin needs recovery after an unexpected failure."
    }
}
