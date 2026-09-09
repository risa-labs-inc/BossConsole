package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * @param onInstall receives the plan that was on screen when Install was pressed, so what gets
 *   installed is exactly what the user was shown, even if the store answered after the click
 */
@Composable
fun MissingDependencyDialog(
    prompt: MissingDependencyPrompt,
    installing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onInstall: (DependencyInstallPlan) -> Unit,
) {
    val missing = prompt.missing

    // Show the id straight away and replace it with the store's display name if that
    // resolves. The alternative - waiting - means a dialog that appears late or not at all
    // when the store is unreachable, which is exactly when the user most needs telling.
    val resolvedName by rememberDependencyName(prompt)

    val plan = rememberInstallPlan(prompt).value

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
                alsoInstalls = plan.order.dropLast(1),
                installing = installing,
                error = error,
                onDismiss = onDismiss,
                onInstall = { onInstall(plan) },
            )
        }
    }
}

@Composable
internal fun rememberDependencyName(prompt: MissingDependencyPrompt): State<String> =
    key(prompt) {
        val pluginId = prompt.missing.missingPluginId
        produceState(initialValue = pluginId, pluginId) {
            runCatching { prompt.installer.displayNameFor(pluginId) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    null
                }?.takeIf { it.isNotBlank() }
                ?.let { value = it }
        }
    }

/**
 * What Install will do for [prompt], as a plan of the one missing plugin until the store answers.
 *
 * Same shape as the display name, for the same reason: the plan comes from the store, so the
 * dialog opens immediately and grows an "also installs" line when the answer lands. Install acts
 * on whatever plan is on screen at the click, so someone who clicks before the store answers gets
 * today's single install and never something they were not shown. A store that cannot answer
 * leaves the single plan in place.
 */
@Composable
internal fun rememberInstallPlan(prompt: MissingDependencyPrompt): State<DependencyInstallPlan> =
    key(prompt) {
        val pluginId = prompt.missing.missingPluginId
        produceState(
            initialValue =
                DependencyInstallPlan(
                    order = listOf(pluginId),
                    unresolved = emptySet(),
                    cyclic = false,
                    truncated = false,
                ),
            pluginId,
        ) {
            runCatching { prompt.installer.planFor(pluginId) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    null
                }?.let { value = it }
        }
    }

@Composable
internal fun MissingDependencyBody(
    missing: MissingPluginDependency,
    resolvedName: String,
    alsoInstalls: List<String>,
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

        AdditionalDependencies(alsoInstalls)

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

@Composable
private fun AdditionalDependencies(pluginIds: List<String>) {
    if (pluginIds.isEmpty()) return
    Spacer(modifier = Modifier.height(8.dp))
    Column(modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
        Text("Also installs:", fontSize = 11.sp, color = BossTheme.colors.textMuted)
        pluginIds.forEach { pluginId ->
            Text(pluginId, fontSize = 11.sp, color = BossTheme.colors.textMuted)
        }
    }
}
