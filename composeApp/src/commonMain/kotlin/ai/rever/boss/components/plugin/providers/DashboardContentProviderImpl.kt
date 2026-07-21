package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.dashboard.Dashboard
import ai.rever.boss.components.events.URLEventBus
import ai.rever.boss.dashboard.SplitTemplate
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.Project
import ai.rever.boss.plugin.api.DashboardContentProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Implementation of DashboardContentProvider that wraps the Boss Console Dashboard.
 *
 * This provides a simplified version of the dashboard for browser plugins to display
 * when showing about:blank pages.
 */
class DashboardContentProviderImpl : DashboardContentProvider {

    @Composable
    override fun DashboardContent(onNavigate: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        val windowId = LocalWindowId.current

        Dashboard(
            onOpenFile = { /* No-op for browser plugin */ },
            onOpenUrl = onNavigate,
            onOpenProject = { /* No-op for browser plugin */ },
            onNewTab = { /* No-op for browser plugin */ },
            onNewTerminal = { /* No-op for browser plugin */ },
            onNewWindow = { /* No-op for browser plugin */ },
            onOpenProjectDialog = { /* No-op for browser plugin */ },
            onOpenFileDialog = { /* No-op for browser plugin */ },
            onNewProject = { /* No-op for browser plugin */ },
            onApplySplitTemplate = { /* No-op for browser plugin */ },
            onActivatePlugin = { /* No-op for browser plugin */ },
            onShowSettings = null
        )
    }
}
