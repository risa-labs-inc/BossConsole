package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.ApprovalDecision
import ai.rever.boss.mcp.McpCallRecord
import ai.rever.boss.mcp.McpCallStatus
import ai.rever.boss.mcp.McpTelemetryRecorder
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private enum class FilterTab {
    ALL, PENDING, ERRORS, SUCCESS
}

/**
 * Full Mission Control Dialog: Real-Time MCP Call Inspector & HITL Guardrail Engine.
 */
@Composable
fun McpMissionControlDialog(onDismiss: () -> Unit) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val scope = rememberCoroutineScope()

    val records by McpTelemetryRecorder.records.collectAsState()
    val stats by McpTelemetryRecorder.stats.collectAsState()
    val toolsRequiringApproval by McpTelemetryRecorder.toolsRequiringApproval.collectAsState()
    val globalSafeMode by McpTelemetryRecorder.globalSafeMode.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(FilterTab.ALL) }
    var selectedCallId by remember { mutableStateOf<String?>(records.firstOrNull()?.callId) }

    // Replay state
    var isReplayMode by remember { mutableStateOf(false) }
    var replayArgs by remember { mutableStateOf("") }
    var replayResult by remember { mutableStateOf<String?>(null) }
    var isReplaying by remember { mutableStateOf(false) }

    val filteredRecords = remember(records, searchQuery, selectedFilter) {
        records.filter { r ->
            val matchesSearch = searchQuery.isBlank() ||
                r.toolName.contains(searchQuery, ignoreCase = true) ||
                r.arguments.contains(searchQuery, ignoreCase = true) ||
                (r.errorMessage?.contains(searchQuery, ignoreCase = true) == true)

            val matchesFilter = when (selectedFilter) {
                FilterTab.ALL -> true
                FilterTab.PENDING -> r.status == McpCallStatus.AWAITING_APPROVAL
                FilterTab.ERRORS -> r.status in setOf(McpCallStatus.ERROR, McpCallStatus.TIMEOUT, McpCallStatus.DENIED)
                FilterTab.SUCCESS -> r.status == McpCallStatus.SUCCESS
            }
            matchesSearch && matchesFilter
        }
    }

    val selectedRecord = records.firstOrNull { it.callId == selectedCallId } ?: filteredRecords.firstOrNull()

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .width(920.dp)
                .height(640.dp)
                .clip(RoundedCornerShape(radii.dialog)),
            color = colors.panel,
            shape = RoundedCornerShape(radii.dialog),
            elevation = 12.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                HeaderSection(
                    stats = stats,
                    globalSafeMode = globalSafeMode,
                    onSafeModeToggled = { McpTelemetryRecorder.setGlobalSafeMode(it) },
                    onClearHistory = { McpTelemetryRecorder.clear() },
                    onClose = onDismiss,
                )

                Divider(color = colors.line.copy(alpha = 0.5f))

                // Filter & Search Controls
                FilterSection(
                    searchQuery = searchQuery,
                    onSearchQueryChanged = { searchQuery = it },
                    selectedFilter = selectedFilter,
                    onFilterSelected = { selectedFilter = it },
                    stats = stats,
                )

                Divider(color = colors.line.copy(alpha = 0.5f))

                // Main Two-Pane Area
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Pane: Call List
                    CallListPane(
                        records = filteredRecords,
                        selectedCallId = selectedRecord?.callId,
                        onSelectCall = { callId ->
                            selectedCallId = callId
                            isReplayMode = false
                            replayResult = null
                        },
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    )

                    Divider(
                        color = colors.line.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                    )

                    // Right Pane: Inspector & Guardrail Actions
                    InspectorDetailPane(
                        record = selectedRecord,
                        isApprovalRequired = selectedRecord != null && (selectedRecord.toolName in toolsRequiringApproval),
                        onToggleApproval = { toolName, required ->
                            McpTelemetryRecorder.setToolRequiresApproval(toolName, required)
                        },
                        onDisableTool = { toolName ->
                            McpToolRegistryImpl.setToolEnabled(toolName, false)
                        },
                        onApprove = { callId ->
                            McpTelemetryRecorder.resolveApproval(callId, ApprovalDecision.Approved())
                        },
                        onDeny = { callId ->
                            McpTelemetryRecorder.resolveApproval(callId, ApprovalDecision.Denied("Denied by operator in Mission Control"))
                        },
                        isReplayMode = isReplayMode,
                        onToggleReplay = {
                            isReplayMode = !isReplayMode
                            if (isReplayMode && selectedRecord != null) {
                                replayArgs = selectedRecord.arguments
                                replayResult = null
                            }
                        },
                        replayArgs = replayArgs,
                        onReplayArgsChanged = { replayArgs = it },
                        replayResult = replayResult,
                        isReplaying = isReplaying,
                        onExecuteReplay = {
                            if (selectedRecord != null) {
                                scope.launch {
                                    isReplaying = true
                                    try {
                                        val res = McpToolRegistryImpl.invoke(selectedRecord.toolName, replayArgs)
                                        replayResult = if (res.isError) "ERROR: ${res.text}" else res.text
                                    } catch (t: Throwable) {
                                        replayResult = "EXCEPTION: ${t.message}"
                                    } finally {
                                        isReplaying = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(
    stats: ai.rever.boss.mcp.McpTelemetryStats,
    globalSafeMode: Boolean,
    onSafeModeToggled: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = BossTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "⚡",
                fontSize = 20.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column {
                Text(
                    text = "Agent Mission Control",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Real-time MCP Observability & Human-in-the-Loop Governance",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Safe Mode Toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Safe Mode",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (globalSafeMode) colors.warn else colors.textSecondary,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Switch(
                    checked = globalSafeMode,
                    onCheckedChange = onSafeModeToggled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.warn,
                        checkedTrackColor = colors.warn.copy(alpha = 0.4f),
                    ),
                )
            }

            IconButton(onClick = onClearHistory) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear History",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterSection(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    selectedFilter: FilterTab,
    onFilterSelected: (FilterTab) -> Unit,
    stats: ai.rever.boss.mcp.McpTelemetryStats,
) {
    val colors = BossTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.raised.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = { Text("Filter calls, tools, parameters...", fontSize = 12.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            },
            singleLine = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = colors.textPrimary,
                backgroundColor = colors.panel,
                cursorColor = colors.signal,
                focusedBorderColor = colors.signal,
                unfocusedBorderColor = colors.line,
            ),
            modifier = Modifier.width(280.dp).height(44.dp),
        )

        // Filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                label = "All (${stats.totalCalls})",
                selected = selectedFilter == FilterTab.ALL,
                onClick = { onFilterSelected(FilterTab.ALL) },
            )
            FilterChip(
                label = "Pending (${stats.pendingApprovalsCount})",
                selected = selectedFilter == FilterTab.PENDING,
                badgeColor = if (stats.pendingApprovalsCount > 0) colors.warn else null,
                onClick = { onFilterSelected(FilterTab.PENDING) },
            )
            FilterChip(
                label = "Errors (${stats.errorCount})",
                selected = selectedFilter == FilterTab.ERRORS,
                badgeColor = if (stats.errorCount > 0) colors.alert else null,
                onClick = { onFilterSelected(FilterTab.ERRORS) },
            )
            FilterChip(
                label = "Success (${stats.successCount})",
                selected = selectedFilter == FilterTab.SUCCESS,
                onClick = { onFilterSelected(FilterTab.SUCCESS) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    badgeColor: Color? = null,
    onClick: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(radii.button))
            .background(if (selected) colors.signalWash else colors.panel)
            .border(
                1.dp,
                if (selected) colors.signal else colors.line.copy(alpha = 0.5f),
                RoundedCornerShape(radii.button),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = badgeColor ?: if (selected) colors.signalText else colors.textSecondary,
        )
    }
}

@Composable
private fun CallListPane(
    records: List<McpCallRecord>,
    selectedCallId: String?,
    onSelectCall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BossTheme.colors

    if (records.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.padding(16.dp),
        ) {
            Text(
                text = "No MCP invocations recorded yet.",
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
        return
    }

    LazyColumn(modifier = modifier) {
        items(records, key = { it.callId }) { r ->
            val isSelected = r.callId == selectedCallId
            CallListItem(
                record = r,
                isSelected = isSelected,
                onClick = { onSelectCall(r.callId) },
            )
            Divider(color = colors.line.copy(alpha = 0.25f))
        }
    }
}

@Composable
private fun CallListItem(
    record: McpCallRecord,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BossTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) colors.raised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(record.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.toolName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = record.arguments.replace("\n", " ").trim(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = if (record.durationMs > 0) "${record.durationMs}ms" else "-",
            fontSize = 11.sp,
            color = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatusBadge(status: McpCallStatus) {
    val colors = BossTheme.colors
    val (label, bg, fg) = when (status) {
        McpCallStatus.SUCCESS -> Triple("OK", colors.ok.copy(alpha = 0.2f), colors.ok)
        McpCallStatus.ERROR -> Triple("ERR", colors.alert.copy(alpha = 0.2f), colors.alert)
        McpCallStatus.TIMEOUT -> Triple("TIME", colors.alert.copy(alpha = 0.2f), colors.alert)
        McpCallStatus.AWAITING_APPROVAL -> Triple("WAIT", colors.warn.copy(alpha = 0.25f), colors.warn)
        McpCallStatus.APPROVED -> Triple("APP", colors.ok.copy(alpha = 0.2f), colors.ok)
        McpCallStatus.DENIED -> Triple("DENY", colors.alert.copy(alpha = 0.2f), colors.alert)
        McpCallStatus.BLOCKED -> Triple("BLOCK", colors.textMuted.copy(alpha = 0.2f), colors.textMuted)
        McpCallStatus.RUNNING -> Triple("RUN", colors.signal.copy(alpha = 0.2f), colors.signal)
        McpCallStatus.CANCELLED -> Triple("CANC", colors.textMuted.copy(alpha = 0.2f), colors.textMuted)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
        )
    }
}

@Composable
private fun InspectorDetailPane(
    record: McpCallRecord?,
    isApprovalRequired: Boolean,
    onToggleApproval: (String, Boolean) -> Unit,
    onDisableTool: (String) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    isReplayMode: Boolean,
    onToggleReplay: () -> Unit,
    replayArgs: String,
    onReplayArgsChanged: (String) -> Unit,
    replayResult: String?,
    isReplaying: Boolean,
    onExecuteReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius

    if (record == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.padding(24.dp),
        ) {
            Text(
                text = "Select an MCP tool invocation to inspect payload and guardrails.",
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Top Tool Title & Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.toolName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(record.status)
                }
                Text(
                    text = "Call ID: ${record.callId} · Latency: ${record.durationMs}ms",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
            }

            // Quick Replay Button
            OutlinedButton(
                onClick = onToggleReplay,
                shape = RoundedCornerShape(radii.button),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Replay",
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (isReplayMode) "Close Scratchpad" else "Replay Tool", fontSize = 12.sp)
            }
        }

        // Pending Human Approval Callout (if awaiting)
        if (record.status == McpCallStatus.AWAITING_APPROVAL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(radii.card))
                    .background(colors.warn.copy(alpha = 0.15f))
                    .border(1.dp, colors.warn, RoundedCornerShape(radii.card))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Action paused: Waiting for your confirmation.",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = colors.warn,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDeny(record.callId) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.alert),
                        shape = RoundedCornerShape(radii.button),
                    ) {
                        Text("Deny", fontSize = 11.sp, color = colors.signalText)
                    }
                    Button(
                        onClick = { onApprove(record.callId) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.ok),
                        shape = RoundedCornerShape(radii.button),
                    ) {
                        Text("Approve", fontSize = 11.sp, color = colors.signalText)
                    }
                }
            }
        }

        // Governance Policy Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(radii.card))
                .background(colors.raised)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = isApprovalRequired,
                    onCheckedChange = { onToggleApproval(record.toolName, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.warn,
                        checkedTrackColor = colors.warn.copy(alpha = 0.4f),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Require Human Approval",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Agent must get confirmation before calling this tool",
                        fontSize = 10.sp,
                        color = colors.textSecondary,
                    )
                }
            }

            OutlinedButton(
                onClick = { onDisableTool(record.toolName) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.alert),
                shape = RoundedCornerShape(radii.button),
            ) {
                Text("Disable Tool (Kill Switch)", fontSize = 11.sp)
            }
        }

        // Replay Scratchpad Area
        if (isReplayMode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(radii.card))
                    .background(colors.raised)
                    .border(1.dp, colors.signal.copy(alpha = 0.5f), RoundedCornerShape(radii.card))
                    .padding(12.dp),
            ) {
                Text(
                    text = "🛠️ Instant Replay Scratchpad",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = colors.signal,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = replayArgs,
                    onValueChange = onReplayArgsChanged,
                    label = { Text("Arguments (JSON)", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onExecuteReplay,
                    enabled = !isReplaying,
                    colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
                    shape = RoundedCornerShape(radii.button),
                ) {
                    Text(if (isReplaying) "Executing..." else "Execute Replay Now", fontSize = 11.sp, color = colors.onSignal)
                }

                if (replayResult != null) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = "Result:\n$replayResult",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = colors.ok,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.panel)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }

        // Arguments Card
        Column {
            Text(
                text = "Tool Arguments",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = record.arguments.ifBlank { "{}" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(radii.card))
                        .background(colors.raised)
                        .padding(10.dp),
                )
            }
        }

        // Output / Error Payload Card
        Column {
            Text(
                text = if (record.errorMessage != null) "Execution Error / Output" else "Result Payload",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (record.errorMessage != null) colors.alert else colors.textSecondary,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = record.errorMessage ?: record.resultPayload ?: "(No output payload)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (record.errorMessage != null) colors.alert else colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(radii.card))
                        .background(colors.raised)
                        .padding(10.dp),
                )
            }
        }
    }
}
