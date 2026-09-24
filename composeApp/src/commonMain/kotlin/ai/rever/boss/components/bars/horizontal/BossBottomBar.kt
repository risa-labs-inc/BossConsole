package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.bars.ChromeBar
import ai.rever.boss.components.bars.getBarScrollbarConfig
import ai.rever.boss.components.bars.horizontalScrollWithScrollbar
import ai.rever.boss.components.bars.rememberBarContextMenuItems
import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.dialogs.McpActivityLogDialog
import ai.rever.boss.components.dialogs.McpFlightPlanLauncher
import ai.rever.boss.components.dialogs.McpPolicyManagerDialog
import ai.rever.boss.components.dialogs.McpProviderTrustDialog
import ai.rever.boss.components.dialogs.McpSessionTrustDialog
import ai.rever.boss.components.dialogs.McpToolIdentity
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.registries.StatusBarRegistryImpl
import ai.rever.boss.components.plugin.registries.owningPluginId
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.layout.BossChrome
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.mcp.McpYoloPrompt
import ai.rever.boss.performance.PerformanceState
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.plugin.api.StatusBarAlignment
import ai.rever.boss.plugin.sandbox.ui.PluginExtensionBoundary
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.Project
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BossBottomBar(tabsComponent: BossTabsComponent? = null) {
    Divider(color = BossTheme.colors.line, thickness = BossChrome.dimens.dividerThickness)
    HorizontalBar(
        modifier = Modifier.contextMenu(items = rememberBarContextMenuItems(ChromeBar.BOTTOM)),
        height = BossChrome.dimens.bottomBarHeight,
    ) {
        HorizontalBarRow {
            BossLeftBottomBar(tabsComponent)
            PluginStatusBarItems(StatusBarAlignment.LEFT)
            Spacer(modifier = Modifier.weight(0.1f))
            PluginStatusBarItems(StatusBarAlignment.RIGHT)
            BossRightBottomBar()
        }
    }
}

/**
 * Plugin-contributed status-bar widgets (StatusBarRegistry) for one alignment
 * group. Each widget renders inside a [PluginExtensionBoundary]: a crash
 * attributed to the owning plugin collapses that widget to a compact error
 * marker instead of corrupting the status bar (or, for a plugin with no
 * other boundary, escalating to the app-level CrashHandler).
 */
@Composable
private fun PluginStatusBarItems(alignment: StatusBarAlignment) {
    val items by StatusBarRegistryImpl.items.collectAsState()
    val access by StatusBarRegistryImpl.access.collectAsState()
    val visible =
        remember(items, access, alignment) {
            StatusBarRegistryImpl.visibleItems(alignment)
        }
    visible.forEach { provider ->
        key(provider.itemId) {
            PluginExtensionBoundary(
                pluginId = owningPluginId(provider),
                surface = "status item ${provider.itemId}",
            ) {
                provider.Content()
            }
        }
    }
}

@Composable
fun RightArrow() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        modifier = Modifier.size(18.dp),
        contentDescription = "Right Arrow",
        tint = BossTheme.colors.textSecondary,
    )
}

