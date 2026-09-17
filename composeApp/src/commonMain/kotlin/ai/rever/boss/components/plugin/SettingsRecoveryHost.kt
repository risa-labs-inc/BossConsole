package ai.rever.boss.components.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal val LocalSettingsRecoveryRequest = compositionLocalOf<((PluginRecoveryTarget) -> Unit)?> { null }

/** Recovery outlives the unavailable notice that enabling a provider removes. */
@Composable
internal fun SettingsRecoveryHost(content: @Composable () -> Unit) {
    var target by remember { mutableStateOf<PluginRecoveryTarget?>(null) }
    val recovery = LocalPluginRecoveryContext.current
    CompositionLocalProvider(LocalSettingsRecoveryRequest provides { target = it }) {
        content()
    }
    target?.let { requested ->
        PluginHealthCenterDialog(
            manager = recovery?.manager,
            delegate = recovery?.delegate,
            target = requested,
            onDismiss = { target = null },
        )
    }
}
