package ai.rever.boss.filetypes

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.DefaultHandlerState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("DefaultAppsOffer")

/**
 * Offers, once, to make BOSS the default browser. File defaults stay in Settings.
 *
 * **Three things it deliberately does not do.**
 *
 * It does not claim anything without a click. Taking over a machine's file
 * associations on first launch is the behaviour that makes people distrust
 * browsers. Only web links are offered; file categories are never preselected or claimed here.
 *
 * It does not ask twice. `promptShown` is persisted the moment the dialog
 * appears, not when it is answered - so a crash, a force quit or a window closed
 * with the dialog up all count as asked. Being asked at every launch is worse
 * than never discovering the setting, and Settings > Default Apps is always
 * there.
 *
 * It does not appear when there is nothing to offer: no categories left to claim,
 * or a platform where the status cannot be read at all.
 */
@Composable
internal actual fun DefaultAppsOfferHost(isFirstWindow: Boolean) {
    if (!isFirstWindow) return

    var unclaimed by remember { mutableStateOf<List<DefaultAppStatus>>(emptyList()) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!DefaultAppsManager.isSupported()) return@LaunchedEffect
        // After the load, not in a `remember`: before the file has been read
        // `shouldOfferPrompt` answers from defaults and would offer a prompt that
        // was already made and declined on a previous launch.
        DefaultAppsSettingsManager.ensureLoaded()
        if (!DefaultAppsSettingsManager.shouldOfferPrompt()) return@LaunchedEffect
        // OS default handlers are machine-wide and belong to the main profile; a BOSS profile's
        // window never asks to take them over.
        if (ai.rever.boss.plugin.pathutils.BossDirectories.isProfile) return@LaunchedEffect
        val declined = DefaultAppsSettingsManager.declinedCategories()
        val statuses =
            DefaultAppsManager
                .statuses(DefaultAppsManager.categories.filter { it.id == "web-links" })
                .filterNot { it.state.isOurs }
                .filterNot { it.category.id in declined }
        if (statuses.isEmpty()) {
            // Everything is already BOSS's. Nothing to ask, and marking the
            // prompt shown would be wrong: if the user later loses an
            // association to another app, the offer is still worth making once.
            logger.debug(LogCategory.SYSTEM, "No default-browser offer needed")
            return@LaunchedEffect
        }
        unclaimed = statuses
        visible = true
        // Before the dialog is answered, deliberately. See the KDoc.
        DefaultAppsSettingsManager.markPromptShown()
    }

    if (!visible) return

    DefaultAppsOfferDialog(
        unclaimed = unclaimed,
        onClose = { visible = false },
    )
}