@Composable
fun RowScope.BossLeftBottomBar(tabsComponent: BossTabsComponent? = null) {
    Column(modifier = Modifier.weight(2f).padding(horizontal = 8.dp)) {
        Row(
            modifier =
                Modifier
                    .horizontalScrollWithScrollbar(
                        rememberScrollState(),
                        scrollbarConfig = getBarScrollbarConfig(),
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Collect state once at the top level to avoid multiple subscriptions (per-window)
            val windowProjectState = LocalWindowProjectState.current
            val currentProject by windowProjectState?.selectedProject?.collectAsState()
                ?: remember { mutableStateOf(Project("No Project", "", 0L)) }

            if (tabsComponent != null) {
                val tabsState by tabsComponent.tabsState.subscribeAsState()
                val activeTab = tabsState.activeTab

                when (activeTab) {
                    is EditorTabInfo -> {
                        // Show file path from project root
                        val pathParts = editorBreadcrumbSegments(activeTab.filePath, currentProject.path)

                        pathParts.forEachIndexed { index, part ->
                            BossActionButton(
                                text = part,
                                color = BossTheme.colors.textSecondary,
                                onClick = {},
                            )
                            if (index < pathParts.lastIndex) {
                                RightArrow()
                            }
                        }
                    }

                    is FluckTabInfo -> {
                        // Show current URL
                        Text(
                            text = activeTab.currentUrl,
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    is TerminalTabInfo -> {
                        // Show terminal title (e.g., "user@hostname:/path")
                        Text(
                            text = activeTab.title,
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    null -> {
                        // Explicitly handle null case (no tab active)
                        Text(
                            text = "${currentProject.name} | Ready",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    else -> {
                        // Handle unknown tab types
                        Text(
                            text = "${currentProject.name} | ${activeTab.title}",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            } else {
                // Show minimal content if no tabs component (shouldn't happen in normal use)
                Text(
                    text = "Ready",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun BossRightBottomBar() {
    val windowId = LocalWindowId.current
    val scope = rememberCoroutineScope()

    // MCP kill-switch degraded state. Persistent on purpose: this is the only
    // access control an admin user does not bypass, and while it is showing, some
    // or all agent tools are being withheld. A transient status message cannot
    // carry that — the next message cancels it, and one raised during startup is
    // gone before the window is up (BossConsole#85).
    val killSwitchFault by McpToolRegistryImpl.killSwitchFault.collectAsState()
    killSwitchFault?.let { fault ->
        Text(
            text = "⚠ ${fault.message}",
            color = BossTheme.colors.alert,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 8.dp),
        )
    }

    // Every MCP consent control - session trust, saved tool policies, trusted plugins - behind one
    // bar item. They used to be three separate controls (two of them full-size Material
    // TextButtons, taller than the bar itself, so their labels were clipped), which spent most of
    // the bar on grants that are usually empty or rarely touched.
    val persistedPolicyConfig by McpToolRegistryImpl.policyEngine.config.collectAsState()
    McpAccessStatusItem(persistedPolicyConfig)

    val policyFault by McpToolRegistryImpl.policyFault.collectAsState()
    policyFault?.let { fault ->
        Text(
            text = "⚠ ${fault.message}",
            color = BossTheme.colors.alert,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 8.dp),
        )
    }

    // Governed Autonomy telemetry: show last executed tool, duration, and status. Clickable
    // because this line used to be the ONLY visibility into MCP activity - every call before
    // the current one, and the policy/approval decision behind it, was reachable only by
    // opening the rotated MCP ledger file in a text editor.
    McpActivityStatusItem()

    // Status message (temporary messages like "Space Saved")
    val statusMessage by StatusMessageManager.currentMessage.collectAsState()
    statusMessage?.let { message ->
        Text(
            text = message,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = BossTheme.colors.ok, // Green color for success
            fontSize = 12.sp,
            modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 8.dp),
        )
    }

    // Live download/update progress, and the way into the download dialog. Ahead of
    // the performance indicator so a transfer sits next to the plugin status items
    // it used to be one of, rather than at the far edge of the bar.
    DownloadCenterStatusItem()

    // Workspace health: shown only while something is wrong, beside the performance figures it
    // complements - those say what BOSS costs, this says what is not working (BossConsole#394).
    WorkspaceHealthStatusItem()

    // Performance indicator (shows memory/CPU usage)
    val showIndicator = PerformanceState.shouldShowIndicator()
    if (showIndicator) {
        val snapshot = PerformanceState.currentSnapshot()
        val health = PerformanceState.currentHealth()
        // Resolved here rather than in the sampler because it is per window: this strip should
        // show the browser in THIS window, and a single sampled value would show one window's tab
        // in the other's.
        //
        // Keyed on the snapshot, which arrives every `memorySampleIntervalMs` - 1 s by default,
        // not the 15 s of ProcessFootprint.MEASURE_TTL_MS. Those are two different clocks: this
        // re-reads about once a second, while the value behind it only changes every 15 s. The
        // key is there to keep it off the recomposition path, not to match the sampler.
        val activeBrowserBytes =
            remember(snapshot, windowId) {
                windowId?.let { PerformanceState.activeBrowserBytes(it) } ?: 0L
            }
        PerformanceIndicator(
            snapshot = snapshot,
            health = health,
            activeBrowserBytes = activeBrowserBytes,
            onClick = { PerformanceState.togglePerformancePanel() },
        )
    }

    BossActionButton(
        imageVector = Icons.Outlined.Info,
        text = "Console",
        color = BossTheme.colors.textSecondary,
        onClick = {
            // Toggle Console panel (PanelId "console" with order 14)
            windowId?.let { wid ->
                scope.launch {
                    PanelEventBus.togglePanel(PanelId("console", 14), sourceWindowId = wid)
                }
            }
        },
    )
}

/**
 * The bottom bar's single MCP consent item, and the menu behind it:
 *
 * - **Tool policies** - inspect and revoke a rule saved via the approval dialog's "Always allow" /
 *   "Always deny", or set one proactively for a tool nothing has asked about yet (the gap
 *   AGENTS.md's governance section names as reachable only by hand-editing
 *   `~/.boss/mcp-tool-policy.json` and restarting).
 * - **Trusted plugins** - the provider-wide ALLOWs ("Trust plugin"), listed and revoked
 *   individually, since these are durable grants an operator made deliberately.
 * - **Session trust** - the tools allowed for this session only, listed and revoked
 *   individually or all at once.
 *
 * Session trust is the grant that is live right now and bypasses prompts, so while any exists the
 * item carries its count in the alert colour: collapsing three controls into one must not hide
 * that. [persistedPolicyConfig] is collected by the caller, once.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
private fun McpAccessStatusItem(persistedPolicyConfig: McpToolPolicyConfig) {
    val allTools by McpToolRegistryImpl.allTools.collectAsState()
    val sessionTrusted by McpToolRegistryImpl.policyEngine.sessionTrustedTools.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showPolicyManager by remember { mutableStateOf(false) }
    var showTrustedPlugins by remember { mutableStateOf(false) }
    var showSessionTrust by remember { mutableStateOf(false) }
    val yolo by McpToolRegistryImpl.yoloMode.collectAsState()
    val windowId = LocalWindowId.current
    val scope = rememberCoroutineScope()
    val summary =
        McpAccessSummary(
            savedRules = persistedPolicyConfig.rules.size,
            trustedPlugins = persistedPolicyConfig.providerRules.count { it.value == McpPolicyAction.ALLOW },
            sessionGrants = sessionTrusted.size,
            yolo = yolo,
            yoloAvailable = McpToolRegistryImpl.yoloAvailable,
        )
    if (summary.isVisible(hasTools = allTools.isNotEmpty())) {
        var anchorHeight by remember { mutableStateOf(0) }
        Box(modifier = Modifier.onSizeChanged { anchorHeight = it.height }) {
            StatusBarTextButton(
                text = summary.label,
                color = if (yolo) BossTheme.colors.alert else BossTheme.colors.textSecondary,
                leadingIcon = if (yolo) Icons.Outlined.GppMaybe else Icons.Outlined.Security,
                badge = summary.sessionGrants.takeIf { it > 0 }?.toString(),
                badgeDescription = summary.sessionGrantsDescription(),
                tooltip = summary.tooltip(),
                clickLabel = "Open MCP access menu",
                onClick = { showMenu = true },
            )
            if (showMenu) {
                ContextMenu(
                    items =
                        mcpAccessMenuItems(
                            summary = summary,
                            onPolicies = { showPolicyManager = true },
                            onSessionTrust = { showSessionTrust = true },
                            onTrustedPlugins = { showTrustedPlugins = true },
                            onYolo = {
                                if (yolo) {
                                    scope.launch { McpToolRegistryImpl.setYoloMode(false) }
                                } else {
                                    windowId?.let(McpYoloPrompt::request)
                                }
                            },
                        ),
                    // Opens upward from the item: the bar sits at the bottom edge of the window.
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, -anchorHeight),
                    onDismissRequest = { showMenu = false },
                )
            }
        }
    }
    if (showSessionTrust) {
        McpSessionTrustDialog(
            trusted = sessionTrusted,
            // The exact (provider, tool) pair: a same-named tool from another provider keeps its
            // own grant. In-memory only, so there is no disk write to move off the UI thread.
            onRevoke = { McpToolRegistryImpl.policyEngine.revokeSessionTrust(it.toolName, it.providerId) },
            onRevokeAll = { McpToolRegistryImpl.policyEngine.clearSessionTrusts() },
            onDismiss = { showSessionTrust = false },
        )
    }
    if (showTrustedPlugins) {
        McpProviderTrustDialog(
            providerRules = persistedPolicyConfig.providerRules,
            // Dispatchers.IO: revokeProviderPolicy performs the same synchronized atomicWriteText
            // disk write as revokePersistedPolicy, off the UI thread for the same reason.
            onRevoke = { providerId ->
                withContext(Dispatchers.IO) {
                    McpToolRegistryImpl.policyEngine.revokeProviderPolicy(providerId)
                }
            },
            onDismiss = { showTrustedPlugins = false },
        )
    }
    if (showPolicyManager) {
        val disabledToolNames by McpToolRegistryImpl.disabledToolNames.collectAsState()
        var candidateRefresh by remember { mutableStateOf(0) }
        val availableTools =
            remember(allTools, persistedPolicyConfig.rules, disabledToolNames, candidateRefresh) {
                mcpProactivePolicyCandidates(
                    allTools,
                    persistedPolicyConfig.rules,
                    disabledToolNames,
                    McpToolRegistryImpl.policyEngine::revocationVersion,
                )
            }
        McpPolicyManagerDialog(
            rules = persistedPolicyConfig.rules,
            availableTools = availableTools,
            // Dispatchers.IO: revokePersistedPolicy and setToolPolicyIfAbsent both do a
            // synchronized atomicWriteText disk write - this call site was the one still running
            // it on the UI thread, where a click could block behind another write holding the
            // same lock from a slow, networked or AV-scanned home directory.
            onRevoke = { toolName ->
                withContext(Dispatchers.IO) {
                    McpToolRegistryImpl.policyEngine.revokePersistedPolicy(toolName)
                }
            },
            // setToolPolicyIfAbsent, not setToolPolicy: this path must add a rule only while the
            // tool still has none of its own, atomically re-checked at write time - not just
            // refuse a DENY, and not only when the revocation counter moved. An intervening
            // explicit ASK or ALLOW, made through the reactive approval dialog for this same tool
            // between "this row was offered" and this click reaching disk, never bumps that
            // counter, so a `preserveDeny`-style guard alone would let this write silently
            // clobber it (review on #636). tool.expectedRevocation is still passed, and still
            // checked first, to catch a DENY or provider-wide reset the same way the reactive
            // path's own capture-then-recheck does.
            onSetPolicy = ::saveProactiveToolPolicy,
            onRefreshCandidates = { candidateRefresh++ },
            onDismiss = { showPolicyManager = false },
            sectionTools =
                remember(allTools, persistedPolicyConfig.rules, disabledToolNames, candidateRefresh) {
                    mcpProactivePolicyCandidates(
                        allTools,
                        emptyMap(),
                        disabledToolNames,
                        McpToolRegistryImpl.policyEngine::revocationVersion,
                    )
                },
            onApplySection = { changes ->
                withContext(Dispatchers.IO) { McpToolRegistryImpl.policyEngine.setSectionPolicies(changes) }
            },
        )
    }
}

/**
 * Every registered tool [McpPolicyManagerDialog] may write a proactive rule for: not disabled by
 * the kill switch (a proactive rule for a disabled tool would do nothing - [McpToolRegistryImpl]
 * resolves invocation against `tools`, which already excludes it), and not already in [rules]
 * (that tool has its row in the saved-rules list instead). Sorted here, once, rather than by the
 * composable on every recomposition - the caller already [remember]s the result.
 *
 * A pure function, not a `@Composable`, because the exclusion this expresses - never offer a rule
 * for a tool that already has one, or that a rule could not affect - is the one thing standing
 * between the dialog's Allow/Deny buttons and silently overwriting an existing DENY or granting a
 * dead tool nothing can invoke. That is worth pinning on its own, not only through the dialog it
 * feeds (review on #636).
 *
 * [revocationVersion] is injected - normally
 * [ai.rever.boss.mcp.McpPolicyEngine.revocationVersion] - rather than reached for directly, so
 * this stays a pure, testable mapping over its arguments; each candidate's own generation is
 * stamped onto it as [McpToolIdentity.expectedRevocation], for [McpPolicyManagerDialog]'s write
 * path to pass back to `setToolPolicyIfAbsent` unchanged.
 */
internal fun mcpProactivePolicyCandidates(
    allTools: List<RegisteredMcpTool>,
    rules: Map<String, McpPolicyAction>,
    disabledToolNames: Set<String>,
    revocationVersion: (toolName: String, providerId: String?) -> Long,
): List<McpToolIdentity> =
    allTools
        .asSequence()
        .filter { it.definition.name !in rules }
        .filter { it.definition.name !in disabledToolNames }
        .map {
            McpToolIdentity(
                it.definition.name,
                it.providerId,
                revocationVersion(it.definition.name, it.providerId),
                it.definition.description,
                it.definition.readOnly,
            )
        }.sortedBy { it.toolName }
        .toList()

/**
 * The single most recent MCP tool call, clickable into [McpActivityLogDialog] for everything
 * behind it. Split out of [BossRightBottomBar] because that function's own branching was already
 * at detekt's [CyclomaticComplexMethod] ceiling before this existed.
 *
 * Reachable even with no activity yet ([McpToolRegistryImpl.ledger]'s ring buffer empty): "has
 * anything used MCP this session?" is a question worth being able to ask before the first call,
 * not only after one - and is when an operator is most likely to be checking (review on #636).
 */
@Composable
private fun McpActivityStatusItem() {
    val recentOps by McpToolRegistryImpl.ledger.recentOperations.collectAsState()
    var showActivityLog by remember { mutableStateOf(false) }
    var showFlightPlan by remember { mutableStateOf(false) }
    val allTools by McpToolRegistryImpl.allTools.collectAsState()
    val shouldRender =
        mcpActivityStatusShowsFor(allTools, recentOps.isNotEmpty(), showActivityLog, showFlightPlan)
    if (!shouldRender) return
    // The most recent CALL: a YOLO on/off marker is in the ledger for audit but is not a call.
    val lastOp = recentOps.firstOrNull { !it.approvalDisposition.isGovernanceEvent }
    val statusText =
        if (lastOp != null) {
            "MCP: ${lastOp.toolName} (${formatMcpDuration(lastOp.durationMs)}) ${if (lastOp.isError) "✕" else "✓"}"
        } else {
            "MCP: no activity yet"
        }
    val statusColor = if (lastOp?.isError == true) BossTheme.colors.alert else BossTheme.colors.textSecondary
    StatusBarTextButton(
        text = statusText,
        color = statusColor,
        tooltip = "Open the MCP activity log",
        onClick = { showActivityLog = true },
    )
    if (showActivityLog) {
        val totalCalls by McpToolRegistryImpl.ledger.totalCalls.collectAsState()
        val totalErrors by McpToolRegistryImpl.ledger.totalErrors.collectAsState()
        val pendingWriteIds by McpToolRegistryImpl.ledger.pendingWriteIds.collectAsState()
        val droppedWrites by McpToolRegistryImpl.ledger.droppedWrites.collectAsState()
        McpActivityLogDialog(
            operations = recentOps,
            totalCalls = totalCalls,
            totalErrors = totalErrors,
            ledgerPath = McpToolRegistryImpl.ledger.persistencePath,
            pendingWriteIds = pendingWriteIds,
            droppedWrites = droppedWrites,
            onOpenFlightPlan = {
                showActivityLog = false
                showFlightPlan = true
            },
            onDismiss = { showActivityLog = false },
        )
    }
    if (showFlightPlan) {
        McpFlightPlanLauncher(onDismiss = { showFlightPlan = false })
    }
}

/**
 * A clickable bottom-bar item sized to the bar: 11sp text, a 13dp icon and 2dp of vertical
 * padding, so it fits even the compact 24dp bar. Material's `TextButton` is not usable here - it
 * brings 8dp of vertical content padding and body-size text, which inside a 24-30dp bar clips the
 * label's descenders ("Trusted plugins" rendered with its "g" and "p" cut off).
 *
 * Carries the affordances a bare clickable [Text] lacks: a hand cursor, a tooltip naming what the
 * click does, and [Role.Button] semantics for assistive tech.
 */
@Composable
private fun StatusBarTextButton(
    text: String,
    tooltip: String,
    onClick: () -> Unit,
    color: Color = BossTheme.colors.textSecondary,
    leadingIcon: ImageVector? = null,
    badge: String? = null,
    // What a screen reader announces the click DOES. Defaults to the tooltip, which is right when
    // the tooltip names the action ("Open the MCP activity log") and wrong when it describes state.
    clickLabel: String = tooltip,
    // How the badge is read aloud; without it a screen reader announces a bare number.
    badgeDescription: String? = null,
) {
    val colors = BossTheme.colors
    HoverTooltipBox(text = tooltip, placement = TooltipPlacement.TOP) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(BossTheme.radius.input))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = badge,
                    color = colors.alert,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .background(colors.alert.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp)
                            .then(
                                if (badgeDescription != null) {
                                    Modifier.clearAndSetSemantics { contentDescription = badgeDescription }
                                } else {
                                    Modifier
                                },
                            ),
                )
            }
        }
    }
}
