package ai.rever.boss.health

/**
 * Read the same workspace health report `boss status` and `boss doctor` print. Never throws: a
 * source that cannot be read is reported as unchecked. Blocking, so call it off the main thread.
 */
internal expect fun readWorkspaceHealth(): WorkspaceHealthReport

/** How a report reads at a glance. Declared least severe first. */
internal enum class HealthIndicatorLevel {
    HEALTHY,

    /** No findings, but an area could not be read, or was read only in part. Not a clean bill of health. */
    INCOMPLETE,
    WARNING,
    CRITICAL,
}

internal val WorkspaceHealthReport.indicatorLevel: HealthIndicatorLevel
    get() =
        when {
            findings.any { it.severity == HealthSeverity.CRITICAL } -> HealthIndicatorLevel.CRITICAL
            findings.isNotEmpty() -> HealthIndicatorLevel.WARNING
            unchecked.isNotEmpty() || partial.isNotEmpty() -> HealthIndicatorLevel.INCOMPLETE
            else -> HealthIndicatorLevel.HEALTHY
        }

/**
 * Whether the status bar shows the health item at all. Only findings earn the space: a healthy
 * workspace needs no badge, and an incomplete read is routine for a moment at start-up, before the
 * window's plugin source registers, so badging it would flash on every launch.
 */
internal val WorkspaceHealthReport.showsStatusItem: Boolean get() = findings.isNotEmpty()

/** The status-bar label. Short on purpose: the bar is shared with MCP, downloads and performance. */
internal fun WorkspaceHealthReport.statusText(): String = issueCount(findings.size)

/** The headline of the health dialog, and the tooltip's first half. */
internal fun WorkspaceHealthReport.headline(): String {
    val critical = findings.count { it.severity == HealthSeverity.CRITICAL }
    return when (indicatorLevel) {
        HealthIndicatorLevel.HEALTHY -> "No problems found"
        HealthIndicatorLevel.INCOMPLETE -> "No problems found, but not every area could be checked"
        HealthIndicatorLevel.WARNING -> issueCount(findings.size)
        HealthIndicatorLevel.CRITICAL -> "${issueCount(findings.size)}, $critical critical"
    }
}

/** The tooltip, and what assistive tech reads for the status item: what it says and what a click does. */
internal fun WorkspaceHealthReport.statusDescription(): String = "Workspace health: ${headline()}. Click for details."

/**
 * One sentence per area the report does not fully cover, so an empty findings list is never read
 * as a clean bill of health for an area that was not looked at.
 */
internal fun WorkspaceHealthReport.coverageNotes(): List<String> =
    HealthArea.entries.mapNotNull { area ->
        when (area) {
            in unchecked -> "${area.displayName} could not be checked, so it is neither healthy nor degraded here."
            in partial -> "${area.displayName} was only partly checked, so its list may be incomplete."
            else -> null
        }
    }

private fun issueCount(count: Int): String = if (count == 1) "1 issue" else "$count issues"

/** What people call each area. The wire names stay lower-case identifiers for scripts. */
internal val HealthArea.displayName: String
    get() =
        when (this) {
            HealthArea.PLUGINS -> "Plugins"
            HealthArea.BROWSER -> "Browser engine"
            HealthArea.MCP -> "MCP tools"
        }

/**
 * A place in BOSS that can act on a finding. Only findings with a screen that fixes them get one:
 * the rest carry a remedy the person has to follow themselves, such as restarting BOSS, and a
 * button that only repeated the remedy would promise more than it does.
 */
internal enum class HealthFix(
    val label: String,
) {
    OPEN_PLUGIN_HEALTH("Open Plugin Health & Recovery"),
    OPEN_BROWSER_ENGINE_SETTINGS("Open Browser Engine settings"),
}

/**
 * Keyed on [HealthFinding.code], the stable identifier, never on the summary text, which is for
 * people and may be reworded.
 */
internal val HealthFinding.fix: HealthFix?
    get() =
        when (code) {
            HealthCodes.PLUGIN_STOPPED, HealthCodes.PLUGIN_NEEDS_ATTENTION -> HealthFix.OPEN_PLUGIN_HEALTH
            HealthCodes.BROWSER_ENGINE_NOT_INSTALLED -> HealthFix.OPEN_BROWSER_ENGINE_SETTINGS
            else -> null
        }

/** The Settings section that downloads and reinstalls the browser engine. Pinned by a test. */
internal const val BROWSER_ENGINE_SETTINGS_SECTION = "BROWSER_ENGINE"
