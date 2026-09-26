package ai.rever.boss.components.plugin

import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.mcp.sentinel.FindingSeverity
import ai.rever.boss.mcp.sentinel.SentinelTrustState
import ai.rever.boss.mcp.sentinel.ToolEvaluationResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose Multiplatform panel for MCP Sentinel (ToolDNA Integrity & Poisoning Protection).
 */
@Composable
fun McpSentinelPanel(modifier: Modifier = Modifier) {
    val sentinelEngine = McpToolRegistryImpl.sentinelEngine
    val registeredTools by McpToolRegistryImpl.allTools.collectAsState()
    val evaluations by sentinelEngine.evaluations.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterState by remember { mutableStateOf<SentinelTrustState?>(null) }
    var selectedToolKey by remember { mutableStateOf<String?>(null) }

    val evalList = remember(evaluations, registeredTools) { evaluations.values.toList() }
    val filteredList =
        remember(evalList, searchQuery, selectedFilterState) {
            evalList.filter { eval ->
                val matchesQuery =
                    searchQuery.isBlank() ||
                        eval.toolName.contains(searchQuery, ignoreCase = true) ||
                        eval.providerId.contains(searchQuery, ignoreCase = true)
                val matchesFilter = selectedFilterState == null || eval.trustState == selectedFilterState
                matchesQuery && matchesFilter
            }
        }
    val selectedEval = selectedToolKey?.let { key -> evaluations[key] }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF1E1E2E)).padding(16.dp),
    ) {
        SentinelPanelHeader(onRefresh = { sentinelEngine.evaluateAll(registeredTools) })
        Spacer(modifier = Modifier.height(16.dp))
        OverviewMetricsRow(evalList = evalList)
        Spacer(modifier = Modifier.height(16.dp))

        SentinelPanelBody(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            selectedFilterState = selectedFilterState,
            onSelectFilterState = { selectedFilterState = it },
            evalList = evalList,
            filteredList = filteredList,
            selectedToolKey = selectedToolKey,
            onSelectTool = { selectedToolKey = it },
            selectedEval = selectedEval,
            onApprove = { eval ->
                sentinelEngine.approveAndTrustTool(
                    providerId = eval.providerId,
                    toolName = eval.toolName,
                    reviewedFingerprint = eval.currentFingerprint.fingerprint,
                )
                sentinelEngine.evaluateAll(registeredTools)
            },
            onBlock = { eval ->
                sentinelEngine.blockTool(eval.providerId, eval.toolName)
                sentinelEngine.evaluateAll(registeredTools)
            },
            onUnblock = { eval ->
                sentinelEngine.unblockTool(eval.providerId, eval.toolName)
                sentinelEngine.evaluateAll(registeredTools)
            },
        )
    }
}

@Composable
private fun SentinelPanelHeader(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "MCP Sentinel",
                tint = Color(0xFF89B4FA),
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "MCP Sentinel - ToolDNA Integrity",
                    style =
                        MaterialTheme.typography.h6.copy(
                            color = Color(0xFFCDD6F4),
                            fontWeight = FontWeight.Bold,
                        ),
                )
                Text(
                    text = "Content-aware & change-aware MCP tool poisoning & rug pull protection",
                    style = MaterialTheme.typography.caption.copy(color = Color(0xFFA6ADC8)),
                )
            }
        }

        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Re-evaluate",
                tint = Color(0xFF89B4FA),
            )
        }
    }
}

