package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.SandboxState
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.auth.AuthStateManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val healthLogger = BossLogger.forComponent("PluginHealthCenter")

/** A lifecycle status presented by the host without duplicating plugin state. */
internal enum class PluginHealthStatus {
    HEALTHY,
    NEEDS_ATTENTION,
    UNAVAILABLE,
}

/** Existing, deliberately narrow lifecycle operations that the center may invoke. */
internal enum class PluginHealthAction {
    ENABLE,
    RELOAD,
}

internal data class PluginHealthRow(
    val pluginId: String,
    val displayName: String,
    val status: PluginHealthStatus,
    val detail: String,
    val action: PluginHealthAction? = null,
)

internal class PluginHealthOperationState {
    var workingPluginId by mutableStateOf<String?>(null)
    var actionError by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberHealthOperation(): PluginHealthOperationState = remember { PluginHealthOperationState() }

/** Host-owned operational surface for plugin status and existing safe recovery actions. */
@Composable
internal fun PluginHealthCenterDialog(
    manager: DynamicPluginManager?,
    delegate: PluginLoaderDelegate?,
    onDismiss: () -> Unit,
) {
    if (manager == null) {
        BossDialog(onDismissRequest = onDismiss) {
            Column(Modifier.padding(20.dp)) {
                Text("Plugin management is not available in this window yet.")
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
        return
    }
    val rows = observeHealthRows(manager)
    val scope = rememberCoroutineScope()
    val operation = rememberHealthOperation()

    BossDialog(
        onDismissRequest = { if (operation.workingPluginId == null) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = operation.workingPluginId == null,
                dismissOnClickOutside = operation.workingPluginId == null,
                usePlatformDefaultWidth = false,
            ),
    ) {
        PluginHealthCenterCard(
            rows = rows,
            actionError = operation.actionError,
            workingPluginId = operation.workingPluginId,
            onDismiss = onDismiss,
            onAction = { row, action ->
                if (operation.workingPluginId == null) {
                    operation.workingPluginId = row.pluginId
                    operation.actionError = null
                    scope.launchHealthAction(
                        manager = manager,
                        delegate = delegate,
                        row = row,
                        action = action,
                        onFinished = { error ->
                            operation.actionError = error
                            operation.workingPluginId = null
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun observeHealthRows(manager: DynamicPluginManager): List<PluginHealthRow> {
    val pluginStates by manager.pluginStates.collectAsState()
    val gates by PluginLoadGateRegistry.gates.collectAsState()
    val crashedPluginIds = PluginCrashRegistry.crashedPlugins.keys
    val user by AuthStateManager.currentUser.collectAsState()
    val inaccessible =
        healthInaccessiblePluginIds(pluginStates, user?.isAdmin == true, user?.permissions?.toSet() ?: emptySet())
    val incompatible by PluginCrashRegistry.incompatiblePlugins.collectAsState()
    val sandboxDisabled = mutableSetOf<String>()
    for (id in pluginStates.keys) {
        key(id) {
            val state =
                manager.sandboxManager
                    .getSandbox(id)
                    ?.state
                    ?.collectAsState()
                    ?.value
            if (state == SandboxState.DISABLED) sandboxDisabled.add(id)
        }
    }
    return healthRowsWithSandboxDisables(
        pluginHealthRows(pluginStates, gates, crashedPluginIds, inaccessible, incompatible),
        sandboxDisabled,
    )
}

@Composable
private fun PluginHealthCenterCard(
    rows: List<PluginHealthRow>,
    actionError: String?,
    workingPluginId: String?,
    onDismiss: () -> Unit,
    onAction: (PluginHealthRow, PluginHealthAction) -> Unit,
) {
    Card(
        modifier = Modifier.width(560.dp),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = BossTheme.colors.panel,
        elevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            PluginHealthHeader(actionError)
            Spacer(Modifier.height(14.dp))
            PluginHealthRows(rows, workingPluginId, onAction)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = workingPluginId == null) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun PluginHealthHeader(actionError: String?) {
    Text(
        text = "Plugin Health & Recovery",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = BossTheme.colors.textPrimary,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "See why a plugin is unavailable and recover it when possible.",
        color = BossTheme.colors.textSecondary,
        fontSize = 13.sp,
    )
    if (actionError != null) {
        Spacer(Modifier.height(10.dp))
        Text(actionError, color = BossTheme.colors.alert, fontSize = 13.sp)
    }
}

@Composable
private fun PluginHealthRows(
    rows: List<PluginHealthRow>,
    workingPluginId: String?,
    onAction: (PluginHealthRow, PluginHealthAction) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            text = "No plugins are currently registered in this window.",
            color = BossTheme.colors.textSecondary,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.height(320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.pluginId }) { row ->
            PluginHealthRowCard(
                row = row,
                working = workingPluginId == row.pluginId,
                actionsEnabled = workingPluginId == null,
                onAction = { action -> onAction(row, action) },
            )
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchHealthAction(
    manager: DynamicPluginManager,
    delegate: PluginLoaderDelegate?,
    row: PluginHealthRow,
    action: PluginHealthAction,
    onFinished: (String?) -> Unit,
) {
    launch {
        healthLogger.info(
            LogCategory.SYSTEM,
            "Plugin health recovery requested",
            mapOf("pluginId" to row.pluginId, "action" to action.name),
        )
        if (currentHealthAction(manager, row.pluginId) != action) {
            healthLogger.info(
                LogCategory.SYSTEM,
                "Plugin health changed before recovery",
                mapOf("pluginId" to row.pluginId),
            )
            onFinished("Plugin state changed. Review its current status and try again.")
            return@launch
        }
        if (delegate == null) {
            healthLogger.warn(LogCategory.SYSTEM, "Plugin health recovery delegate unavailable")
            onFinished("Plugin recovery is not available in this window yet.")
            return@launch
        }
        val succeeded =
            runCatching {
                // The delegate persists Enable and coordinates reload teardown and refresh.
                // Reload's uninstall already clears the old crash and quarantine markers.
                when (action) {
                    PluginHealthAction.ENABLE -> delegate.enablePlugin(row.pluginId)
                    PluginHealthAction.RELOAD -> delegate.reloadPlugin(row.pluginId) != null
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                healthLogger.warn(
                    LogCategory.SYSTEM,
                    "Plugin health recovery failed",
                    mapOf("pluginId" to row.pluginId, "errorType" to it.javaClass.simpleName),
                )
                false
            }
        val verb = if (action == PluginHealthAction.ENABLE) "enable" else "reload"
        onFinished(if (succeeded) null else "Could not $verb this plugin. Check BOSS logs for details.")
    }
}

/** Re-check action eligibility immediately before a user action can mutate plugin lifecycle. */
private fun currentHealthAction(
    manager: DynamicPluginManager,
    pluginId: String,
): PluginHealthAction? {
    val states = manager.pluginStates.value
    val user = AuthStateManager.currentUser.value
    return healthRowsWithSandboxDisables(
        pluginHealthRows(
            pluginStates = states,
            loadGates = PluginLoadGateRegistry.gates.value,
            crashedPluginIds = states.keys.filterTo(mutableSetOf()) { PluginCrashRegistry.hasCrashed(it) },
            inaccessiblePluginIds =
                healthInaccessiblePluginIds(states, user?.isAdmin == true, user?.permissions?.toSet() ?: emptySet()),
            incompatiblePluginIds = PluginCrashRegistry.incompatiblePlugins.value,
        ),
        states.keys.filterTo(mutableSetOf()) { manager.sandboxManager.isPluginDisabled(it) },
    ).firstOrNull { it.pluginId == pluginId }?.action
}

@Composable
private fun PluginHealthRowCard(
    row: PluginHealthRow,
    working: Boolean,
    actionsEnabled: Boolean,
    onAction: (PluginHealthAction) -> Unit,
) {
    Card(backgroundColor = BossTheme.colors.raised, elevation = 0.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.displayName,
                    fontWeight = FontWeight.Medium,
                    color = BossTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.pluginId,
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${row.status.label()} · ${row.detail}",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.action?.let { action ->
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onAction(action) },
                    enabled = actionsEnabled,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossTheme.colors.signal),
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (action == PluginHealthAction.ENABLE) "Enable" else "Reload")
                    }
                }
            }
        }
    }
}

private fun PluginHealthStatus.label(): String =
    when (this) {
        PluginHealthStatus.HEALTHY -> "Healthy"
        PluginHealthStatus.NEEDS_ATTENTION -> "Needs attention"
        PluginHealthStatus.UNAVAILABLE -> "Unavailable"
    }
