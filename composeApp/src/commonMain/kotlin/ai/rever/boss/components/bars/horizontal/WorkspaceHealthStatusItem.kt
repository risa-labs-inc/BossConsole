package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.dialogs.WorkspaceHealthDialog
import ai.rever.boss.components.dialogs.levelColor
import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.health.BROWSER_ENGINE_SETTINGS_SECTION
import ai.rever.boss.health.HealthFix
import ai.rever.boss.health.HealthSourceWarnings
import ai.rever.boss.health.WorkspaceHealthReport
import ai.rever.boss.health.indicatorLevel
import ai.rever.boss.health.readWorkspaceHealth
import ai.rever.boss.health.showsStatusItem
import ai.rever.boss.health.statusDescription
import ai.rever.boss.health.statusText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * How often the status bar re-reads workspace health. Every source is a value BOSS already holds in
 * memory, plus one read-only check that the browser engine is installed, so this is cheap.
 */
private const val HEALTH_REFRESH_INTERVAL_MS = 5_000L

/** [HealthSourceWarnings] key for the report as a whole, apart from the per-area and per-window keys. */
private const val REPORT_WARNING_KEY = "report"

/** This item's [BottomBarHolds] key while its dialog is open. */
private const val HEALTH_DIALOG_HOLD_KEY = "workspace-health-dialog"

private val logger = BossLogger.forComponent("WorkspaceHealth")

/**
 * The workspace health item: nothing while there is nothing to report, and "2 issues" in the
 * findings' colour when there is, opening [WorkspaceHealthDialog].
 *
 * Persistent on purpose, for the reason the MCP fault line beside it is (BossConsole#85): a plugin
 * the sandbox stops after repeated failures is otherwise announced by one toast that disappears,
 * and the next thing the person notices is the plugin simply missing (BossConsole#394).
 *
 * [readReport] and [refreshIntervalMs] are parameters so tests can supply a report without a
 * running workspace, and watch it change without waiting out the real interval.
 */
@Composable
internal fun WorkspaceHealthStatusItem(
    readReport: () -> WorkspaceHealthReport = ::readWorkspaceHealth,
    refreshIntervalMs: Long = HEALTH_REFRESH_INTERVAL_MS,
) {
    val windowId = LocalWindowId.current
    val report by produceState<WorkspaceHealthReport?>(initialValue = null, readReport, refreshIntervalMs) {
        while (true) {
            // A failed read keeps the last report on screen; see [readReportContained].
            withContext(Dispatchers.IO) { readReportContained(readReport) }?.let { value = it }
            delay(refreshIntervalMs)
        }
    }
    var showDialog by remember { mutableStateOf(false) }
    val current = report ?: return

    // The dialog outlives the badge: once the last problem is fixed from inside it, it stays open
    // and shows the workspace as healthy, instead of vanishing with the badge mid-read.
    if (current.showsStatusItem) {
        WorkspaceHealthBadge(report = current, onClick = { showDialog = true })
    }
    if (showDialog) {
        // The dialog lives inside this item, so it must keep the bar composed while it is open: in
        // focus mode the bar auto-hides once the pointer moves onto the dialog, taking it along.
        HoldBottomBar(HEALTH_DIALOG_HOLD_KEY)
        WorkspaceHealthDialog(
            report = current,
            // No window id, no screen to send a fix to: the button would close the dialog and open
            // nothing, so it is left out and the finding's remedy text says what to do instead.
            onFix =
                windowId?.let { id ->
                    { fix: HealthFix ->
                        // Closed first: both fixes open another dialog, and two stacked modals is
                        // the kind of pile-up BossConsole#696 was about.
                        showDialog = false
                        openHealthFix(fix, id)
                    }
                },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * One read of the report, or null when the read throws.
 *
 * The collector contains each source, but it assembles the report outside those guards, and this read
 * now runs every few seconds in every window. An exception escaping the `produceState` effect would
 * cancel the window's recomposer effect job rather than just this badge, so it is contained here: the
 * badge keeps its last report, the failure is logged once until a read succeeds again, and the loop
 * keeps polling. LinkageError is caught for the collector's reason; cancellation is rethrown so the
 * loop still stops with its composition.
 */
@Suppress("TooGenericExceptionCaught")
internal fun readReportContained(readReport: () -> WorkspaceHealthReport): WorkspaceHealthReport? =
    try {
        readReport().also { HealthSourceWarnings.recovered(REPORT_WARNING_KEY) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportUnreadable(e)
    } catch (e: LinkageError) {
        reportUnreadable(e)
    }

private fun reportUnreadable(error: Throwable): Nothing? {
    if (!HealthSourceWarnings.failed(REPORT_WARNING_KEY)) return null
    logger.warn(
        LogCategory.SYSTEM,
        "Workspace health report could not be read",
        mapOf("error" to (error.message ?: error::class.simpleName)),
    )
    return null
}

@Composable
private fun WorkspaceHealthBadge(
    report: WorkspaceHealthReport,
    onClick: () -> Unit,
) {
    val color = levelColor(report.indicatorLevel)
    val description = report.statusDescription()
    HoverTooltipBox(text = description, placement = TooltipPlacement.TOP) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = "Open workspace health", onClick = onClick)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = description
                    },
        ) {
            Icon(
                imageVector = Icons.Outlined.MonitorHeart,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = report.statusText(), color = color, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Send [windowId] to the screen that fixes [fix], through the same events the application menu raises. */
internal fun openHealthFix(
    fix: HealthFix,
    windowId: String,
) {
    when (fix) {
        HealthFix.OPEN_PLUGIN_HEALTH -> {
            MenuActionsHandler.triggerShowPluginHealthCenter(windowId)
        }

        HealthFix.OPEN_BROWSER_ENGINE_SETTINGS -> {
            MenuActionsHandler.triggerOpenSettings(windowId, BROWSER_ENGINE_SETTINGS_SECTION)
        }
    }
}