@Composable
private fun SentinelPanelBody(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilterState: SentinelTrustState?,
    onSelectFilterState: (SentinelTrustState?) -> Unit,
    evalList: List<ToolEvaluationResult>,
    filteredList: List<ToolEvaluationResult>,
    selectedToolKey: String?,
    onSelectTool: (String) -> Unit,
    selectedEval: ToolEvaluationResult?,
    onApprove: (ToolEvaluationResult) -> Unit,
    onBlock: (ToolEvaluationResult) -> Unit,
    onUnblock: (ToolEvaluationResult) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Filter tools or providers...", color = Color(0xFF6C7086)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilterTabRow(
                selectedState = selectedFilterState,
                onSelectState = onSelectFilterState,
                totalCount = evalList.size,
                evalList = evalList,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredList, key = { "${it.providerId}/${it.toolName}" }) { eval ->
                    val key = "${eval.providerId}/${eval.toolName}"
                    ToolCardItem(
                        eval = eval,
                        isSelected = selectedToolKey == key,
                        onSelect = { onSelectTool(key) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (selectedEval != null) {
            Spacer(modifier = Modifier.width(16.dp))
            ToolInspectorDrawer(
                eval = selectedEval,
                onClose = { onSelectTool("") },
                onApprove = { onApprove(selectedEval) },
                onBlock = { onBlock(selectedEval) },
                onUnblock = { onUnblock(selectedEval) },
            )
        }
    }
}

@Composable
private fun OverviewMetricsRow(evalList: List<ToolEvaluationResult>) {
    val total = evalList.size
    val trusted = evalList.count { it.trustState == SentinelTrustState.TRUSTED }
    val newCount = evalList.count { it.trustState == SentinelTrustState.NEW }
    val changed = evalList.count { it.trustState == SentinelTrustState.CHANGED }
    val suspicious =
        evalList.count {
            it.trustState == SentinelTrustState.SUSPICIOUS || it.trustState == SentinelTrustState.REVIEW_REQUIRED
        }
    val blocked = evalList.count { it.trustState == SentinelTrustState.BLOCKED }

    val cards =
        listOf(
            "Monitored" to (total to Color(0xFF89B4FA)),
            "Trusted" to (trusted to Color(0xFFA6E3A1)),
            "New" to (newCount to Color(0xFF89DCEB)),
            "Changed" to (changed to Color(0xFFFAB387)),
            "Suspicious" to (suspicious to Color(0xFFF38BA8)),
            "Blocked" to (blocked to Color(0xFF6C7086)),
        )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((title, pair) in cards) {
            val (count, color) = pair
            Card(
                modifier = Modifier.weight(1f),
                backgroundColor = Color(0xFF313244),
                shape = RoundedCornerShape(8.dp),
                elevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = title, style = MaterialTheme.typography.caption.copy(color = Color(0xFFA6ADC8)))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.h5.copy(color = color, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterTabRow(
    selectedState: SentinelTrustState?,
    onSelectState: (SentinelTrustState?) -> Unit,
    totalCount: Int,
    evalList: List<ToolEvaluationResult>,
) {
    val cntChanged = evalList.count { it.trustState == SentinelTrustState.CHANGED }
    val cntSuspicious = evalList.count { it.trustState == SentinelTrustState.SUSPICIOUS }
    val cntTrusted = evalList.count { it.trustState == SentinelTrustState.TRUSTED }
    val cntBlocked = evalList.count { it.trustState == SentinelTrustState.BLOCKED }

    val filterItems =
        listOf(
            "All ($totalCount)" to null,
            "Changed ($cntChanged)" to SentinelTrustState.CHANGED,
            "Suspicious ($cntSuspicious)" to SentinelTrustState.SUSPICIOUS,
            "Trusted ($cntTrusted)" to SentinelTrustState.TRUSTED,
            "Blocked ($cntBlocked)" to SentinelTrustState.BLOCKED,
        )

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, state) in filterItems) {
            val isSelected = selectedState == state
            Box(
                modifier =
                    Modifier
                        .background(
                            color = if (isSelected) Color(0xFF89B4FA) else Color(0xFF313244),
                            shape = RoundedCornerShape(16.dp),
                        ).clickable(onClick = { onSelectState(state) })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.caption.copy(
                            color = if (isSelected) Color(0xFF11111B) else Color(0xFFCDD6F4),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ToolCardItem(
    eval: ToolEvaluationResult,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val borderColor = if (isSelected) Color(0xFF89B4FA) else Color.Transparent

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .clickable(onClick = onSelect),
        backgroundColor = Color(0xFF313244),
        shape = RoundedCornerShape(8.dp),
        elevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = eval.toolName,
                        style =
                            MaterialTheme.typography.subtitle1.copy(
                                color = Color(0xFFCDD6F4),
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "from ${eval.providerId}",
                        style = MaterialTheme.typography.caption.copy(color = Color(0xFFA6ADC8)),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ToolDNA: ${eval.currentFingerprint.fingerprint.take(16)}...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF9399B2),
                )
                if (eval.securityFindings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠️ ${eval.securityFindings.size} security finding(s)",
                        style = MaterialTheme.typography.caption.copy(color = Color(0xFFF38BA8)),
                    )
                }
            }

            StateBadge(state = eval.trustState)
        }
    }
}

@Composable
private fun StateBadge(state: SentinelTrustState) {
    val (bgColor, textColor, label) =
        when (state) {
            SentinelTrustState.TRUSTED -> Triple(Color(0x33A6E3A1), Color(0xFFA6E3A1), "TRUSTED")
            SentinelTrustState.NEW -> Triple(Color(0x3389DCEB), Color(0xFF89DCEB), "NEW")
            SentinelTrustState.CHANGED -> Triple(Color(0x33FAB387), Color(0xFFFAB387), "CHANGED")
            SentinelTrustState.SUSPICIOUS -> Triple(Color(0x33F38BA8), Color(0xFFF38BA8), "SUSPICIOUS")
            SentinelTrustState.REVIEW_REQUIRED -> Triple(Color(0x33F9E2AF), Color(0xFFF9E2AF), "REVIEW REQ")
            SentinelTrustState.BLOCKED -> Triple(Color(0x336C7086), Color(0xFF6C7086), "BLOCKED")
            SentinelTrustState.UNKNOWN -> Triple(Color(0x339399B2), Color(0xFF9399B2), "UNKNOWN")
        }

    Box(
        modifier =
            Modifier
                .background(bgColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style =
                MaterialTheme.typography.caption.copy(
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                ),
        )
    }
}

@Composable
private fun ToolInspectorDrawer(
    eval: ToolEvaluationResult,
    onClose: () -> Unit,
    onApprove: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
) {
    Card(
        modifier = Modifier.width(420.dp).fillMaxSize(),
        backgroundColor = Color(0xFF181825),
        shape = RoundedCornerShape(8.dp),
        elevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tool Inspector: ${eval.toolName}",
                    style =
                        MaterialTheme.typography.subtitle1.copy(
                            color = Color(0xFFCDD6F4),
                            fontWeight = FontWeight.Bold,
                        ),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFA6ADC8))
                }
            }
            Divider(color = Color(0xFF313244), modifier = Modifier.padding(vertical = 8.dp))
            ToolInspectorBody(eval = eval, modifier = Modifier.weight(1f))
            Divider(color = Color(0xFF313244), modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (eval.trustState == SentinelTrustState.BLOCKED) {
                    OutlinedButton(onClick = onUnblock, modifier = Modifier.weight(1f)) {
                        Text("Unblock Tool", color = Color(0xFF89B4FA))
                    }
                } else {
                    OutlinedButton(onClick = onBlock, modifier = Modifier.weight(1f)) {
                        Text("Block Tool", color = Color(0xFFF38BA8))
                    }
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFA6E3A1)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Accept Baseline", color = Color(0xFF11111B), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ToolInspectorBody(
    eval: ToolEvaluationResult,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        item {
            Text("Identity & Fingerprint", style = MaterialTheme.typography.subtitle2.copy(color = Color(0xFF89B4FA)))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Provider: ${eval.providerId}",
                style = MaterialTheme.typography.caption.copy(color = Color(0xFFCDD6F4)),
            )
            Text(
                "Current Fingerprint: ${eval.currentFingerprint.fingerprint}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFA6ADC8),
            )
            eval.baselineRecord?.let {
                Text(
                    "Baseline Fingerprint: ${it.canonicalFingerprint}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFA6ADC8),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        SentinelDrawerHelper.renderDiffAndSecurityItems(this, eval)
    }
}

private object SentinelDrawerHelper {
    fun renderDiffAndSecurityItems(
        scope: androidx.compose.foundation.lazy.LazyListScope,
        eval: ToolEvaluationResult,
    ) {
        if (eval.diffResult != null && eval.diffResult.hasChanges) {
            scope.item {
                Text(
                    "Semantic Diff Findings",
                    style = MaterialTheme.typography.subtitle2.copy(color = Color(0xFFFAB387)),
                )
                Spacer(modifier = Modifier.height(4.dp))
                for (detail in eval.diffResult.diffDetails) {
                    Text(
                        "• ${detail.explanation}",
                        style = MaterialTheme.typography.caption.copy(color = Color(0xFFCDD6F4)),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (eval.securityFindings.isNotEmpty()) {
            scope.item {
                Text(
                    "Security Findings (${eval.securityFindings.size})",
                    style = MaterialTheme.typography.subtitle2.copy(color = Color(0xFFF38BA8)),
                )
                Spacer(modifier = Modifier.height(4.dp))
                for (finding in eval.securityFindings) {
                    FindingItem(finding = finding)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (eval.shadowingFindings.isNotEmpty()) {
            scope.item {
                Text(
                    "Cross-Server Collisions",
                    style = MaterialTheme.typography.subtitle2.copy(color = Color(0xFFF9E2AF)),
                )
                Spacer(modifier = Modifier.height(4.dp))
                for (shadowing in eval.shadowingFindings) {
                    Text(
                        "• ${shadowing.explanation}",
                        style = MaterialTheme.typography.caption.copy(color = Color(0xFFCDD6F4)),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    @Composable
    fun FindingItem(finding: ai.rever.boss.mcp.sentinel.SecurityFinding) {
        val color =
            when (finding.severity) {
                FindingSeverity.CRITICAL, FindingSeverity.HIGH -> Color(0xFFF38BA8)
                FindingSeverity.MEDIUM -> Color(0xFFFAB387)
                FindingSeverity.LOW, FindingSeverity.INFO -> Color(0xFF89B4FA)
            }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF313244), RoundedCornerShape(4.dp))
                    .padding(8.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "[${finding.ruleId}] ${finding.severity.name}",
                        style = MaterialTheme.typography.caption.copy(color = color, fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "at ${finding.location}",
                        style = MaterialTheme.typography.caption.copy(color = Color(0xFFA6ADC8)),
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = finding.explanation,
                    style = MaterialTheme.typography.caption.copy(color = Color(0xFFCDD6F4)),
                )
            }
        }
    }
}
