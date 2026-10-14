package ai.rever.boss.components.dialogs

import ai.rever.boss.health.HealthFinding
import ai.rever.boss.health.HealthFix
import ai.rever.boss.health.HealthIndicatorLevel
import ai.rever.boss.health.HealthSeverity
import ai.rever.boss.health.WorkspaceHealthReport
import ai.rever.boss.health.coverageNotes
import ai.rever.boss.health.displayName
import ai.rever.boss.health.fix
import ai.rever.boss.health.headline
import ai.rever.boss.health.indicatorLevel
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * The in-app face of the workspace health report `boss doctor` prints: every finding with its
 * remedy, and a way straight to the screen that fixes it where there is one.
 *
 * Before this, a plugin the sandbox stopped after repeated failures was announced by one toast
 * that disappears, and was otherwise visible only by opening Help > Plugin Health & Recovery or
 * running `boss doctor` in a terminal (BossConsole#394). [report] is re-read by the caller while
 * the dialog is open, so a problem fixed from here is seen to clear without reopening it.
 *
 * [onFix] is null when there is no screen to send a fix to; the Fix buttons are then left out
 * rather than shown doing nothing, and each finding's remedy text still says what to do.
 */
@Composable
internal fun WorkspaceHealthDialog(
    report: WorkspaceHealthReport,
    onFix: ((HealthFix) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(LocalDensity.current) { windowSize.height.toDp() }
    val windowWidth = with(LocalDensity.current) { windowSize.width.toDp() }
    val maxHeight = if (windowHeight > 0.dp) (windowHeight - 32.dp).coerceAtLeast(1.dp) else 700.dp
    val maxWidth = if (windowWidth > 0.dp) (windowWidth - 32.dp).coerceAtLeast(1.dp) else 560.dp
    BossDialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).width(560.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(BossTheme.radius.dialog),
            color = BossTheme.colors.panel,
        ) {
            WorkspaceHealthCard(report = report, onFix = onFix, onDismiss = onDismiss)
        }
    }
}

/** The dialog's content, without the window around it, so it can be rendered and tested on its own. */
@Composable
internal fun WorkspaceHealthCard(
    report: WorkspaceHealthReport,
    onFix: ((HealthFix) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = BossTheme.colors
    Column(modifier = Modifier.padding(24.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text("Workspace Health", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "Plugins, the browser engine and MCP tools, checked every few seconds. " +
                        "The same report the boss doctor command prints.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = report.headline(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = levelColor(report.indicatorLevel),
            )
            report.findings.forEach { finding ->
                Spacer(Modifier.height(12.dp))
                HealthFindingRow(finding = finding, onFix = onFix)
            }
            report.coverageNotes().forEach { note ->
                Spacer(Modifier.height(8.dp))
                Text(note, fontSize = 12.sp, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal)) {
                Text("Close", color = colors.onSignal, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HealthFindingRow(
    finding: HealthFinding,
    onFix: ((HealthFix) -> Unit)?,
) {
    val colors = BossTheme.colors
    val severityColor = severityColor(finding.severity)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colors.line, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "${finding.severity.label} · ${finding.area.displayName}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = severityColor,
        )
        Spacer(Modifier.height(4.dp))
        Text(finding.summary, fontSize = 13.sp, color = colors.textPrimary)
        finding.remedy?.let { remedy ->
            Spacer(Modifier.height(4.dp))
            Text("What to do: $remedy", fontSize = 12.sp, color = colors.textSecondary)
        }
        val fix = finding.fix
        if (fix != null && onFix != null) {
            Spacer(Modifier.height(8.dp))
            // Explicit colours: OutlinedButton's defaults come from MaterialTheme, whose surface is
            // light, so a themed label on it is white on white.
            OutlinedButton(
                onClick = { onFix(fix) },
                border = BorderStroke(1.dp, colors.lineStrong),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        backgroundColor = Color.Transparent,
                        contentColor = colors.textPrimary,
                    ),
            ) {
                Text(fix.label, fontSize = 12.sp)
            }
        }
    }
}

private val HealthSeverity.label: String
    get() =
        when (this) {
            HealthSeverity.CRITICAL -> "CRITICAL"
            HealthSeverity.WARNING -> "WARNING"
        }

@Composable
private fun severityColor(severity: HealthSeverity): Color =
    when (severity) {
        HealthSeverity.CRITICAL -> BossTheme.colors.alert
        HealthSeverity.WARNING -> BossTheme.colors.warn
    }

@Composable
internal fun levelColor(level: HealthIndicatorLevel): Color =
    when (level) {
        HealthIndicatorLevel.HEALTHY -> BossTheme.colors.ok
        HealthIndicatorLevel.INCOMPLETE -> BossTheme.colors.textSecondary
        HealthIndicatorLevel.WARNING -> BossTheme.colors.warn
        HealthIndicatorLevel.CRITICAL -> BossTheme.colors.alert
    }
