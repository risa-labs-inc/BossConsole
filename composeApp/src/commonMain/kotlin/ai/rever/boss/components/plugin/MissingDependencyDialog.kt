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

/**
 * Offers to install a dependency a just-installed plugin declares but which is absent.
 *
 * The point is that it *installs*, rather than telling someone where to go and look. The host
 * can: `PluginRepository.downloadPlugin(pluginId, …)` resolves a plugin by id and
 * `DynamicPluginManager.installPlugin` loads the result. A plugin cannot do either, which is
 * why the equivalent prompt inside a plugin can only open the Toolbox.
 *
 * @param prompt the unmet dependency plus the installer that can fix it
 * @param installing true while the install is in flight, so the dialog stays put and shows why
 * @param error a failure from the last attempt, kept on screen with Retry rather than vanishing
 */
@Composable
fun MissingDependencyDialog(
    prompt: MissingDependencyPrompt,
    installing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val missing = prompt.missing

    // Show the id straight away and replace it with the store's display name if that
    // resolves. The alternative - waiting - means a dialog that appears late or not at all
    // when the store is unreachable, which is exactly when the user most needs telling.
    val resolvedName by
        produceState(initialValue = missing.missingPluginId, missing.missingPluginId) {
            runCatching { prompt.installer.displayNameFor(missing.missingPluginId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { value = it }
        }

    BossDialog(
        // Not dismissable while installing: the install continues regardless, and a dialog
        // that vanishes mid-download reads as "nothing happened".
        onDismissRequest = { if (!installing) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = !installing,
                dismissOnClickOutside = !installing,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Card(
            modifier =
                Modifier
                    .width(400.dp)
                    .onKeyEvent { event ->
                        val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                        if (!installing && escape) {
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
            MissingDependencyBody(
                missing = missing,
                resolvedName = resolvedName,
                installing = installing,
                error = error,
                onDismiss = onDismiss,
                onInstall = onInstall,
            )
        }
    }
}

@Composable
private fun MissingDependencyBody(
    missing: MissingPluginDependency,
    resolvedName: String,
    installing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = if (missing.optional) "Recommended plugin" else "Required plugin missing",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            // Both names in this sentence are attacker-influenced - the id comes from a
            // plugin manifest, the resolved name from a store listing - so neither is allowed
            // to grow the dialog or run on into something that reads like our own copy.
            text = missing.description(resolvedName),
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // The plugin id, always, even once the store name resolves. This is a consent dialog
        // for downloading and running code, and the display name is the one string the least
        // trustworthy party controls - so the identity the host will actually install by is
        // shown alongside it. The clamps stop a crafted name breaking the dialog; they do not
        // stop it misleading inside it.
        Text(
            text = missing.missingPluginId,
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

        MissingDependencyActions(
            optional = missing.optional,
            resolvedName = resolvedName,
            installing = installing,
            hasError = error != null,
            onDismiss = onDismiss,
            onInstall = onInstall,
        )
    }
}

@Composable
private fun MissingDependencyActions(
    optional: Boolean,
    resolvedName: String,
    installing: Boolean,
    hasError: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (installing) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = BossTheme.colors.signalText,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Installing $resolvedName",
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
            enabled = !installing,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary,
                ),
        ) {
            // An optional dependency is a suggestion, so declining it is not "skipping" a
            // step the plugin needed.
            Text(if (optional) "Not now" else "Skip")
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onInstall,
            enabled = !installing,
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal,
                ),
        ) {
            Text(if (hasError) "Retry" else "Install")
        }
    }
}
