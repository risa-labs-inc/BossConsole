package ai.rever.boss.components.home

import ai.rever.boss.components.dashboard.sections.DashboardSection
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Project-independent layouts use the same window-scoped route as the Space picker. */
@Composable
internal fun HomeSideBySideSection(actions: HomeActions) {
    // The registry is live snapshot state, as in rememberHomeTools. A disabled or access-gated
    // provider unregisters its types, so these cards never substitute for plugin permissions.
    val registeredTypes =
        LocalTabRegistry.current
            ?.getAllTabTypes()
            ?.map { it.typeId }
            .orEmpty()
    val browserAvailable = FluckTabType.typeId in registeredTypes
    val terminalAvailable = TerminalTabType.typeId in registeredTypes
    val space = BossTheme.space

    DashboardSection(
        title = "Work side by side",
        subtitle = "Open a split layout in this window. No project needed.",
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = homeToolColumns(maxWidth, minTileWidth = 240.dp, gap = space.md).coerceAtMost(2)
            Column(verticalArrangement = Arrangement.spacedBy(space.md)) {
                listOf(false, true).chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(space.md),
                    ) {
                        row.forEach { withTerminal ->
                            val unavailableReason =
                                sideBySideUnavailableReason(browserAvailable, terminalAvailable, withTerminal)
                            SideBySideCard(
                                withTerminal = withTerminal,
                                unavailableReason = unavailableReason,
                                onClick = {
                                    actions.applyWorkspace(
                                        if (withTerminal) {
                                            PredefinedWorkspaces.BROWSER_TERMINAL_ID
                                        } else {
                                            PredefinedWorkspaces.DUAL_BROWSER_ID
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun sideBySideUnavailableReason(
    browserAvailable: Boolean,
    terminalAvailable: Boolean,
    withTerminal: Boolean,
): String? =
    when {
        !browserAvailable && withTerminal && !terminalAvailable -> {
            "Requires Browser and Terminal. Install or enable their plugins."
        }

        !browserAvailable -> {
            "Requires Browser. Install or enable its plugin."
        }

        withTerminal && !terminalAvailable -> {
            "Requires Terminal. Install or enable its plugin."
        }

        else -> {
            null
        }
    }

@Composable
private fun SideBySideCard(
    withTerminal: Boolean,
    unavailableReason: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BossTheme.colors
    val space = BossTheme.space
    val enabled = unavailableReason == null

    Column(
        modifier =
            modifier
                .clip(BossTheme.radius.cardShape)
                .background(if (enabled) colors.signalWash else colors.raised)
                .border(1.dp, colors.line, BossTheme.radius.cardShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(space.lg),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(space.xs),
        ) {
            PreviewPane(label = "Browser", modifier = Modifier.weight(1f))
            PreviewPane(label = if (withTerminal) "Terminal" else "Browser", modifier = Modifier.weight(1f))
        }
        Text(
            text = if (withTerminal) "Browser + terminal" else "Two browsers",
            style = BossTheme.type.label,
            color = if (enabled) colors.textPrimary else colors.textSecondary,
        )
        Text(
            text =
                if (withTerminal) {
                    "Keep a page beside your command line."
                } else {
                    "Compare pages or keep a reference open."
                },
            style = BossTheme.type.body,
            color = colors.textSecondary,
        )
        Text(
            text = unavailableReason ?: "Open layout",
            style = BossTheme.type.micro,
            color = if (enabled) colors.signalText else colors.textMuted,
        )
    }
}

@Composable
private fun PreviewPane(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(56.dp)
                .background(BossTheme.colors.panel, BossTheme.radius.cardShape)
                .padding(BossTheme.space.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = BossTheme.type.micro, color = BossTheme.colors.textSecondary)
    }
}
