package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.Panel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search

object AgentTracePanelInfo : PanelInfo {
    override val id = PanelIds.AGENT_TRACE
    override val displayName = "Agent Trace"
    override val icon = Icons.Outlined.Search
    override val defaultSlotPosition = Panel.bottom
}
