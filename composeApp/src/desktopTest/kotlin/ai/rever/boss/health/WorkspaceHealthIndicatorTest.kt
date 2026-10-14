package ai.rever.boss.health

import ai.rever.boss.components.settings.sidebar.SettingsSection
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceHealthIndicatorTest {
    @Test
    fun `a healthy workspace shows nothing in the status bar`() {
        val report = WorkspaceHealthReport(findings = emptyList())

        assertEquals(HealthIndicatorLevel.HEALTHY, report.indicatorLevel)
        assertFalse(report.showsStatusItem)
        assertEquals("No problems found", report.headline())
    }

    @Test
    fun `an unchecked area is not called healthy, but does not badge the status bar either`() {
        // Plugins are unchecked for a moment at every launch, before the window's source
        // registers, so a badge here would flash on start-up.
        val report = WorkspaceHealthReport(findings = emptyList(), unchecked = setOf(HealthArea.PLUGINS))

        assertEquals(HealthIndicatorLevel.INCOMPLETE, report.indicatorLevel)
        assertFalse(report.showsStatusItem)
        assertEquals("No problems found, but not every area could be checked", report.headline())
    }

    @Test
    fun `any finding badges the status bar with a count`() {
        val one = WorkspaceHealthReport(findings = listOf(finding(HealthSeverity.WARNING)))
        val two = WorkspaceHealthReport(findings = List(2) { finding(HealthSeverity.WARNING) })

        assertTrue(one.showsStatusItem)
        assertEquals("1 issue", one.statusText())
        assertEquals("2 issues", two.statusText())
        assertEquals(HealthIndicatorLevel.WARNING, two.indicatorLevel)
    }

    @Test
    fun `one critical finding makes the whole report critical and the headline says how many`() {
        val report =
            WorkspaceHealthReport(findings = listOf(finding(HealthSeverity.WARNING), finding(HealthSeverity.CRITICAL)))

        assertEquals(HealthIndicatorLevel.CRITICAL, report.indicatorLevel)
        assertEquals("2 issues, 1 critical", report.headline())
        assertEquals("Workspace health: 2 issues, 1 critical. Click for details.", report.statusDescription())
    }

    @Test
    fun `findings outrank an incomplete read`() {
        val report =
            WorkspaceHealthReport(
                findings = listOf(finding(HealthSeverity.WARNING)),
                unchecked = setOf(HealthArea.BROWSER),
            )

        assertEquals(HealthIndicatorLevel.WARNING, report.indicatorLevel)
    }

    @Test
    fun `every area the report does not fully cover gets its own note, in area order`() {
        val report =
            WorkspaceHealthReport(
                findings = emptyList(),
                unchecked = setOf(HealthArea.MCP),
                partial = setOf(HealthArea.PLUGINS),
            )

        val notes = report.coverageNotes()

        assertEquals(2, notes.size)
        assertTrue(notes[0].startsWith("Plugins was only partly checked"), notes[0])
        assertTrue(notes[1].startsWith("MCP tools could not be checked"), notes[1])
        assertTrue(WorkspaceHealthReport(findings = emptyList()).coverageNotes().isEmpty())
    }

    @Test
    fun `only findings with a screen that fixes them offer a fix`() {
        val expected =
            mapOf(
                HealthCodes.PLUGIN_STOPPED to HealthFix.OPEN_PLUGIN_HEALTH,
                HealthCodes.PLUGIN_NEEDS_ATTENTION to HealthFix.OPEN_PLUGIN_HEALTH,
                HealthCodes.BROWSER_ENGINE_NOT_INSTALLED to HealthFix.OPEN_BROWSER_ENGINE_SETTINGS,
                HealthCodes.BROWSER_ENGINE_UNAVAILABLE to null,
                HealthCodes.BROWSER_ENGINE_UNRESPONSIVE to null,
                HealthCodes.MCP_TOOLS_WITHHELD to null,
                HealthCodes.MCP_TOOL_SETTING_NOT_SAVED to null,
                HealthCodes.MCP_POLICY_UNREADABLE to null,
                HealthCodes.MCP_POLICY_NOT_SAVED to null,
            )

        expected.forEach { (code, fix) ->
            assertEquals(fix, finding(HealthSeverity.WARNING, code).fix, code)
        }
        // A code added to HealthCodes later must be decided here, not silently left without a fix.
        assertEquals(allHealthCodes(), expected.keys)
    }

    @Test
    fun `the browser engine fix names a Settings section that exists`() {
        // triggerOpenSettings takes the section as a string, so a renamed enum entry would open
        // Settings on its default page and nothing would fail.
        assertEquals(SettingsSection.BROWSER_ENGINE, SettingsSection.valueOf(BROWSER_ENGINE_SETTINGS_SECTION))
    }

    private fun finding(
        severity: HealthSeverity,
        code: String = HealthCodes.PLUGIN_STOPPED,
    ) = HealthFinding(area = HealthArea.PLUGINS, severity = severity, code = code, summary = "summary")

    private fun allHealthCodes(): Set<String> =
        HealthCodes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
}
