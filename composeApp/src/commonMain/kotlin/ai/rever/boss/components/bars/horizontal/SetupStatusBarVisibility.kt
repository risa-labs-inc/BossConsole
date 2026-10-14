package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.plugin.registries.RegistryAccess
import ai.rever.boss.components.plugin.registries.StatusBarRegistryImpl
import ai.rever.boss.plugin.api.StatusBarItemProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Terminal Tab registers this item only while a setup session needs a home. Keep the bar
 * composed until the session is acknowledged: its item also hosts the reopened setup dialog.
 * This is temporary chrome visibility, never a change to the user's appearance preferences.
 */
@Composable
internal fun setupKeepsBottomBarVisible(): Boolean {
    val items by StatusBarRegistryImpl.items.collectAsState()
    val access by StatusBarRegistryImpl.access.collectAsState()
    return hasAccessibleSetupStatus(items, access)
}

internal fun hasAccessibleSetupStatus(
    items: Map<String, StatusBarItemProvider>,
    access: RegistryAccess,
): Boolean {
    val setup = items[TERMINAL_SETUP_STATUS_ITEM_ID] ?: return false
    return access.permits(requiresAdmin = false, requiredPermissions = setup.requiredPermissions)
}

internal const val TERMINAL_SETUP_STATUS_ITEM_ID = "ai.rever.boss.plugin.dynamic.terminaltab:setup-progress"
