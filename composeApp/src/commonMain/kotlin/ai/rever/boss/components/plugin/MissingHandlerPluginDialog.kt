package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException

/**
 * Offers to install or enable the plugin that would have rendered something BOSS
 * was just asked to open.
 *
 * Separate from [MissingDependencyDialog] rather than a parameterisation of it:
 * that one's copy is specifically about a plugin declaring a dependency in its
 * manifest ("Flow needs the AI Gateway"), and the two questions differ in who is
 * asking and what they were doing. Here the user double-clicked a file or clicked
 * a link, and the sentence that makes sense names *that*.
 *
 * It also has a second verb. The dependency dialog only ever installs; this one
 * offers **Enable** when the plugin is on disk and switched off, because
 * installing something already installed cannot fix it.
 *
 * @param prompt the missing plugin plus the means to fix it
 * @param working true while the install or enable is in flight
 * @param error a failure from the last attempt, kept on screen with Retry
 */
@Composable
fun MissingHandlerPluginDialog(
    prompt: MissingHandlerPluginPrompt,
    working: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onResolve: () -> Unit,
) {
    val missing = prompt.missing

    // Show the id immediately and improve it if the store answers. Waiting would
    // mean a dialog that appears late or not at all when the store is
    // unreachable, which is exactly when the user most needs telling.
    val resolvedName by
        produceState(initialValue = missing.pluginId, missing.pluginId) {
            runCatching { prompt.displayName() }
                .getOrElse { error ->
                    // Same contract as MissingDependencyDialog: a dismissed dialog is not a
                    // lookup failure, and the two dialogs should not disagree about that.
                    if (error is CancellationException) throw error
                    null
                }?.takeIf { it.isNotBlank() }
                ?.let { value = it }
        }

    BossDialog(
        // Not dismissable while working: the install continues regardless, and a
        // dialog that vanishes mid-download reads as "nothing happened".
        onDismissRequest = { if (!working) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = !working,
                dismissOnClickOutside = !working,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Card(
            modifier =
                Modifier
                    .width(400.dp)
                    .onKeyEvent { event ->
                        val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                        if (!working && escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            shape = RoundedCornerShape(8.dp),
            backgroundColor = BossTheme.colors.panel,
            elevation = 8.dp,
        ) {
            MissingHandlerPluginBody(
                missing = missing,
                resolvedName = resolvedName,
                working = working,
                error = error,
                onDismiss = onDismiss,
                onResolve = onResolve,
            )
        }
    }
}

@Composable
private fun MissingHandlerPluginBody(
    missing: MissingHandlerPlugin,
    resolvedName: String,
    working: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onResolve: () -> Unit,
) {
    val enabling = missing.remedy == MissingHandlerRemedy.ENABLE

    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = if (enabling) "Plugin is switched off" else "Plugin needed",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            // `purpose` is a file name or a URL the OS handed over and
            // `resolvedName` comes from a store listing, so neither is allowed to
            // grow the dialog or run on into something that reads like our own
            // copy.
            text =
                if (enabling) {
                    "${missing.purpose} needs $resolvedName, which is installed but switched off."
                } else {
                    "${missing.purpose} needs $resolvedName, the plugin that opens ${missing.capability}."
                },
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // The plugin id, always, even once the store name resolves. For an
        // install this is a consent dialog for downloading and running code, and
        // the display name is the one string the least trustworthy party
        // controls, so the identity the host will act on is shown alongside it.
        Text(
            text = missing.pluginId,
            fontSize = 11.sp,
            color = BossTheme.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error,
                fontSize = 12.sp,
                color = BossTheme.colors.alert,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        MissingHandlerPluginActions(
            enabling = enabling,
            resolvedName = resolvedName,
            working = working,
            hasError = error != null,
            onDismiss = onDismiss,
            onResolve = onResolve,
        )
    }
}

@Composable
private fun MissingHandlerPluginActions(
    enabling: Boolean,
    resolvedName: String,
    working: Boolean,
    hasError: Boolean,
    onDismiss: () -> Unit,
    onResolve: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = BossTheme.colors.signalText,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (enabling) "Enabling $resolvedName" else "Installing $resolvedName",
                fontSize = 12.sp,
                color = BossTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(
            onClick = onDismiss,
            enabled = !working,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary,
                ),
        ) {
            Text("Not now")
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onResolve,
            enabled = !working,
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal,
                ),
        ) {
            Text(
                when {
                    hasError -> "Retry"
                    enabling -> "Enable"
                    else -> "Install"
                },
            )
        }
    }
}
