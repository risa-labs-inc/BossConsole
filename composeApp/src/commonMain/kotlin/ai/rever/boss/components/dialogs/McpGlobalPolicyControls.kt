package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.McpSectionPolicyChange
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
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun McpGlobalPolicyControls(
    tools: List<McpToolIdentity>,
    rules: Map<String, McpPolicyAction>,
    onApply: suspend (List<McpSectionPolicyChange>) -> McpProactivePolicyOutcome,
    onRefresh: () -> Unit,
) {
    val colors = BossTheme.colors
    var selection by remember(tools, rules) { mutableStateOf<McpSectionMode?>(null) }
    var saving by remember { mutableStateOf(false) }
    val presets =
        listOf(
            "All" to McpSectionMode.All,
            "View" to McpSectionMode.View,
            "Edit" to McpSectionMode.Edit,
            "None" to McpSectionMode.None,
        )
    Surface(
        color = colors.textSecondary.copy(alpha = 0.04f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("All sections", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(
                "Applies to all ${tools.size} tools, including sections hidden by search. " +
                    "View allows read-only tools; Edit allows actions and sensitive tools; None denies all tools.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
            Column(Modifier.selectableGroup()) {
                presets.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (label, mode) ->
                            GlobalPreset(
                                label,
                                selection == mode,
                                !saving && tools.isNotEmpty(),
                                Modifier.weight(1f),
                            ) { selection = mode }
                        }
                    }
                }
            }
            SectionConfirmation(
                tools,
                rules,
                selection?.let { sectionSelection(tools, it) }.orEmpty(),
                selection != null,
                onApply,
                onRefresh,
                onSaving = { saving = it },
                onDone = { selection = null },
                confirmLabel = "Confirm all sections",
            )
        }
    }
}

@Composable
private fun GlobalPreset(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val colors = BossTheme.colors
    Row(
        modifier
            .selectable(selected, enabled, Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = "$label for all sections" }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RadioButton(
            selected,
            null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = colors.signal, unselectedColor = colors.textSecondary),
        )
        Text(label, fontSize = 13.sp, color = colors.textPrimary)
    }
}
