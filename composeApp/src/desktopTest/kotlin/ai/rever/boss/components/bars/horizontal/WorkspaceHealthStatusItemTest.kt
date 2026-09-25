package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.dialogs.WorkspaceHealthCard
import ai.rever.boss.health.BROWSER_ENGINE_SETTINGS_SECTION
import ai.rever.boss.health.HealthArea
import ai.rever.boss.health.HealthCodes
import ai.rever.boss.health.HealthFinding
import ai.rever.boss.health.HealthFix
import ai.rever.boss.health.HealthSeverity
import ai.rever.boss.health.HealthSourceWarnings
import ai.rever.boss.health.WorkspaceHealthReport
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkspaceHealthStatusItemTest {
    @get:Rule
    val rule = createComposeRule()

    private val stoppedPlugin =
        HealthFinding(
            area = HealthArea.PLUGINS,
            severity = HealthSeverity.WARNING,
            code = HealthCodes.PLUGIN_STOPPED,
            summary = "Plugin 'Notes' was stopped after repeated failures.",
            subject = "notes",
            remedy = "Reload it from Help > Plugin Health & Recovery in the affected window, or restart BOSS.",
        )

    private val unresponsiveEngine =
        HealthFinding(
            area = HealthArea.BROWSER,
            severity = HealthSeverity.CRITICAL,
            code = HealthCodes.BROWSER_ENGINE_UNRESPONSIVE,
            summary = "The browser engine stopped creating browsers.",
            remedy = "Restart BOSS.",
        )

    @Test
    fun `a healthy workspace puts nothing on the status bar`() {
        val reads = AtomicInteger()
        rule.setContent {
            WorkspaceHealthStatusItem(readReport = {
                reads.incrementAndGet()
                WorkspaceHealthReport(findings = emptyList())
            })
        }
        // Wait for a real read, or this would pass before the report had arrived at all.
        rule.waitUntil(timeoutMillis = 5_000) { reads.get() > 0 }
        rule.waitForIdle()

        rule.onAllNodesWithText("issue", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a workspace with problems shows how many`() {
        val report = WorkspaceHealthReport(findings = listOf(stoppedPlugin, unresponsiveEngine))
        rule.setContent { WorkspaceHealthStatusItem(readReport = { report }) }

        // The first read happens off the main thread, so wait for it rather than for idle.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("2 issues").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("2 issues").assertExists()
    }

    @Test
    fun `the dialog stays open and shows the recovery after the badge hides`() {
        val current = AtomicReference(WorkspaceHealthReport(findings = listOf(stoppedPlugin)))
        rule.setContent { WorkspaceHealthStatusItem(readReport = { current.get() }, refreshIntervalMs = 50) }
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText("1 issue").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("1 issue").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Workspace Health").fetchSemanticsNodes().isNotEmpty()
        }

        // The problem is fixed from inside the dialog: the next read is healthy.
        current.set(WorkspaceHealthReport(findings = emptyList()))
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.mainClock.advanceTimeBy(100)
            rule.onAllNodesWithText("No problems found").fetchSemanticsNodes().isNotEmpty()
        }

        rule.onAllNodesWithText("1 issue").assertCountEquals(0)
        rule.onNodeWithText("Workspace Health").assertExists()
    }

    @Test
    fun `a read that throws does not stop the badge or the loop`() {
        HealthSourceWarnings.clear()
        val reads = AtomicInteger()
        val report = WorkspaceHealthReport(findings = listOf(stoppedPlugin))
        rule.setContent {
            WorkspaceHealthStatusItem(
                readReport = {
                    // Fails first, answers once, then fails on every read after.
                    if (reads.incrementAndGet() == 2) report else error("report assembly failed")
                },
                refreshIntervalMs = 50,
            )
        }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.mainClock.advanceTimeBy(100)
            reads.get() >= 4
        }

        // The loop survived the first failure to read again, and later failures kept the last report.
        rule.onNodeWithText("1 issue").assertExists()
        HealthSourceWarnings.clear()
    }

    @Test
    fun `a report that keeps failing is logged once until it reads again`() {
        HealthSourceWarnings.clear()
        val marker = "report assembly failed ${System.nanoTime()}"
        val failing = { error(marker) }
        val healthy = { WorkspaceHealthReport(findings = emptyList()) }

        repeat(3) { assertNull(readReportContained(failing)) }
        assertEquals(1, warnings(marker), "the status bar re-reads every few seconds; one warning is enough")

        assertEquals(healthy(), readReportContained(healthy))
        assertNull(readReportContained(failing))
        assertEquals(2, warnings(marker), "failing again after a clean read is a new failure")
        HealthSourceWarnings.clear()
    }

    @Test
    fun `cancellation is not contained`() {
        assertFailsWith<CancellationException> { readReportContained { throw CancellationException("window closed") } }
    }

    private fun warnings(marker: String): Int =
        BossLogger.getRecentLogs(limit = 1000).count {
            it.message == "Workspace health report could not be read" && it.data?.get("error") == marker
        }

    @Test
    fun `the card lists each finding with its remedy`() {
        mountCard(WorkspaceHealthReport(findings = listOf(unresponsiveEngine, stoppedPlugin)))

        rule.onNodeWithText("2 issues, 1 critical").assertExists()
        rule.onNodeWithText(stoppedPlugin.summary).assertExists()
        rule.onNodeWithText("What to do: Restart BOSS.").assertExists()
        rule.onNodeWithText("CRITICAL · Browser engine").assertExists()
    }

    @Test
    fun `a finding with a fixing screen offers it, and one without offers nothing`() {
        val fixes = mutableListOf<HealthFix>()
        val report = WorkspaceHealthReport(findings = listOf(unresponsiveEngine, stoppedPlugin))
        mountCard(report, onFix = { fixes += it })

        // Exactly one button: the unresponsive engine's only remedy is a restart, which no screen does.
        rule.onAllNodesWithText("Open", substring = true).assertCountEquals(1)
        rule.onNodeWithText(HealthFix.OPEN_PLUGIN_HEALTH.label).performClick()

        assertEquals(listOf(HealthFix.OPEN_PLUGIN_HEALTH), fixes)
    }

    @Test
    fun `a healthy report still explains an area it could not check`() {
        mountCard(WorkspaceHealthReport(findings = emptyList(), unchecked = setOf(HealthArea.BROWSER)))

        rule.onNodeWithText("No problems found, but not every area could be checked").assertExists()
        rule.onNodeWithText("Browser engine could not be checked", substring = true).assertExists()
    }

    @Test
    fun `the plugin fix opens Plugin Health & Recovery in this window`() {
        val opened =
            runBlocking {
                val events = MenuActionsHandler.showPluginHealthCenterEvents
                val event = async(start = CoroutineStart.UNDISPATCHED) { events.first() }
                openHealthFix(HealthFix.OPEN_PLUGIN_HEALTH, "window-1")
                withTimeout(1_000) { event.await() }
            }

        assertEquals("window-1", opened)
    }

    @Test
    fun `the browser engine fix opens Settings on the Browser Engine page`() {
        val opened =
            runBlocking {
                val events = MenuActionsHandler.openSettingsEvents
                val event = async(start = CoroutineStart.UNDISPATCHED) { events.first() }
                openHealthFix(HealthFix.OPEN_BROWSER_ENGINE_SETTINGS, "window-1")
                withTimeout(1_000) { event.await() }
            }

        assertEquals("window-1" to BROWSER_ENGINE_SETTINGS_SECTION, opened)
    }

    private fun mountCard(
        report: WorkspaceHealthReport,
        onFix: (HealthFix) -> Unit = {},
    ) {
        rule.setContent { WorkspaceHealthCard(report = report, onFix = onFix, onDismiss = {}) }
        rule.waitForIdle()
    }
}
