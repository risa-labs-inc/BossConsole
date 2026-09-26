package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.overlays.ContextMenuItem

/**
 * The counts behind the bottom bar's single "MCP access" item.
 *
 * @property savedRules persisted per-tool rules ("Always allow" / "Always deny").
 * @property trustedPlugins persisted provider-wide ALLOWs ("Trust plugin").
 * @property sessionGrants tools trusted for this session only.
 * @property yolo whether YOLO mode is on (every ASK runs without prompting).
 * @property yoloAvailable false when the deployment refuses YOLO mode, which hides the entry.
 */
internal data class McpAccessSummary(
    val savedRules: Int,
    val trustedPlugins: Int,
    val sessionGrants: Int,
    val yolo: Boolean = false,
    val yoloAvailable: Boolean = true,
) {
    /**
     * Shown while there is anything to manage, or any tool a rule could be set for. The same
     * condition the three controls it replaced had between them, so nothing that used to be
     * reachable from the bar stops being reachable.
     */
    fun isVisible(hasTools: Boolean): Boolean {
        val anyGrant = savedRules > 0 || trustedPlugins > 0 || sessionGrants > 0
        return yolo || hasTools || anyGrant
    }

    /** The session-trust badge read aloud, e.g. "2 tools trusted for this session". */
    fun sessionGrantsDescription(): String = count(sessionGrants, "tool", "tools") + " trusted for this session"

    /** What the bar item reads: while YOLO is on the bar says so, in words, not only in colour. */
    val label: String get() = if (yolo) "MCP: YOLO" else "MCP access"

    fun tooltip(): String =
        if (yolo) {
            "YOLO mode is on - MCP tools run without asking, except explicit denies. Click to turn it off."
        } else {
            permissionsTooltip()
        }

    private fun permissionsTooltip(): String =
        buildList {
            add(count(sessionGrants, "tool", "tools") + " trusted for this session")
            add(count(savedRules, "saved rule", "saved rules"))
            add(count(trustedPlugins, "trusted plugin", "trusted plugins"))
        }.joinToString(prefix = "MCP tool permissions - ", separator = ", ")
}

private fun count(
    n: Int,
    one: String,
    many: String,
): String = "$n ${if (n == 1) one else many}"

/**
 * The menu behind the "MCP access" item, one entry per kind of grant. Entries that would open an
 * empty list are left out rather than disabled, so the menu only ever offers something that does
 * something; "Tool policies" is always present because it is also where a rule is set proactively.
 *
 * No icons, so on macOS it renders as a real NSMenu (see `ContextMenu`'s `isNativeRepresentable`).
 */
internal fun mcpAccessMenuItems(
    summary: McpAccessSummary,
    onPolicies: () -> Unit,
    onSessionTrust: () -> Unit,
    onTrustedPlugins: () -> Unit,
    onSentinel: () -> Unit = {},
    onYolo: () -> Unit = {},
): List<ContextMenuItem> =
    buildList {
        // Turning YOLO off is the first thing offered while it is on, so it is one click from the
        // red bar item; turning it on sits last, behind a divider and a confirmation.
        if (summary.yolo) {
            add(ContextMenuItem(text = "Turn off YOLO mode", onClick = onYolo))
            add(ContextMenuItem(isDivider = true))
        }
        add(
            ContextMenuItem(
                text = if (summary.savedRules > 0) "Tool policies (${summary.savedRules})..." else "Tool policies...",
                onClick = onPolicies,
            ),
        )
        add(
            ContextMenuItem(
                text = "ToolDNA Sentinel...",
                onClick = onSentinel,
            ),
        )
        if (summary.sessionGrants > 0) {
            add(ContextMenuItem(text = "Session trust (${summary.sessionGrants})...", onClick = onSessionTrust))
        }
        if (summary.trustedPlugins > 0) {
            add(ContextMenuItem(text = "Trusted plugins (${summary.trustedPlugins})...", onClick = onTrustedPlugins))
        }
        if (!summary.yolo && summary.yoloAvailable) {
            add(ContextMenuItem(isDivider = true))
            add(ContextMenuItem(text = "YOLO mode...", onClick = onYolo))
        }
    }

/**
 * A tool call's duration for the bar's one-line status: `850ms`, `12.4s`, `4m 16s`. The raw
 * millisecond count it replaced ran to six digits for a long agent call (`256100ms`), which is
 * both hard to read and a lot of bar.
 */
internal fun formatMcpDuration(durationMs: Long): String =
    when {
        durationMs < 0 -> {
            "0ms"
        }

        durationMs < MS_PER_SECOND -> {
            "${durationMs}ms"
        }

        durationMs < MS_PER_MINUTE -> {
            "${durationMs / MS_PER_SECOND}.${(durationMs % MS_PER_SECOND) / 100}s"
        }

        else -> {
            val totalSeconds = durationMs / MS_PER_SECOND
            "${totalSeconds / 60}m ${totalSeconds % 60}s"
        }
    }

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L
