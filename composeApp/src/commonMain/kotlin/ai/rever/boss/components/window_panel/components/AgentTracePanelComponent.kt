@file:Suppress("PackageNaming")

package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.observability.AgentTraceStore
import ai.rever.boss.components.observability.McpTraceEvent
import ai.rever.boss.components.observability.TraceStatus
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
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
import com.arkivanov.decompose.ComponentContext
import kotlinx.datetime.Instant

class AgentTracePanelComponent(
    componentContext: ComponentContext,
    override val panelInfo: PanelInfo,
) : PanelComponentWithUI,
    ComponentContext by componentContext {
    @Composable
    override fun Content() {
        val modifier = Modifier
        val events by AgentTraceStore.events.collectAsState()
        var selectedEventId by remember { mutableStateOf<String?>(null) }
        val selectedEvent = events.find { it.id == selectedEventId }

        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(BossTheme.colors.panel),
        ) {
            // Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        AgentTraceStore.clear()
                        selectedEventId = null
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.raised,
                            contentColor = BossTheme.colors.textPrimary,
                        ),
                ) {
                    Text("Clear")
                }
            }
            Divider(color = BossTheme.colors.line)

            Row(modifier = Modifier.fillMaxSize()) {
                TraceList(
                    events = events,
                    selectedEventId = selectedEventId,
                    onEventSelected = { selectedEventId = it },
                    modifier = Modifier.weight(0.4f),
                )

                Divider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = BossTheme.colors.line,
                )

                TraceDetail(
                    selectedEvent = selectedEvent,
                    modifier = Modifier.weight(0.6f),
                )
            }
        }
    }

    @Composable
    private fun TraceList(
        events: List<McpTraceEvent>,
        selectedEventId: String?,
        onEventSelected: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxHeight()
                    .padding(end = 8.dp),
        ) {
            if (events.isEmpty()) {
                item {
                    Text(
                        "No MCP calls yet",
                        color = BossTheme.colors.textSecondary,
                        style = BossTheme.type.body,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(events, key = { it.id }) { event ->
                val isSelected = event.id == selectedEventId
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEventSelected(event.id) }
                            .background(
                                if (isSelected) {
                                    BossTheme.colors.signalWash
                                } else {
                                    Color.Transparent
                                },
                            ).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TraceStatusIcon(event.status)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            event.toolName,
                            style = BossTheme.type.body,
                            color = BossTheme.colors.textPrimary,
                        )
                        val durationText = event.durationMs?.let { "${it}ms" } ?: "..."
                        Text(
                            durationText,
                            style = BossTheme.type.body,
                            color = BossTheme.colors.textSecondary,
                        )
                    }
                }
                Divider(color = BossTheme.colors.line, thickness = 0.5.dp)
            }
        }
    }

    @Composable
    private fun TraceStatusIcon(status: TraceStatus) {
        val icon =
            when (status) {
                TraceStatus.RUNNING -> Icons.Default.HourglassEmpty
                TraceStatus.SUCCESS -> Icons.Default.CheckCircle
                TraceStatus.FAILURE -> Icons.Default.Error
                TraceStatus.TIMEOUT -> Icons.Default.Block
                TraceStatus.CANCELLED -> Icons.Default.Cancel
            }
        val color =
            when (status) {
                TraceStatus.RUNNING -> BossTheme.colors.signalText
                TraceStatus.SUCCESS -> BossTheme.colors.ok
                TraceStatus.FAILURE, TraceStatus.TIMEOUT -> BossTheme.colors.alert
                TraceStatus.CANCELLED -> BossTheme.colors.textMuted
            }
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
    }

    @Composable
    @Suppress("LongMethod")
    private fun TraceDetail(
        selectedEvent: McpTraceEvent?,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier =
                modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            if (selectedEvent != null) {
                DetailSection("Tool", selectedEvent.toolName)
                DetailSection("Status", selectedEvent.status.name)
                DetailSection("Started At", Instant.fromEpochMilliseconds(selectedEvent.startedAtMs).toString())
                if (selectedEvent.durationMs != null) {
                    DetailSection("Duration", "${selectedEvent.durationMs}ms")
                }

                Text(
                    "Arguments",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    color = BossTheme.colors.textPrimary,
                )
                SelectionContainer {
                    Text(
                        text = selectedEvent.argumentsJson,
                        fontFamily = FontFamily.Monospace,
                        style = BossTheme.type.body,
                        color = BossTheme.colors.textSecondary,
                    )
                }

                if (selectedEvent.resultJson != null) {
                    Text(
                        "Result",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        color = BossTheme.colors.textPrimary,
                    )
                    SelectionContainer {
                        Text(
                            text = selectedEvent.resultJson!!,
                            fontFamily = FontFamily.Monospace,
                            style = BossTheme.type.body,
                            color = BossTheme.colors.ok,
                        )
                    }
                }

                if (selectedEvent.errorMessage != null) {
                    Text(
                        "Error",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        color = BossTheme.colors.textPrimary,
                    )
                    SelectionContainer {
                        Text(
                            text = selectedEvent.errorMessage!!,
                            fontFamily = FontFamily.Monospace,
                            style = BossTheme.type.body,
                            color = BossTheme.colors.alert,
                        )
                    }
                }
            } else {
                Text(
                    "Select a trace event to view details",
                    color = BossTheme.colors.textSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }

    @Composable
    private fun DetailSection(
        label: String,
        value: String,
    ) {
        Row(modifier = Modifier.padding(bottom = 4.dp)) {
            Text("$label: ", fontWeight = FontWeight.Bold, color = BossTheme.colors.textPrimary)
            SelectionContainer {
                Text(value, color = BossTheme.colors.textPrimary)
            }
        }
    }
}
