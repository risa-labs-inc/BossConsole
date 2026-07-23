package ai.rever.boss.plugin.sandbox.ui

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Crash boundary for small plugin-contributed UI extensions (status-bar
 * widgets, settings pages) that have no [PluginSandbox] of their own.
 *
 * The heavier [PluginErrorBoundary] needs the plugin's sandbox and a parent
 * that rebuilds on crash; extension surfaces have neither. This boundary
 * registers a crash-interceptor callback for [pluginId] (the interceptor
 * supports multiple boundaries per plugin), and on an attributed crash tears
 * down its own subtree via a `key()` bump and renders [fallback] instead —
 * the corrupted content group is discarded rather than recomposed in place.
 *
 * Without a boundary, a crash from a plugin whose ONLY surface is an
 * extension is unattributable and escalates to the app-level CrashHandler.
 *
 * @param pluginId Owning plugin, or null when unknown — content then renders
 *   bare (host-owned content, or a provider whose classloader isn't a plugin
 *   classloader).
 * @param surface Short label for logs and the default fallback text, e.g.
 *   "status-bar item my.plugin:sync".
 * @param fallback Rendered after a crash; defaults to a compact warning text.
 */
@Composable
fun PluginExtensionBoundary(
    pluginId: String?,
    surface: String,
    fallback: @Composable (Throwable) -> Unit = { error ->
        Text(
            text = "⚠ $surface failed: ${error.message ?: error::class.simpleName}",
            color = BossTheme.colors.alert,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    },
    content: @Composable () -> Unit
) {
    if (pluginId == null) {
        content()
        return
    }

    val logger = remember { BossLogger.forComponent("PluginExtensionBoundary") }
    var crashCount by remember(pluginId) { mutableStateOf(0) }
    var error by remember(pluginId) { mutableStateOf<Throwable?>(null) }

    // Registered via remember{} so the interceptor is active BEFORE content()
    // first composes (DisposableEffect would arm it too late for first-frame
    // crashes like NoSuchMethodError). The callback runs on the crashing
    // thread; snapshot writes are thread-safe.
    val registration = remember(pluginId) {
        registerCrashInterceptor(pluginId) { e ->
            logger.warn(LogCategory.UI, "Plugin extension crashed; showing fallback", mapOf(
                "pluginId" to pluginId,
                "surface" to surface,
                "errorType" to e::class.simpleName
            ))
            error = e
            crashCount++
        }
    }
    DisposableEffect(pluginId) {
        onDispose { registration?.invoke() }
    }

    // key(crashCount): on crash the whole content group is torn down and the
    // fallback composes fresh — a corrupted subtree must be discarded, not
    // recomposed in place (same rationale as SidePanel's key(crashState)).
    key(crashCount) {
        val e = error
        if (e != null) {
            fallback(e)
        } else {
            content()
        }
    }
}