@Composable
internal fun DefaultAppsOfferDialog(
    unclaimed: List<DefaultAppStatus>,
    onClose: () -> Unit,
    claimCategories: suspend (List<FileTypeCategory>) -> ClaimOutcome = { DefaultAppsManager.claimAll(it) },
    recordDeclined: suspend (List<String>) -> Unit = { DefaultAppsSettingsManager.markDeclined(it) },
    operationScope: kotlinx.coroutines.CoroutineScope = DefaultAppsManager.offerScope,
) {
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Keyed on `unclaimed` so the selection is rebuilt if the offered set ever
    // changes. Built with addAll rather than a spread, which would copy the array
    // once more for nothing.
    val offered = unclaimed.filter { it.category.id == "web-links" }
    val selected =
        remember(offered) {
            mutableStateListOf<String>().apply { addAll(offered.map { it.category.id }) }
        }
    val scope = operationScope
    var dismissed by remember { mutableStateOf(false) }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        // Everything on offer counts as declined, so a later "Set all" in
        // Settings does not quietly claim what was just refused.
        scope.launch { recordDeclined(offered.map { it.category.id }) }
        onClose()
    }

    fun claim() {
        working = true
        error = null
        scope.launch {
            try {
                val categories = offered.filter { it.category.id in selected }.map { it.category }
                val declined = offered.map { it.category.id }.filterNot { it in selected }
                if (declined.isNotEmpty()) recordDeclined(declined)

                val outcome = claimCategories(categories)
                if (dismissed) return@launch
                when (outcome) {
                    is ClaimOutcome.Claimed -> {
                        dismissed = true
                        onClose()
                    }

                    is ClaimOutcome.NeedsUserAction -> {
                        error = outcome.instruction
                    }

                    is ClaimOutcome.Failed -> {
                        error = outcome.message
                    }
                }
            } finally {
                if (!dismissed) working = false
            }
        }
    }

    BossDialog(
        onDismissRequest = { dismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Card(
            modifier =
                Modifier
                    .width(460.dp)
                    .testTag("default-browser-offer")
                    .onPreviewKeyEvent { event ->
                        val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                        if (escape) {
                            dismiss()
                            true
                        } else {
                            false
                        }
                    },
            shape = RoundedCornerShape(8.dp),
            backgroundColor = BossTheme.colors.panel,
            elevation = 8.dp,
        ) {
            OfferBody(
                unclaimed = offered,
                selected = selected,
                working = working,
                error = error,
                onToggle = { id, checked -> if (checked) selected.add(id) else selected.remove(id) },
                onDismiss = { dismiss() },
                onClaim = { claim() },
            )
        }
    }
}

@Composable
private fun OfferBody(
    unclaimed: List<DefaultAppStatus>,
    selected: List<String>,
    working: Boolean,
    error: String?,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onClaim: () -> Unit,
) {
    // "Repair" whenever a BOSS component holds any of these, which is the state
    // almost every existing install is in - and it needs different words, because
    // the user very likely did set "BOSS" as their browser and got the wrong one
    // of two entries with that name.
    val repairing = unclaimed.any { it.state is DefaultHandlerState.OurEngine }

    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = if (repairing) "Use BOSS for web links?" else "Make BOSS your default browser?",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text =
                if (repairing) {
                    "Web links currently open in a BOSS component with no window. " +
                        "Repairing points them back at the app."
                } else {
                    "Open web links with BOSS. File defaults stay unchanged. " +
                        "You can change this later in Settings > Default Apps."
                },
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(14.dp))

        unclaimed.forEach { status ->
            CategoryCheckbox(
                status = status,
                checked = status.category.id in selected,
                enabled = !working,
                onCheckedChange = { checked -> onToggle(status.category.id, checked) },
            )
        }

        error?.let { text ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                color = BossTheme.colors.alert,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        OfferActions(
            confirmLabel = if (repairing) "Repair browser default" else "Make default browser",
            working = working,
            canConfirm = selected.isNotEmpty(),
            onDismiss = onDismiss,
            onClaim = onClaim,
        )
    }
}

@Composable
private fun OfferActions(
    confirmLabel: String,
    working: Boolean,
    canConfirm: Boolean,
    onDismiss: () -> Unit,
    onClaim: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = BossTheme.colors.signalText,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Setting...", fontSize = 12.sp, color = BossTheme.colors.textSecondary)
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(
            onClick = onDismiss,
            colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
        ) {
            Text("Skip")
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onClaim,
            enabled = !working && canConfirm,
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal,
                ),
        ) {
            Text(confirmLabel)
        }
    }
}

@Composable
private fun CategoryCheckbox(
    status: DefaultAppStatus,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = BossTheme.colors.signal,
                    uncheckedColor = BossTheme.colors.textMuted,
                    checkmarkColor = BossTheme.colors.onSignal,
                ),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.category.displayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = BossTheme.colors.textPrimary,
            )
            Text(
                text =
                    if (status.state is DefaultHandlerState.OurEngine) {
                        "Currently opened by a BOSS component with no window"
                    } else {
                        status.category.description
                    },
                fontSize = 11.sp,
                color = BossTheme.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}
