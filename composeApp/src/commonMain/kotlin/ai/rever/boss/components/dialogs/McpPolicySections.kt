package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.McpSectionPolicyChange
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.ruleFor
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * A View-bucket tool: not classified mutating by [McpMutatingToolCatalog.isMutating], which
 * weighs the provider's own [McpToolIdentity.readOnly] declaration alongside the name (#804),
 * and below HIGH risk. The catalog is the single classification point - this does not
 * re-derive mutating status from the declaration separately, so the two can never diverge.
 */
internal fun McpToolIdentity.isViewTool(): Boolean =
    !McpMutatingToolCatalog.isMutating(toolName, readOnly) && policyRisk(this).level < McpRiskLevel.HIGH

internal fun sectionSelection(
    tools: List<McpToolIdentity>,
    mode: McpSectionMode,
): Set<McpToolKey> =
    tools
        .filter {
            when (mode) {
                McpSectionMode.All -> true
                McpSectionMode.View -> it.isViewTool()
                McpSectionMode.Edit -> !it.isViewTool()
                McpSectionMode.Custom, McpSectionMode.None -> false
            }
        }.map { it.key }
        .toSet()

@Composable
internal fun McpPolicySections(
    tools: List<McpToolIdentity>,
    policy: McpToolPolicyConfig,
    query: String,
    onApply: suspend (List<McpSectionPolicyChange>) -> McpProactivePolicyOutcome,
    onRefresh: () -> Unit,
) {
    val pluginNames = mcpPolicyPluginNames()
    val groups =
        tools.groupBy { it.providerId }.filter { (provider, members) ->
            policySectionName(provider, pluginNames).contains(query.trim(), true) ||
                provider.contains(query.trim(), true) ||
                members.any {
                    it.toolName.contains(query.trim(), true) || it.description.contains(query.trim(), true)
                }
        }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Presets allow selected tools and deny the rest. " +
                "Search keeps matching sections complete.",
            color = BossTheme.colors.textSecondary,
            fontSize = 12.sp,
        )
        McpGlobalPolicyControls(tools, policy, onApply, onRefresh)
        groups.forEach { (provider, members) ->
            key(provider) {
                McpPolicySection(
                    policySectionName(provider, pluginNames),
                    members,
                    policy,
                    onApply,
                    onRefresh,
                )
            }
        }
        if (groups.isEmpty()) Text("No matching sections.", color = BossTheme.colors.textSecondary)
    }
}

@Composable
private fun McpPolicySection(
    provider: String,
    tools: List<McpToolIdentity>,
    policy: McpToolPolicyConfig,
    onApply: suspend (List<McpSectionPolicyChange>) -> McpProactivePolicyOutcome,
    onRefresh: () -> Unit,
) {
    val colors = BossTheme.colors
    val initial =
        tools
            .filter { policy.ruleFor(it.toolName, it.providerId) == McpPolicyAction.ALLOW }
            .map { it.key }
            .toSet()
    var selected by remember(tools, policy) { mutableStateOf(initial) }
    var mode by remember(tools, policy) { mutableStateOf(savedSectionMode(tools, policy)) }
    var dirty by remember(tools, policy) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.textSecondary.copy(alpha = 0.035f),
        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.14f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(provider, tools.size, selected.size, !saving) {
                selected = if (it) tools.map { tool -> tool.key }.toSet() else emptySet()
                mode = if (it) McpSectionMode.All else McpSectionMode.None
                dirty = true
            }
            SectionModes(mode, !saving) {
                mode = it
                if (it != McpSectionMode.Custom) selected = sectionSelection(tools, it)
                expanded = it == McpSectionMode.Custom
                dirty = true
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide tools" else "Review tools", color = colors.signal, fontSize = 12.sp)
            }
            if (expanded) {
                tools.forEach { tool ->
                    val currentRule = policy.ruleFor(tool.toolName, tool.providerId)
                    SectionToolRow(tool, currentRule, tool.key in selected, !saving) { checked ->
                        selected = if (checked) selected + tool.key else selected - tool.key
                        mode = McpSectionMode.Custom
                        dirty = true
                    }
                }
            }
            SectionConfirmation(
                tools,
                policy,
                selected,
                dirty,
                onApply,
                onRefresh,
                onSaving = { saving = it },
                onDone = { dirty = false },
            )
        }
    }
}

@Composable
private fun SectionModes(
    selected: McpSectionMode,
    enabled: Boolean,
    onSelect: (McpSectionMode) -> Unit,
) {
    val colors = BossTheme.colors
    Column(Modifier.selectableGroup()) {
        // Two rows also fit narrow windows without clipping the labels.
        listOf(McpSectionMode.All, McpSectionMode.View, McpSectionMode.Edit, McpSectionMode.Custom)
            .chunked(2)
            .forEach { modes ->
                Row(Modifier.fillMaxWidth()) {
                    modes.forEach { mode ->
                        Row(
                            Modifier
                                .weight(1f)
                                .selectable(selected == mode, enabled, Role.RadioButton) { onSelect(mode) }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RadioButton(
                                selected == mode,
                                null,
                                enabled = enabled,
                                colors =
                                    RadioButtonDefaults.colors(
                                        selectedColor = colors.signal,
                                        unselectedColor = colors.textSecondary,
                                    ),
                            )
                            Text(mode.name, fontSize = 13.sp, color = colors.textPrimary)
                        }
                    }
                }
            }
    }
}

@Composable
private fun SectionToolRow(
    tool: McpToolIdentity,
    currentRule: McpPolicyAction?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val colors = BossTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(
            selected,
            onSelect,
            modifier = Modifier.semantics { contentDescription = "Allow ${tool.toolName}" },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = colors.signal),
        )
        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tool.toolName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(savedPolicyLabel(currentRule), fontSize = 12.sp, color = colors.textSecondary)
            Text(
                if (tool.isViewTool()) "View · read-only" else "Edit · actions or sensitive access",
                fontSize = 11.sp,
                color = colors.textSecondary,
            )
            Text(
                tool.description.ifBlank { "No description provided by this tool." },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.textSecondary,
            )
        }
    }
}

internal fun policyToolsHeading(
    sections: List<McpToolIdentity>?,
    available: List<McpToolIdentity>,
): String = if (sections == null) "Available tools · ${available.size}" else "Tool sections · ${sections.size} tools"

internal fun policyToolsDescription(sections: List<McpToolIdentity>?): String =
    if (sections == null) {
        "Choose Allow or Deny for a tool without a saved rule. " +
            "Each rule is saved for the plugin that provides the tool."
    } else {
        "Enable a section or choose All, View, Edit, or Custom. Changes require confirmation."
    }

@Composable
private fun SectionHeader(
    provider: String,
    count: Int,
    selectedCount: Int,
    enabled: Boolean,
    onEnable: (Boolean) -> Unit,
) {
    val colors = BossTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(provider, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text("$count tools · $selectedCount selected", fontSize = 12.sp, color = colors.textSecondary)
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = "Enable $provider section" },
            checked = selectedCount > 0,
            onCheckedChange = onEnable,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = colors.signal),
        )
    }
}
